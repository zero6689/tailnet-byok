package io.github.zero6689.tailnetbyok.data.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.core.text.TextRef
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the one secret this app holds, using a key that cannot leave the
 * device.
 *
 * # The design, and what it actually buys
 *
 * An AES-256-GCM key is generated inside the Android Keystore and is created
 * with `setRandomizedEncryptionRequired(true)`, so the platform refuses to reuse
 * an IV. The key material itself is never readable by this process: on devices
 * with a TEE or StrongBox it does not exist in the app's address space at all,
 * and on every device `KeyStore.getKey` would return a handle, not bytes.
 *
 * What this protects against: someone who obtains a copy of the app's data
 * directory — a rooted-device file copy, a forensic image, a stray backup, a
 * bug report that zipped the sandbox. What it does *not* protect against: an
 * attacker running code as this app on this unlocked device. That distinction is
 * written down here and in `PRIVACY.md` rather than left for the reader to
 * assume a stronger guarantee.
 *
 * # Format
 *
 * `base64( iv[12] || ciphertext || tag[16] )`
 *
 * The IV is stored with the ciphertext because GCM requires it per message, and
 * it is not secret. The tag is appended by the JCE provider. Nothing else is
 * prepended — no version byte, no algorithm id — because there is exactly one
 * algorithm here and a version field would only invite a migration path nobody
 * needs. If that ever changes, the storage key changes with it.
 *
 * # Key invalidation
 *
 * If the user removes their device credential, or the lock screen is reset, keys
 * created with `setUserAuthenticationRequired(true)` are destroyed. This vault
 * sets that flag to `false` — an auth prompt on every read of a config field
 * would be hostile, and the key is not worth a biometric gate — but the
 * invalidation failure is still handled explicitly, because it is reachable via
 * `setUnlockedDeviceRequired`, which *is* enabled on API 28+.
 */
class KeystoreSecretVault(
    private val alias: String = DEFAULT_ALIAS,
) {

    sealed interface Result<out T> {
        data class Success<T>(val value: T) : Result<T>

        /** No key exists yet. Not an error: the first write creates one. */
        data object NoKey : Result<Nothing>

        /** The key is gone and the data is unrecoverable. The user must re-enter. */
        data class KeyInvalidated(val reason: TextRef) : Result<Nothing>

        data class Failure(val reason: TextRef, val cause: Throwable? = null) : Result<Nothing>
    }

    /** True when a usable key is already materialised in the Keystore. */
    fun hasKey(): Boolean = runCatching {
        loadKeyStore().containsAlias(alias)
    }.getOrDefault(false)

    /**
     * Whether the key is held in hardware rather than software.
     *
     * Surfaced in the UI as a trust signal, because it is the difference between
     * "an attacker with a filesystem image needs your unlocked device" and "an
     * attacker with a filesystem image needs only the Keystore file". Reporting
     * `false` is not a failure — many devices and all emulators lack a TEE.
     */
    fun isHardwareBacked(): Boolean {
        val key = runCatching { existingKey() }.getOrNull() ?: return false
        return runCatching {
            val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, KEYSTORE)
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel != KeyProperties.SECURITY_LEVEL_SOFTWARE
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        }.getOrDefault(false)
    }

    /**
     * Encrypts [plaintext].
     *
     * The caller owns the returned bytes and should overwrite them once they are
     * stored — see [wipe].
     */
    fun encrypt(plaintext: ByteArray): Result<ByteArray> = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "unexpected IV length ${iv.size}" }
        Result.Success(iv + cipher.doFinal(plaintext))
    } catch (e: KeyPermanentlyInvalidatedException) {
        Result.KeyInvalidated(TextRef.of(R.string.key_error_invalidated))
    } catch (e: Exception) {
        SafeLog.e(TAG, "encryption failed", e)
        Result.Failure(
            TextRef.of(R.string.key_error_encrypt_failed, e.message ?: "unknown error"),
            e,
        )
    }

    /** Decrypts a payload produced by [encrypt]. */
    fun decrypt(payload: ByteArray): Result<ByteArray> = try {
        if (payload.size <= IV_LENGTH) {
            Result.Failure(TextRef.of(R.string.key_error_truncated))
        } else {
            val key = existingKey()
            if (key == null) {
                Result.NoKey
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH),
                )
                Result.Success(cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH))
            }
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        Result.KeyInvalidated(TextRef.of(R.string.key_error_invalidated))
    } catch (e: UnrecoverableKeyException) {
        Result.KeyInvalidated(TextRef.of(R.string.key_error_invalidated))
    } catch (e: javax.crypto.AEADBadTagException) {
        // Wrong key, or the ciphertext was tampered with. Both mean the same
        // thing to the user: the stored value cannot be trusted any more.
        Result.KeyInvalidated(TextRef.of(R.string.key_error_invalidated))
    } catch (e: Exception) {
        SafeLog.e(TAG, "decryption failed", e)
        Result.Failure(
            TextRef.of(R.string.key_error_decrypt_failed, e.message ?: "unknown error"),
            e,
        )
    }

    /**
     * Destroys the key, which renders every ciphertext this vault produced
     * permanently unreadable. This is what "forget my key" must actually do;
     * deleting the ciphertext alone would leave the key in place.
     */
    fun deleteKey(): Result<Unit> = try {
        loadKeyStore().deleteEntry(alias)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Failure(
            TextRef.of(R.string.key_error_delete_failed, e.message ?: "unknown error"),
            e,
        )
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = runCatching {
        loadKeyStore().getKey(alias, null) as? SecretKey
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // The platform then refuses to encrypt twice with the same IV, which
            // is the one mistake that actually breaks GCM.
            .setRandomizedEncryptionRequired(true)
            // No biometric prompt: this key guards a config value, and gating it
            // behind authentication would make the app unusable in a car, on a
            // bike, or with wet hands. Stated explicitly so the choice is
            // reviewable rather than accidental.
            .setUserAuthenticationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Ciphertext written while the screen is locked becomes
                    // undecryptable. Costs nothing here: the app has no
                    // background component that reads the credential while
                    // locked.
                    setUnlockedDeviceRequired(true)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val TAG = "Vault"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BITS = 128

        private const val DEFAULT_ALIAS = "tailnet_byok_credential_v1"

        /**
         * Best-effort zeroing of a byte array.
         *
         * Honest caveat: the JVM gives no guarantee that a copy was not made, and
         * `Arrays.fill` is not a security boundary. It is still worth doing,
         * because it shortens the window in which a heap dump would contain the
         * key, and because it documents the intent at the call site.
         */
        fun wipe(bytes: ByteArray?) {
            if (bytes == null) return
            java.util.Arrays.fill(bytes, 0)
        }
    }
}

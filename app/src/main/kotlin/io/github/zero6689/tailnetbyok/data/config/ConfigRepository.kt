package io.github.zero6689.tailnetbyok.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.zero6689.tailnetbyok.R
import io.github.zero6689.tailnetbyok.core.log.Redact
import io.github.zero6689.tailnetbyok.core.log.SafeLog
import io.github.zero6689.tailnetbyok.core.text.TextRef
import io.github.zero6689.tailnetbyok.data.crypto.KeystoreSecretVault
import io.github.zero6689.tailnetbyok.net.ProviderId
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.configStore: DataStore<Preferences> by preferencesDataStore(
    name = "tailnet_byok_config",
)

/**
 * Persistence for the app's configuration.
 *
 * Two stores, on purpose:
 *
 *  * non-secret settings go into DataStore as plain values, because encrypting a
 *    hostname buys nothing and makes the file unreadable when debugging;
 *  * the auth key goes through [KeystoreSecretVault] and is persisted only as
 *    ciphertext.
 *
 * The file lives under `noBackupFilesDir`, not `filesDir`. Combined with
 * `android:allowBackup="false"` that means the ciphertext cannot reach a cloud
 * backup — which matters, because the Keystore key that decrypts it is
 * hardware-bound and would not travel with it anyway. The only outcome of a
 * backup would be an unreadable blob on someone else's server.
 */
class ConfigRepository(
    private val context: Context,
    private val vault: KeystoreSecretVault,
) {

    val config: Flow<AppConfig> = context.configStore.data
        .catch { e ->
            // A corrupt preferences file must not take the app down; fall back
            // to defaults and let the next write heal it.
            if (e is IOException) {
                SafeLog.e(TAG, "config store unreadable, using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs ->
            AppConfig(
                provider = ProviderId.fromStorageKey(prefs[KEY_PROVIDER]),
                scheme = prefs[KEY_SCHEME] ?: AppConfig.DEFAULT_SCHEME,
                hostInput = prefs[KEY_HOST] ?: "",
                port = prefs[KEY_PORT] ?: AppConfig.DEFAULT_PORT,
                path = prefs[KEY_PATH] ?: AppConfig.DEFAULT_PATH,
                controlUrl = prefs[KEY_CONTROL_URL] ?: "",
                nodeHostname = prefs[KEY_NODE_HOSTNAME] ?: AppConfig.DEFAULT_NODE_HOSTNAME,
                ephemeral = prefs[KEY_EPHEMERAL] ?: true,
                hasStoredKey = prefs[KEY_KEY_PRESENT] ?: false,
                acknowledgedSecurityModel = prefs[KEY_ACKNOWLEDGED] ?: false,
            )
        }

    /** Snapshot read, for one-shot work like building a request. */
    suspend fun current(): AppConfig = config.first()

    /**
     * Applies [transform] to the stored settings.
     *
     * Deliberately does not touch the credential: settings and secrets change
     * for different reasons, and a single "save" that writes both is how a key
     * ends up clobbered by a form that merely changed the port.
     */
    suspend fun update(transform: (AppConfig) -> AppConfig) {
        context.configStore.edit { prefs ->
            val before = AppConfig(
                provider = ProviderId.fromStorageKey(prefs[KEY_PROVIDER]),
                scheme = prefs[KEY_SCHEME] ?: AppConfig.DEFAULT_SCHEME,
                hostInput = prefs[KEY_HOST] ?: "",
                port = prefs[KEY_PORT] ?: AppConfig.DEFAULT_PORT,
                path = prefs[KEY_PATH] ?: AppConfig.DEFAULT_PATH,
                controlUrl = prefs[KEY_CONTROL_URL] ?: "",
                nodeHostname = prefs[KEY_NODE_HOSTNAME] ?: AppConfig.DEFAULT_NODE_HOSTNAME,
                ephemeral = prefs[KEY_EPHEMERAL] ?: true,
                hasStoredKey = prefs[KEY_KEY_PRESENT] ?: false,
                acknowledgedSecurityModel = prefs[KEY_ACKNOWLEDGED] ?: false,
            )
            val after = transform(before)
            prefs[KEY_PROVIDER] = after.provider.storageKey
            prefs[KEY_SCHEME] = after.scheme
            prefs[KEY_HOST] = after.hostInput
            prefs[KEY_PORT] = after.port
            prefs[KEY_PATH] = after.path
            prefs[KEY_CONTROL_URL] = after.controlUrl
            prefs[KEY_NODE_HOSTNAME] = after.nodeHostname
            prefs[KEY_EPHEMERAL] = after.ephemeral
            prefs[KEY_ACKNOWLEDGED] = after.acknowledgedSecurityModel
            // hasStoredKey is written only by the credential methods below.
        }
    }

    sealed interface KeyWrite {
        data object Stored : KeyWrite
        data class Rejected(val reason: TextRef) : KeyWrite
    }

    /**
     * Encrypts and stores [authKey].
     *
     * The plaintext is converted straight to bytes, handed to the vault, and the
     * intermediate array is wiped. Nothing here logs the value, and the value is
     * never placed in a preference key that a `dumpsys` or a debugger could
     * surface.
     */
    suspend fun storeAuthKey(authKey: String): KeyWrite {
        val trimmed = authKey.trim()
        if (trimmed.isEmpty()) return KeyWrite.Rejected(TextRef.of(R.string.key_error_empty))

        // Cheap shape check. Not authentication — the control plane decides that
        // — but it catches the common paste error (a hostname, a URL, a whole
        // `tailscale up --authkey=…` line) before a confusing network failure.
        if (trimmed.contains(' ') || trimmed.contains('\n')) {
            return KeyWrite.Rejected(TextRef.of(R.string.key_error_whitespace))
        }

        val plaintext = trimmed.toByteArray(Charsets.UTF_8)
        return try {
            when (val result = vault.encrypt(plaintext)) {
                is KeystoreSecretVault.Result.Success -> {
                    val encoded = android.util.Base64.encodeToString(
                        result.value,
                        android.util.Base64.NO_WRAP,
                    )
                    KeystoreSecretVault.wipe(result.value)
                    context.configStore.edit { prefs ->
                        prefs[KEY_KEY_CIPHERTEXT] = encoded
                        prefs[KEY_KEY_PRESENT] = true
                    }
                    SafeLog.i(TAG, "auth key stored (${Redact.fingerprint(trimmed)}), encrypted at rest")
                    KeyWrite.Stored
                }
                is KeystoreSecretVault.Result.KeyInvalidated ->
                    KeyWrite.Rejected(result.reason)
                is KeystoreSecretVault.Result.Failure ->
                    KeyWrite.Rejected(result.reason)
                KeystoreSecretVault.Result.NoKey ->
                    KeyWrite.Rejected(TextRef.of(R.string.key_error_keystore_unavailable))
            }
        } finally {
            KeystoreSecretVault.wipe(plaintext)
        }
    }

    sealed interface KeyRead {
        data class Present(val authKey: String) : KeyRead
        data object Absent : KeyRead
        data class Unreadable(val reason: TextRef) : KeyRead
    }

    /**
     * Decrypts the stored credential.
     *
     * The returned string cannot be wiped — see the note on `TailnetCredentials`.
     * Callers should hold it for the shortest possible time and never copy it
     * into state that outlives the connection attempt.
     */
    suspend fun readAuthKey(): KeyRead {
        val prefs = context.configStore.data.first()
        val encoded = prefs[KEY_KEY_CIPHERTEXT] ?: return KeyRead.Absent
        val payload = try {
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            SafeLog.e(TAG, "stored credential is not valid base64", e)
            return KeyRead.Unreadable(TextRef.of(R.string.key_error_store_corrupt))
        }
        return when (val result = vault.decrypt(payload)) {
            is KeystoreSecretVault.Result.Success -> {
                val text = String(result.value, Charsets.UTF_8)
                KeystoreSecretVault.wipe(result.value)
                KeyRead.Present(text)
            }
            KeystoreSecretVault.Result.NoKey ->
                KeyRead.Unreadable(TextRef.of(R.string.key_error_no_encryption_key))
            is KeystoreSecretVault.Result.KeyInvalidated ->
                KeyRead.Unreadable(result.reason)
            is KeystoreSecretVault.Result.Failure ->
                KeyRead.Unreadable(result.reason)
        }
    }

    /**
     * Forgets the credential: deletes the ciphertext *and* destroys the Keystore
     * key, so the old value is unrecoverable even from a pre-existing copy of the
     * preferences file.
     */
    suspend fun clearAuthKey() {
        context.configStore.edit { prefs ->
            prefs.remove(KEY_KEY_CIPHERTEXT)
            prefs[KEY_KEY_PRESENT] = false
        }
        vault.deleteKey()
        SafeLog.i(TAG, "auth key cleared and keystore key destroyed")
    }

    fun isHardwareBacked(): Boolean = vault.isHardwareBacked()

    /**
     * The directory the embedded node keeps its state in.
     *
     * `noBackupFilesDir` specifically: node state contains a private node key, it
     * is useless on another device, and it must not be swept into a backup.
     */
    fun nodeStateDir(): String =
        java.io.File(context.noBackupFilesDir, "tailnet").absolutePath

    private companion object {
        const val TAG = "ConfigRepo"

        val KEY_PROVIDER = stringPreferencesKey("provider")
        val KEY_SCHEME = stringPreferencesKey("scheme")
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_PORT = intPreferencesKey("port")
        val KEY_PATH = stringPreferencesKey("path")
        val KEY_CONTROL_URL = stringPreferencesKey("control_url")
        val KEY_NODE_HOSTNAME = stringPreferencesKey("node_hostname")
        val KEY_EPHEMERAL = booleanPreferencesKey("ephemeral")
        val KEY_KEY_PRESENT = booleanPreferencesKey("key_present")
        val KEY_KEY_CIPHERTEXT = stringPreferencesKey("key_ciphertext")
        val KEY_ACKNOWLEDGED = booleanPreferencesKey("acknowledged_security_model")
    }
}

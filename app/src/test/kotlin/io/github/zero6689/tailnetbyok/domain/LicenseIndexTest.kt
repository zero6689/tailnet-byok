package io.github.zero6689.tailnetbyok.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app licence screen's input, which is the one thing standing between a
 * published APK and a BSD-3-Clause obligation it does not meet.
 *
 * Two layers are checked here: the parser (what the screen will do with whatever it
 * is handed) and the *generated asset itself*, read off disk. The second is the one
 * that would have caught the real failure — an asset that stops being generated, a
 * referenced file that is not copied, a component silently dropped from the index —
 * because none of those make the app crash. They just make the list shorter, and a
 * shorter list still looks complete.
 */
class LicenseIndexTest {

    @Test
    fun `a generated index parses into notes and components`() {
        val index = LicenseIndexParser.parse(
            """
            # Scope: the native bridge.
            #
            # Second note.
            project-mit	MIT	This app	project-mit.txt
            BSD-3-Clause--tailscale-com	BSD-3-Clause	github.com/tailscale	BSD-3-Clause--tailscale-com.txt
            """.trimIndent(),
        )
        assertEquals(listOf("Scope: the native bridge.", "Second note."), index.notes)
        assertEquals(2, index.entries.size)
        assertEquals("MIT", index.entries[0].licence)
        assertEquals("github.com/tailscale", index.entries[1].modules)
        assertEquals("BSD-3-Clause--tailscale-com.txt", index.entries[1].asset)
    }

    @Test
    fun `a byte-order mark does not become part of the first line`() {
        val index = LicenseIndexParser.parse("\uFEFFproject-mit\tMIT\tThis app\tproject-mit.txt\n")
        assertEquals("project-mit", index.entries.single().key)
    }

    @Test
    fun `blank lines are ignored`() {
        val index = LicenseIndexParser.parse(
            "\nproject-mit\tMIT\tThis app\tproject-mit.txt\n\n\n",
        )
        assertEquals(1, index.entries.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a row with the wrong number of fields is a build error, not a shorter list`() {
        LicenseIndexParser.parse("project-mit\tMIT\tproject-mit.txt\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a row with a blank field is a build error`() {
        LicenseIndexParser.parse("project-mit\tMIT\t\tproject-mit.txt\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an index with no components is a build error`() {
        LicenseIndexParser.parse("# just a note\n")
    }

    @Test
    fun `the generated asset ships, parses, and every entry has its text beside it`() {
        val assets = File("src/main/assets/licenses")
        val indexFile = File(assets, "notices.tsv")
        assertTrue(
            "missing ${indexFile.path} - run node scripts/generate-license-screen.mjs",
            indexFile.isFile,
        )

        val index = LicenseIndexParser.parse(indexFile.readText())

        // The bridge links 31 Go modules; they are carried in one file per distinct
        // licence text, so the count is of files, not modules. A floor rather than an
        // exact number: adding a dependency is allowed, losing the asset is not.
        assertTrue("expected at least 20 components, got ${index.entries.size}", index.entries.size >= 20)
        assertTrue("expected a scope note", index.notes.isNotEmpty())

        val projectEntry = index.entries.first()
        assertEquals("the app's own licence comes first", "MIT", projectEntry.licence)

        val licences = index.entries.map { it.licence }.toSet()
        for (expected in listOf("MIT", "BSD-3-Clause", "BSD-2-Clause", "Apache-2.0")) {
            assertTrue("no $expected entry in the index ($licences)", expected in licences)
        }

        for (entry in index.entries) {
            val body = File(assets, entry.asset)
            assertTrue("${entry.asset} is referenced but not shipped", body.isFile)
            assertTrue("${entry.asset} is empty", body.length() > 200)
            // The screen shows the licence name from the index and the text from the
            // file; a component whose text does not name its licence is a copy that
            // went wrong rather than a licence that is unusual.
            assertTrue(
                "${entry.asset} does not read like a licence text",
                body.readText().contains("Permission") || body.readText().contains("Licensed under") ||
                    body.readText().contains("Copyright"),
            )
        }
    }
}

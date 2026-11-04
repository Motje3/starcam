package com.wm.starcam.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegalPageTest {

    @Test
    fun readsTheBlocksOfMain() {
        val html = """
            <html><head><title>Not this</title></head><body>
            <main>
            <h1>Stelli privacy policy</h1>
            <p class="updated">Last updated: 4 November 2026</p>
            <p>Made by W&amp;M Websolutions.
               Email <a href="mailto:a@b.nl">a@b.nl</a>.</p>
            <h2>What the app uses</h2>
            <ul>
            <li><strong>Camera</strong>: to take the photos.</li>
            </ul>
            </main>
            <footer><p>Not this either</p></footer>
            </body></html>
        """.trimIndent()
        val blocks = LegalPage.parse(html)
        assertEquals(
            listOf(
                LegalPage.Block(LegalPage.Kind.Title, "Stelli privacy policy"),
                LegalPage.Block(LegalPage.Kind.Updated, "Last updated: 4 November 2026"),
                LegalPage.Block(LegalPage.Kind.Paragraph, "Made by W&M Websolutions. Email a@b.nl."),
                LegalPage.Block(LegalPage.Kind.Heading, "What the app uses"),
                LegalPage.Block(LegalPage.Kind.Item, "Camera: to take the photos."),
            ),
            blocks,
        )
    }

    /** The published pages are what the app shows: whole, and nothing left to fill in. */
    @Test
    fun thePublishedPagesReadWhole() {
        for (name in listOf("privacy.html", "terms.html")) {
            val file = File("../docs/legal/$name")
            assertTrue("$name is missing", file.isFile)
            val html = file.readText()
            assertFalse("$name still has a placeholder", "{{" in html)
            val blocks = LegalPage.parse(html)
            assertEquals(name, LegalPage.Kind.Title, blocks.first().kind)
            assertEquals(name, LegalPage.Kind.Updated, blocks[1].kind)
            assertTrue("$name has few sections", blocks.count { it.kind == LegalPage.Kind.Heading } >= 5)
            // Every tag and entity read away: none left in what the app shows.
            for (b in blocks) {
                assertFalse("$name: ${b.text}", Regex("[<>]|&[a-z#0-9]+;").containsMatchIn(b.text))
            }
        }
    }
}

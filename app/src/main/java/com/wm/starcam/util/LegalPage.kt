package com.wm.starcam.util

/**
 * The privacy policy and the terms of use as the app shows them: read from the very pages that
 * are published on the web (docs/legal, packed into the assets by app/build.gradle.kts), so the
 * two can never differ. Those pages keep to a small part of HTML for this: the blocks are h1, h2,
 * p and li, and inside a block only links and emphasis, whose tags are dropped.
 */
object LegalPage {
    enum class Kind { Title, Updated, Heading, Paragraph, Item }

    data class Block(val kind: Kind, val text: String)

    private val block = Regex("""<(h1|h2|p|li)(\s[^>]*)?>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val tag = Regex("<[^>]+>")
    private val space = Regex("\\s+")

    /** The blocks of the page's `<main>`, in order; the footer and the head are left out. */
    fun parse(html: String): List<Block> {
        val main = html.substringAfter("<main>").substringBefore("</main>")
        return block.findAll(main).map { m ->
            val (name, attrs, body) = m.destructured
            val kind = when (name) {
                "h1" -> Kind.Title
                "h2" -> Kind.Heading
                "li" -> Kind.Item
                else -> if ("updated" in attrs) Kind.Updated else Kind.Paragraph
            }
            Block(kind, text(body))
        }.toList()
    }

    private fun text(html: String): String =
        html.replace(tag, "").replace(space, " ").trim()
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&amp;", "&")
}

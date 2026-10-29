package com.getdieter.mobile.platform

import com.getdieter.core.files.syntaxRanges
import com.getdieter.core.presentation.Markdown
import com.getdieter.core.presentation.MarkdownBlock
import com.getdieter.core.presentation.MarkdownDiagrams
import com.getdieter.core.presentation.MarkdownInline
import com.getdieter.core.presentation.TableAlignment

/**
 * The core's Markdown tree as HTML for the web's native document view: the same profile Compose
 * renders (headings, paragraphs, lists with tasks, quotes, rules, highlighted code, GFM tables;
 * images are their alt text). Every text and attribute is escaped; links carry their target in
 * `data-link` and open through Kotlin, never through `href`.
 */
internal object MarkdownHtml {
    fun render(
        blocks: List<MarkdownBlock>,
        linkable: (String) -> Boolean,
        diagrams: Boolean,
    ): String = buildString { blocks.forEach { block(it, linkable, diagrams) } }

    private fun StringBuilder.block(
        block: MarkdownBlock,
        linkable: (String) -> Boolean,
        diagrams: Boolean,
    ) {
        when (block) {
            is MarkdownBlock.Paragraph -> {
                append("<p>")
                inlines(block.inlines, linkable)
                append("</p>")
            }
            is MarkdownBlock.Heading -> {
                val level = block.level.coerceIn(1, 6)
                append("<h").append(level).append('>')
                inlines(block.inlines, linkable)
                append("</h").append(level).append('>')
            }
            is MarkdownBlock.Code -> {
                val kind = if (diagrams) MarkdownDiagrams.kind(block.language) else null
                if (kind != null) {
                    append("<div class=\"diagram\" data-kind=\"").escape(kind).append("\">")
                    append("<pre class=\"source\"><code>").escape(block.text).append("</code></pre>")
                    append("</div>")
                } else code(block)
            }
            is MarkdownBlock.Quote -> {
                append("<blockquote>")
                block.blocks.forEach { block(it, linkable, diagrams) }
                append("</blockquote>")
            }
            is MarkdownBlock.ListBlock -> {
                if (block.ordered) {
                    append("<ol")
                    if (block.start != 1) append(" start=\"").append(block.start).append('"')
                    append('>')
                } else append("<ul>")
                block.items.forEach { item ->
                    if (item.checked != null) {
                        append("<li class=\"task\"><input type=\"checkbox\" disabled")
                        if (item.checked == true) append(" checked")
                        append(" aria-label=\"").append(if (item.checked == true) "Done" else "Open")
                        append("\">")
                    } else append("<li>")
                    item.blocks.forEach { block(it, linkable, diagrams) }
                    append("</li>")
                }
                append(if (block.ordered) "</ol>" else "</ul>")
            }
            MarkdownBlock.Rule -> append("<hr>")
            is MarkdownBlock.Table -> table(block, linkable)
        }
    }

    private fun StringBuilder.code(block: MarkdownBlock.Code) {
        append("<div class=\"code\">")
        if (block.language.isNotEmpty())
            append("<div class=\"lang\">").escape(block.language).append("</div>")
        append("<pre><code>")
        val text = block.text
        var at = 0
        for (range in syntaxRanges(text, Markdown.codeLanguage(block.language))) {
            val start = range.start.coerceIn(at, text.length)
            val end = range.end.coerceIn(start, text.length)
            if (start >= end) continue
            escape(text.substring(at, start))
            append("<span class=\"tok-").append(range.kind.name.lowercase()).append("\">")
            escape(text.substring(start, end))
            append("</span>")
            at = end
        }
        escape(text.substring(at))
        append("</code></pre></div>")
    }

    private fun StringBuilder.table(block: MarkdownBlock.Table, linkable: (String) -> Boolean) {
        fun align(column: Int) =
            when (block.alignments.getOrNull(column)) {
                TableAlignment.CENTER -> " style=\"text-align:center\""
                TableAlignment.END -> " style=\"text-align:right\""
                else -> ""
            }
        append("<div class=\"table\"><table><thead><tr>")
        block.header.forEachIndexed { column, cell ->
            append("<th").append(align(column)).append('>')
            inlines(Markdown.inline(cell), linkable)
            append("</th>")
        }
        append("</tr></thead><tbody>")
        block.rows.forEach { row ->
            append("<tr>")
            row.forEachIndexed { column, cell ->
                append("<td").append(align(column)).append('>')
                inlines(Markdown.inline(cell), linkable)
                append("</td>")
            }
            append("</tr>")
        }
        append("</tbody></table></div>")
    }

    private fun StringBuilder.inlines(inlines: List<MarkdownInline>, linkable: (String) -> Boolean) {
        for (inline in inlines) {
            when (inline) {
                is MarkdownInline.Text -> escape(inline.text)
                is MarkdownInline.Code -> append("<code>").escape(inline.text).append("</code>")
                is MarkdownInline.Emphasis -> wrap("em") { inlines(inline.children, linkable) }
                is MarkdownInline.Strong -> wrap("strong") { inlines(inline.children, linkable) }
                is MarkdownInline.Strikethrough -> wrap("del") { inlines(inline.children, linkable) }
                is MarkdownInline.Link ->
                    link(inline.url, linkable) { inlines(inline.children, linkable) }
                // Never loaded: the alt text (or the address) links to the image.
                is MarkdownInline.Image ->
                    link(inline.url, linkable) { escape(inline.alt.ifBlank { inline.url }) }
                // A soft break wraps like the space it is in CommonMark; a hard one breaks.
                is MarkdownInline.Break -> if (inline.hard) append("<br>") else append('\n')
            }
        }
    }

    private inline fun StringBuilder.wrap(tag: String, content: () -> Unit) {
        append('<').append(tag).append('>')
        content()
        append("</").append(tag).append('>')
    }

    private inline fun StringBuilder.link(
        url: String,
        linkable: (String) -> Boolean,
        content: () -> Unit,
    ) {
        if (!linkable(url)) {
            content()
            return
        }
        append("<a href=\"#\" data-link=\"").escape(url).append("\" title=\"").escape(url).append("\">")
        content()
        append("</a>")
    }

    private fun StringBuilder.escape(text: String): StringBuilder {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
        return this
    }
}

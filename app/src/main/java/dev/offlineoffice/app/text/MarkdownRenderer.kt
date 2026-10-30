// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.text

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** Colours for the preview page, taken from the app theme so preview matches light and dark mode. */
data class PreviewColors(
    val background: String,
    val text: String,
    val muted: String,
    val border: String,
    val codeBackground: String,
    val link: String,
    val dark: Boolean,
)

/**
 * Markdown → standalone HTML with commonmark-java and the GFM tables, strikethrough and task-list
 * extensions. The page carries a Content-Security-Policy that forbids every load except inline
 * styles and data: images; the WebView additionally blocks all requests and runs no JavaScript.
 */
object MarkdownRenderer {
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
    )
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).sanitizeUrls(true).build()

    fun bodyHtml(markdown: String): String = renderer.render(parser.parse(markdown))

    fun page(markdown: String, colors: PreviewColors): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>").append(css(colors)).append("</style></head><body>")
        append(bodyHtml(markdown))
        append("</body></html>")
    }

    private fun css(c: PreviewColors) = """
        :root { color-scheme: ${if (c.dark) "dark" else "light"}; }
        html { background: ${c.background}; }
        body { margin: 0; padding: 16px; color: ${c.text}; background: ${c.background};
          font: 16px/1.6 sans-serif; overflow-wrap: break-word; }
        h1, h2 { border-bottom: 1px solid ${c.border}; padding-bottom: .3em; }
        h1, h2, h3, h4, h5, h6 { line-height: 1.25; margin: 1.2em 0 .6em; }
        a { color: ${c.link}; }
        code, pre { font-family: monospace; font-size: 90%; background: ${c.codeBackground}; border-radius: 6px; }
        code { padding: .15em .35em; }
        pre { padding: 12px; overflow-x: auto; }
        pre code { padding: 0; background: none; }
        blockquote { margin: 0; padding: 0 1em; color: ${c.muted}; border-left: 4px solid ${c.border}; }
        table { border-collapse: collapse; display: block; overflow-x: auto; max-width: 100%; }
        th, td { border: 1px solid ${c.border}; padding: 6px 12px; }
        th { background: ${c.codeBackground}; }
        hr { border: 0; border-top: 1px solid ${c.border}; }
        img { max-width: 100%; }
        li:has(> input[type=checkbox]) { list-style: none; margin-left: -1.3em; }
        input[type=checkbox] { margin-right: .5em; }
    """.trimIndent()
}

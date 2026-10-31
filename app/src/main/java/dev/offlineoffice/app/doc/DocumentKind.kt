// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.doc

/** What engine opens a document. Detection prefers the file extension, then the MIME type. */
enum class DocumentKind(val engine: Engine, val extensions: Set<String>, val mimeTypes: Set<String>) {
    PDF(Engine.PDF, setOf("pdf"), setOf("application/pdf", "application/x-pdf")),
    WORD(
        Engine.OFFICE,
        setOf(
            "docx", "docm", "dotx", "doc", "dot", "odt", "ott", "fodt", "rtf",
            "pages", // import only (see IMPORT_ONLY)
        ),
        setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "application/vnd.ms-word.document.macroenabled.12",
            "application/msword",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "text/rtf",
            "application/vnd.oasis.opendocument.text-flat-xml",
            "application/vnd.apple.pages",
            "application/x-iwork-pages-sffpages",
        ),
    ),
    SPREADSHEET(
        Engine.OFFICE,
        setOf(
            "xlsx", "xlsm", "xltx", "xls", "xlt", "ods", "ots", "fods",
            "numbers",
        ),
        setOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "application/vnd.ms-excel.sheet.macroenabled.12",
            "application/vnd.ms-excel",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.spreadsheet-flat-xml",
            "application/vnd.apple.numbers",
            "application/x-iwork-numbers-sffnumbers",
        ),
    ),
    PRESENTATION(
        Engine.OFFICE,
        setOf("pptx", "pptm", "ppsx", "potx", "ppt", "pps", "odp", "otp", "fodp", "key"),
        setOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.openxmlformats-officedocument.presentationml.template",
            "application/vnd.ms-powerpoint.presentation.macroenabled.12",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.oasis.opendocument.presentation-flat-xml",
            "application/vnd.apple.keynote",
            "application/x-iwork-keynote-sffkey",
        ),
    ),
    MARKDOWN(Engine.TEXT, setOf("md", "markdown", "mdown", "mkd"), setOf("text/markdown", "text/x-markdown")),
    CSV(Engine.TEXT, setOf("csv", "tsv"), setOf("text/csv", "text/comma-separated-values", "text/tab-separated-values")),
    TEXT(
        Engine.TEXT,
        setOf(
            "txt", "text", "log", "nfo", "json", "xml", "yaml", "yml", "ini", "cfg", "conf", "properties", "toml", "env",
            "html", "htm", "xhtml", "css", "svg", "rst", "adoc", "org", "tex", "bib", "srt", "vtt", "sql",
            "js", "jsx", "ts", "tsx", "kt", "kts", "gradle", "java", "py", "rb", "php", "pl", "lua", "go", "rs", "swift",
            "c", "h", "cc", "cpp", "hpp", "cs", "sh", "bat", "ps1",
        ),
        setOf("text/plain", "application/json", "application/xml", "text/xml", "application/x-yaml", "text/html"),
    );

    enum class Engine { PDF, OFFICE, TEXT }

    companion object {
        /** MIME types offered to the system picker. Providers often label iWork files as
         *  zip or octet-stream; the file name then decides. */
        val pickerMimeTypes: Array<String> =
            // Keynote files are often typed as PGP keys (both use .key).
            (entries.flatMap { it.mimeTypes } + listOf("text/*", "application/zip", "application/octet-stream", "application/pgp-keys")).distinct().toTypedArray()

        /** Apple iWork: the Office engine can open these but not save them, so they open read-only. */
        val IMPORT_ONLY = setOf("pages", "numbers", "key")

        fun isImportOnly(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in IMPORT_ONLY

        fun detect(displayName: String?, mimeType: String?): DocumentKind? {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            entries.firstOrNull { ext in it.extensions }?.let { return it }
            val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
            entries.firstOrNull { mime in it.mimeTypes }?.let { return it }
            return if (mime.startsWith("text/")) TEXT else null
        }
    }
}

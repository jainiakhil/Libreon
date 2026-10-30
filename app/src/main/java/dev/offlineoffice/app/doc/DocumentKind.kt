// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.doc

/** What engine opens a document. Detection prefers the file extension, then the MIME type. */
enum class DocumentKind(val engine: Engine, val extensions: Set<String>, val mimeTypes: Set<String>) {
    PDF(Engine.PDF, setOf("pdf"), setOf("application/pdf", "application/x-pdf")),
    WORD(
        Engine.OFFICE,
        setOf("docx", "docm", "dotx", "doc", "dot", "odt", "ott", "rtf"),
        setOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "application/vnd.ms-word.document.macroenabled.12",
            "application/msword",
            "application/vnd.oasis.opendocument.text",
            "application/rtf",
            "text/rtf",
        ),
    ),
    SPREADSHEET(
        Engine.OFFICE,
        setOf("xlsx", "xlsm", "xltx", "xls", "xlt", "ods", "ots"),
        setOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "application/vnd.ms-excel.sheet.macroenabled.12",
            "application/vnd.ms-excel",
            "application/vnd.oasis.opendocument.spreadsheet",
        ),
    ),
    PRESENTATION(
        Engine.OFFICE,
        setOf("pptx", "pptm", "ppsx", "potx", "ppt", "pps", "odp", "otp"),
        setOf(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.openxmlformats-officedocument.presentationml.template",
            "application/vnd.ms-powerpoint.presentation.macroenabled.12",
            "application/vnd.ms-powerpoint",
            "application/vnd.oasis.opendocument.presentation",
        ),
    ),
    MARKDOWN(Engine.TEXT, setOf("md", "markdown", "mdown", "mkd"), setOf("text/markdown", "text/x-markdown")),
    CSV(Engine.TEXT, setOf("csv", "tsv"), setOf("text/csv", "text/comma-separated-values", "text/tab-separated-values")),
    TEXT(
        Engine.TEXT,
        setOf("txt", "text", "log", "json", "xml", "yaml", "yml", "ini", "cfg", "conf", "properties", "toml", "html", "htm", "css", "js", "kt", "java", "py", "sh"),
        setOf("text/plain", "application/json", "application/xml", "text/xml", "application/x-yaml", "text/html"),
    );

    enum class Engine { PDF, OFFICE, TEXT }

    companion object {
        /** MIME types offered to the system picker and declared in intent filters. */
        val pickerMimeTypes: Array<String> =
            (entries.flatMap { it.mimeTypes } + "text/*").distinct().toTypedArray()

        fun detect(displayName: String?, mimeType: String?): DocumentKind? {
            val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            entries.firstOrNull { ext in it.extensions }?.let { return it }
            val mime = mimeType?.lowercase()?.substringBefore(';')?.trim() ?: return null
            entries.firstOrNull { mime in it.mimeTypes }?.let { return it }
            return if (mime.startsWith("text/")) TEXT else null
        }
    }
}

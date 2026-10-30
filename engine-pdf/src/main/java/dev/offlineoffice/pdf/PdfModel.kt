// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.pdf

import org.json.JSONArray

/** A rectangle in display points (PDF points from the top-left of the page as shown). */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** How the document's interactive form is specified (PDFium FORMTYPE_*). */
enum class FormType { NONE, ACRO_FORM, XFA_FULL, XFA_FOREGROUND }

/** One choice of a combo or list box. */
data class FieldOption(val label: String, val selected: Boolean)

/** A form widget on a page. [annotIndex] identifies it within its page. */
data class FormField(
    val page: Int,
    val annotIndex: Int,
    val type: Type,
    val flags: Int,
    val name: String,
    val value: String,
    val checked: Boolean,
    val rect: PageRect,
    val options: List<FieldOption>,
) {
    /** In PDFium's FPDF_FORMFIELD_* order. */
    enum class Type { UNKNOWN, PUSH_BUTTON, CHECKBOX, RADIO, COMBO_BOX, LIST_BOX, TEXT, SIGNATURE }

    val readOnly: Boolean get() = flags and FLAG_READONLY != 0
    val multiline: Boolean get() = flags and FLAG_TEXT_MULTILINE != 0
    val password: Boolean get() = flags and FLAG_TEXT_PASSWORD != 0
    val editableChoice: Boolean get() = flags and FLAG_CHOICE_EDIT != 0
    val multiSelect: Boolean get() = flags and FLAG_CHOICE_MULTI_SELECT != 0

    companion object {
        const val FLAG_READONLY = 1
        const val FLAG_TEXT_MULTILINE = 1 shl 12
        const val FLAG_TEXT_PASSWORD = 1 shl 13
        const val FLAG_CHOICE_EDIT = 1 shl 18
        const val FLAG_CHOICE_MULTI_SELECT = 1 shl 21

        internal fun parse(page: Int, json: String): List<FormField> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val r = o.getJSONArray("rect")
                val opts = o.optJSONArray("options")
                FormField(
                    page = page,
                    annotIndex = o.getInt("i"),
                    type = Type.entries.getOrElse(o.getInt("type")) { Type.UNKNOWN },
                    flags = o.getInt("flags"),
                    name = o.getString("name"),
                    value = o.getString("value"),
                    checked = o.getBoolean("checked"),
                    rect = PageRect(
                        r.getDouble(0).toFloat(), r.getDouble(1).toFloat(),
                        r.getDouble(2).toFloat(), r.getDouble(3).toFloat(),
                    ),
                    options = if (opts == null) emptyList() else (0 until opts.length()).map { k ->
                        val opt = opts.getJSONObject(k)
                        FieldOption(opt.getString("label"), opt.getBoolean("selected"))
                    },
                )
            }
        }
    }
}

/** One search match: the rectangles covering its text (several when it wraps lines). */
data class SearchMatch(val page: Int, val rects: List<PageRect>)

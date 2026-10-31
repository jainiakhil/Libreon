// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep in sync with docs/licenses.md. */
private val notices = listOf(
    "LibreOffice core (LibreOfficeKit), Collabora Online front-end" to
        "MPL-2.0 (some files MPL-2.0 / LGPL-3.0+); source: libreoffice.org, collaboraonline.github.io",
    "LibreOffice bundled libraries (ICU, Boost, libxml2, HarfBuzz, cairo, NSS and others)" to
        "Unicode, BSL-1.0, MIT, MPL-1.1 / LGPL-2.1, MPL-2.0 (each library's own license)",
    "librevenge, libodfgen, libetonyek, libvisio" to "MPL-2.0 / LGPL-2.1+",
    "PDFium" to "BSD-3-Clause / Apache-2.0 (incl. FreeType FTL, libjpeg-turbo, OpenJPEG BSD-2, lcms2 MIT, zlib)",
    "pdfium-binaries packaging" to "MIT",
    "commonmark-java 0.24.0 (core, GFM tables, strikethrough, task list items)" to "BSD-2-Clause",
    "Kotlin, kotlinx.coroutines" to "Apache-2.0",
    "AndroidX (Compose, Material 3, Activity, Lifecycle, Room)" to "Apache-2.0",
)

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Offline Office is free software, licensed under the GNU General Public License v3.0 or later. " +
                        "It has no network access, no accounts and no analytics.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                notices.forEach { (name, license) ->
                    Text(name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    Text(license, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

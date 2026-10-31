// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.offlineoffice.app.doc.DocumentKind

/** Whether the current theme is dark; documents follow it (an Office page switches with it). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

val DocumentKind.icon: ImageVector
    get() = when (this) {
        DocumentKind.PDF -> Icons.Outlined.PictureAsPdf
        DocumentKind.WORD -> Icons.Outlined.Description
        DocumentKind.SPREADSHEET -> Icons.Outlined.TableChart
        DocumentKind.PRESENTATION -> Icons.Outlined.Slideshow
        DocumentKind.MARKDOWN -> Icons.AutoMirrored.Outlined.Article
        DocumentKind.CSV -> Icons.Outlined.GridOn
        DocumentKind.TEXT -> Icons.Outlined.TextSnippet
    }

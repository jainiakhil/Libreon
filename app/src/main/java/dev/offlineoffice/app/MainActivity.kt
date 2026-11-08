// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app

import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.offlineoffice.app.data.Settings
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.tabs.OpenOrigin
import dev.offlineoffice.app.tabs.SaveResult
import dev.offlineoffice.app.ui.AppRoot
import dev.offlineoffice.app.ui.AppTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app get() = application as OfficeApp

    private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { open(it, OpenOrigin.PICKER, null) }
    }

    private var pendingSaveAs: String? = null
    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val tabId = pendingSaveAs ?: return@registerForActivityResult
        pendingSaveAs = null
        if (uri != null) lifecycleScope.launch { reportSave(app.tabs.saveAs(tabId, uri)) }
    }

    /** A PDF exported to the cache, waiting for the user to pick where it goes. */
    private var pendingExport: File? = null
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val file = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
            }
            Toast.makeText(this@MainActivity, if (result.isSuccess) "PDF saved" else "Could not save the PDF", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The activity may be recreated (e.g. a window resize) while the save picker is open.
        pendingSaveAs = savedInstanceState?.getString(KEY_PENDING_SAVE_AS)
        setContent {
            val mode by app.settings.theme.collectAsState()
            val lightTabs by app.settings.lightTabs.collectAsState()
            val dark = when (mode) {
                Settings.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                Settings.ThemeMode.LIGHT -> false
                Settings.ThemeMode.DARK -> true
            }
            // System bar icons follow the app theme, which may differ from the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            app.tabs.darkPagesFor = { id -> dark && id !in lightTabs }
            AppTheme(dark) {
                AppRoot(
                    tabs = app.tabs,
                    settings = app.settings,
                    onOpen = { openDocuments.launch(DocumentKind.pickerMimeTypes) },
                    onSaveAs = ::launchSaveAs,
                    onShare = ::share,
                    onExportPdf = ::exportPdf,
                    onMessage = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() },
                )
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingSaveAs?.let { outState.putString(KEY_PENDING_SAVE_AS, it) }
    }

    override fun onStop() {
        super.onStop()
        app.tabs.persistAll()
    }

    private fun launchSaveAs(tabId: String, suggestedName: String) {
        pendingSaveAs = tabId
        createDocument.launch(suggestedName)
    }

    /**
     * Sends the document, with any unsaved edits (as a PDF with [asPdf]), to another app.
     * The copy lives in the cache until the next share; the original is not changed.
     */
    private fun share(tabId: String, displayName: String, asPdf: Boolean) {
        lifecycleScope.launch {
            val name = if (asPdf) displayName.substringBeforeLast('.') + ".pdf" else displayName
            val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, name)
            runCatching { app.tabs.exportCopy(tabId, file, asPdf) }.onFailure {
                Toast.makeText(this@MainActivity, "Could not share: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val type = if (asPdf) "application/pdf" else MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            val send = Intent(Intent.ACTION_SEND).apply {
                setType(type)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, null))
        }
    }

    /** Exports an Office document as a PDF, then asks where to save it. */
    private fun exportPdf(tabId: String, displayName: String) {
        lifecycleScope.launch {
            val dir = File(cacheDir, "export").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, "export.pdf")
            runCatching { app.tabs.exportCopy(tabId, file, asPdf = true) }.onFailure {
                Toast.makeText(this@MainActivity, "Could not export: ${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingExport = file
            createPdf.launch(displayName.substringBeforeLast('.') + ".pdf")
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        uris.filter { it.scheme == "content" }.forEach { open(it, OpenOrigin.INTENT, intent.type) }
    }

    private fun open(uri: Uri, origin: OpenOrigin, mimeHint: String?) {
        lifecycleScope.launch {
            app.ready.await()
            runCatching { app.tabs.open(uri, origin, mimeHint) }
                .onFailure { Toast.makeText(this@MainActivity, it.message ?: "Cannot open file", Toast.LENGTH_LONG).show() }
        }
    }

    private fun reportSave(result: SaveResult) {
        val text = when (result) {
            SaveResult.Saved -> "Saved"
            is SaveResult.Failed -> "Save failed: ${result.message}"
            else -> return
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val KEY_PENDING_SAVE_AS = "pendingSaveAs"
    }
}

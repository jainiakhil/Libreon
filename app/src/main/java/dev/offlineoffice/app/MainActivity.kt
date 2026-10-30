// SPDX-License-Identifier: GPL-3.0-or-later
package dev.offlineoffice.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dev.offlineoffice.app.doc.DocumentKind
import dev.offlineoffice.app.tabs.OpenOrigin
import dev.offlineoffice.app.tabs.SaveResult
import dev.offlineoffice.app.ui.AppRoot
import dev.offlineoffice.app.ui.AppTheme
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppRoot(
                    tabs = app.tabs,
                    onOpen = { openDocuments.launch(DocumentKind.pickerMimeTypes) },
                    onSaveAs = ::launchSaveAs,
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

    override fun onStop() {
        super.onStop()
        app.tabs.persistAll()
    }

    private fun launchSaveAs(tabId: String, suggestedName: String) {
        pendingSaveAs = tabId
        createDocument.launch(suggestedName)
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
}

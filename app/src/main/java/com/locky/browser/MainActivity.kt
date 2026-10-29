package com.locky.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File

class MainActivity : FragmentActivity() {
    private lateinit var settings: Settings
    private lateinit var downloads: DownloadManager
    private lateinit var vault: PasswordVault
    private lateinit var state: BrowserState

    private var screen by mutableStateOf(Screen.BROWSER)
    private var locked by mutableStateOf(false)
    private var authenticating = false
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        settings = Settings(this)
        vault = PasswordVault(this)
        val dir = getExternalFilesDir("Download") ?: File(filesDir, "Download")
        downloads = DownloadManager(dir, lifecycleScope)
        state = BrowserState(this, settings, AdBlocker(this), downloads)
        state.fileChooser = { callback, intent ->
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = callback
            try {
                picker.launch(intent)
                true
            } catch (e: Exception) {
                pendingFileCallback = null
                callback.onReceiveValue(null)
                false
            }
        }

        locked = settings.appLock.value
        openLinkFrom(intent, newTab = false)

        setContent {
            LockyTheme {
                val secure = locked || screen == Screen.PASSWORDS || state.current.isPrivate
                LaunchedEffect(secure) {
                    // Keeps private tabs, saved passwords and the lock screen out of screenshots and the recents view.
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }

                BackHandler {
                    when {
                        locked -> moveTaskToBack(true)
                        screen != Screen.BROWSER -> screen = Screen.BROWSER
                        state.canStepBack(state.current) -> state.goBack(state.current)
                        state.tabs.size > 1 -> state.closeTab(state.current)
                        else -> moveTaskToBack(true)
                    }
                }

                if (locked) {
                    LockScreen(onUnlock = { requestUnlock() })
                } else {
                    when (screen) {
                        Screen.BROWSER -> BrowserScreen(
                            state = state,
                            settings = settings,
                            vault = vault,
                            open = { screen = it },
                        )
                        Screen.TABS -> TabsScreen(state, onClose = { screen = Screen.BROWSER })
                        Screen.DOWNLOADS -> DownloadsScreen(downloads, onBack = { screen = Screen.BROWSER }, openFile = ::openDownloaded)
                        Screen.PASSWORDS -> PasswordsScreen(
                            vault = vault,
                            currentUrl = state.current.url,
                            onBack = { screen = Screen.BROWSER },
                            onFill = { entry ->
                                state.fillCredentials(state.current, entry.user, entry.password)
                                screen = Screen.BROWSER
                            },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            onBack = { screen = Screen.BROWSER },
                            onChanged = { state.refreshSettings() },
                            onClearData = { state.clearBrowsingData() },
                            onEnableLock = ::enableAppLock,
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ lock

    override fun onStart() {
        super.onStart()
        if (locked) requestUnlock()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
        if (settings.appLock.value && !authenticating) locked = true
    }

    private fun requestUnlock() {
        if (authenticating) return
        authenticating = true
        AppLock.authenticate(this, "Unlock Locky") { result ->
            authenticating = false
            // If the phone has no screen lock set up there is nothing to check against, so don't trap the user.
            if (result == AuthResult.SUCCESS || result == AuthResult.UNAVAILABLE) locked = false
        }
    }

    private fun enableAppLock(onDone: (Boolean) -> Unit) {
        authenticating = true
        AppLock.authenticate(this, "Turn on Locky app lock") { result ->
            authenticating = false
            onDone(result == AuthResult.SUCCESS)
        }
    }

    // ----------------------------------------------------------------- links

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLinkFrom(intent, newTab = true)
        screen = Screen.BROWSER
    }

    private fun openLinkFrom(intent: Intent?, newTab: Boolean) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        if (newTab) state.newTab(uri.toString()) else state.navigate(state.current, uri.toString())
    }

    private fun openDownloaded(item: DownloadItem) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", item.file)
            val ext = item.file.extension.lowercase()
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            state.current.notice = "No app found to open this file"
            state.current.noticeUrl = null
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        state.tabs.forEach { it.webView.onResume() }
    }

    override fun onPause() {
        state.tabs.forEach { it.webView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) state.destroyAll()
        super.onDestroy()
    }
}

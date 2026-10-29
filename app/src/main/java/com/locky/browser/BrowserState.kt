package com.locky.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.net.http.SslError
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

class BrowserTab(val id: Int, val isPrivate: Boolean) {
    lateinit var webView: WebView
    var title by mutableStateOf("New tab")
    var url by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var blockedCount by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
    var warning by mutableStateOf<String?>(null)
    var pendingUrl: String? = null
    val allowedHosts = HashSet<String>()

    /** Short banner such as "Redirect blocked"; noticeUrl (if set) lets the user allow it. */
    var notice by mutableStateOf<String?>(null)
    var noticeUrl: String? = null
}

class BrowserState(
    private val activity: Activity,
    private val settings: Settings,
    private val adBlocker: AdBlocker,
    private val downloads: DownloadManager,
) {
    val tabs = mutableStateListOf<BrowserTab>()
    private var currentId by mutableIntStateOf(-1)
    private var nextId = 1
    private val main = Handler(Looper.getMainLooper())

    /** Set by the activity: launches the system file picker for <input type=file>. */
    var fileChooser: ((ValueCallback<Array<Uri>>, Intent) -> Boolean)? = null

    val current: BrowserTab
        get() = tabs.firstOrNull { it.id == currentId } ?: tabs.first()

    init {
        newTab()
    }

    // ---------------------------------------------------------------- tabs

    fun newTab(url: String? = null, isPrivate: Boolean = false, select: Boolean = true): BrowserTab {
        val tab = BrowserTab(nextId++, isPrivate)
        tab.webView = buildWebView(tab)
        applySettings(tab)
        tabs.add(tab)
        if (select) currentId = tab.id
        if (!url.isNullOrBlank()) navigate(tab, url)
        return tab
    }

    fun select(tab: BrowserTab) {
        currentId = tab.id
    }

    fun closeTab(tab: BrowserTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasCurrent = tab.id == currentId
        tabs.remove(tab)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        if (tab.isPrivate) {
            tab.webView.clearCache(true)
            tab.webView.clearHistory()
            tab.webView.clearFormData()
        }
        tab.webView.destroy()
        if (tabs.isEmpty()) {
            newTab()
        } else if (wasCurrent) {
            currentId = tabs[minOf(idx, tabs.size - 1)].id
        }
    }

    fun destroyAll() {
        tabs.forEach { it.webView.destroy() }
    }

    // ----------------------------------------------------------- navigation

    fun navigate(tab: BrowserTab, input: String) {
        val url = normalize(input)
        tab.error = null
        tab.warning = null
        tab.notice = null
        UrlSafety.check(url)?.let { reason ->
            val host = Uri.parse(url).host.orEmpty()
            if (host !in tab.allowedHosts) {
                tab.url = url
                tab.warning = reason
                tab.pendingUrl = url
                return
            }
        }
        tab.webView.loadUrl(url)
    }

    private fun normalize(input: String): String {
        val t = input.trim()
        val lower = t.lowercase()
        val blockedSchemes = listOf("javascript:", "file:", "content:", "data:", "intent:")
        if (blockedSchemes.any { lower.startsWith(it) }) return searchUrl(t)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return upgrade(t)
        val looksLikeHost = !t.contains(' ') && (t.contains('.') || lower.startsWith("localhost"))
        return if (looksLikeHost) upgrade("http://$t") else searchUrl(t)
    }

    private fun searchUrl(q: String) = "https://duckduckgo.com/?q=" + Uri.encode(q)

    private fun upgrade(url: String): String {
        if (!settings.httpsOnly.value || !url.startsWith("http://", ignoreCase = true)) return url
        val host = Uri.parse(url).host.orEmpty()
        val local = host == "localhost" || host.endsWith(".local") || Regex("""^(10|127|192\.168)\.[\d.]+$""").matches(host)
        return if (local) url else "https://" + url.substring(7)
    }

    fun proceedAnyway(tab: BrowserTab) {
        val url = tab.pendingUrl ?: return
        Uri.parse(url).host?.let { tab.allowedHosts.add(it) }
        tab.warning = null
        tab.pendingUrl = null
        tab.webView.loadUrl(url)
    }

    fun dismissWarning(tab: BrowserTab) {
        tab.warning = null
        tab.pendingUrl = null
        if (tab.webView.canGoBack()) {
            tab.webView.goBack()
        } else {
            tab.url = ""
            tab.title = "New tab"
        }
    }

    fun allowNotice(tab: BrowserTab) {
        val u = tab.noticeUrl
        tab.notice = null
        tab.noticeUrl = null
        if (u != null) tab.webView.loadUrl(u)
    }

    fun goBack(tab: BrowserTab) {
        if (tab.error != null) tab.error = null
        if (tab.webView.canGoBack()) tab.webView.goBack() else if (tab.url.isNotBlank()) {
            tab.url = ""
            tab.title = "New tab"
        }
    }

    /** True if "back" would do something inside this tab. */
    fun canStepBack(tab: BrowserTab) = tab.canGoBack || tab.url.isNotBlank()

    // ------------------------------------------------------------- settings

    fun refreshSettings() {
        tabs.forEach { applySettings(it) }
    }

    private fun applySettings(tab: BrowserTab) {
        val s = tab.webView.settings
        val saver = settings.dataSaver.value
        s.blockNetworkImage = saver
        s.loadsImagesAutomatically = !saver
        s.cacheMode = if (tab.isPrivate) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, !settings.blockThirdPartyCookies.value)
    }

    fun clearBrowsingData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.forEach {
            it.webView.clearCache(true)
            it.webView.clearHistory()
            it.webView.clearFormData()
            it.canGoBack = false
            it.canGoForward = false
        }
    }

    // ------------------------------------------------------------- passwords

    /** Fills the first password field (and the text/email field before it) on the current page. */
    fun fillCredentials(tab: BrowserTab, user: String, password: String) {
        val js = "(function(u,p){var pw=document.querySelector('input[type=password]');if(!pw)return;" +
            "var inputs=Array.prototype.slice.call(document.querySelectorAll('input'));var i=inputs.indexOf(pw);var un=null;" +
            "for(var j=i-1;j>=0;j--){var t=(inputs[j].type||'text').toLowerCase();if(t=='text'||t=='email'||t=='tel'){un=inputs[j];break;}}" +
            "function set(el,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(el,v);" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "if(un)set(un,u);set(pw,p);})(" + JSONObject.quote(user) + "," + JSONObject.quote(password) + ");"
        tab.webView.evaluateJavascript(js, null)
    }

    // -------------------------------------------------------------- webview

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun buildWebView(tab: BrowserTab): WebView {
        val wv = WebView(activity)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // Hardening: no local file access, no insecure mixed content, no JS bridges are ever added.
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.saveFormData = false
        s.savePassword = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true
        // Popups: pages may ask for windows; onCreateWindow decides.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                val saver = settings.dataSaver.value
                if (adBlocker.isBlocked(uri, settings.adBlock.value, saver || settings.adBlock.value)) {
                    main.post { tab.blockedCount++ }
                    return AdBlocker.emptyResponse()
                }
                if (saver && AdBlocker.isHeavyResource(uri)) return AdBlocker.emptyResponse()
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme?.lowercase()

                if (scheme != "http" && scheme != "https") {
                    // Never let a page silently launch other apps (intent:, market:, custom schemes).
                    if (request.hasGesture() && (scheme == "mailto" || scheme == "tel")) {
                        try {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {
                        }
                    }
                    return true
                }

                val url = uri.toString()
                if (settings.httpsOnly.value && scheme == "http") {
                    val up = upgrade(url)
                    if (up != url) {
                        view.loadUrl(up)
                        return true
                    }
                }

                // Redirect guard: a page that navigates the tab to another site without a user tap.
                // (Normal server redirects such as http->https or www are marked isRedirect and allowed.)
                if (settings.redirectGuard.value && !request.hasGesture() && !request.isRedirect && tab.url.isNotBlank()) {
                    val from = UrlSafety.siteOf(Uri.parse(tab.url).host)
                    val to = UrlSafety.siteOf(uri.host)
                    if (from != to) {
                        tab.blockedCount++
                        tab.notice = "Blocked automatic redirect to $to"
                        tab.noticeUrl = url
                        return true
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                tab.url = url
                tab.loading = true
                tab.error = null
                if (settings.phishingWarning.value) {
                    val host = Uri.parse(url).host.orEmpty()
                    val reason = UrlSafety.check(url)
                    if (reason != null && host !in tab.allowedHosts) {
                        view.stopLoading()
                        tab.warning = reason
                        tab.pendingUrl = url
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                tab.loading = false
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
                if (settings.adBlock.value) view.evaluateJavascript(AdBlocker.COSMETIC_JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (tab.warning == null) tab.url = url
                tab.canGoBack = view.canGoBack()
                tab.canGoForward = view.canGoForward()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) tab.error = error.description?.toString() ?: "Page could not be loaded"
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel() // never continue past certificate errors
                tab.error = "This site's security certificate is not trusted, so Locky did not load it."
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (settings.popupBlock.value && !isUserGesture) {
                    tab.blockedCount++
                    tab.notice = "Blocked a popup"
                    tab.noticeUrl = null
                    return false
                }
                val newTab = newTab(isPrivate = tab.isPrivate)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                val launcher = fileChooser ?: return false
                return try {
                    launcher(callback, params.createIntent())
                } catch (e: Exception) {
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val scheme = Uri.parse(url).scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                downloads.enqueue(url, userAgent, contentDisposition, mimetype, CookieManager.getInstance().getCookie(url))
                tab.notice = "Download started"
                tab.noticeUrl = null
            } else {
                tab.notice = "This kind of download is not supported"
                tab.noticeUrl = null
            }
        }
        return wv
    }
}

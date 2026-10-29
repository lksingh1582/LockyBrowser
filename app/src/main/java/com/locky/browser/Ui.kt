package com.locky.browser

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

enum class Screen { BROWSER, TABS, DOWNLOADS, PASSWORDS, SETTINGS }

private val Blue = Color(0xFF1A56DB)
private val PrivatePurple = Color(0xFF3B2A5C)

@Composable
fun LockyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8AB4F8)) else lightColorScheme(primary = Blue),
        content = content,
    )
}

// ================================================================== lock

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🔒", fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text("Locky is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}

// =============================================================== browser

@Composable
fun BrowserScreen(state: BrowserState, settings: Settings, vault: PasswordVault, open: (Screen) -> Unit) {
    val tab = state.current
    var menuOpen by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
    ) {
        AddressBar(state, tab)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab.warning != null -> WarningView(
                    reason = tab.warning!!,
                    onBack = { state.dismissWarning(tab) },
                    onContinue = { state.proceedAnyway(tab) },
                )
                tab.url.isBlank() -> StartPage(state, tab, settings)
                else -> key(tab.id) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = {
                            tab.webView.also { (it.parent as? ViewGroup)?.removeView(it) }
                        },
                    )
                }
            }

            tab.error?.let { msg ->
                if (tab.warning == null) ErrorView(msg, onRetry = {
                    tab.error = null
                    tab.webView.reload()
                })
            }

            NoticeBar(tab, state, Modifier.align(Alignment.BottomCenter))
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton("◀", enabled = state.canStepBack(tab)) { state.goBack(tab) }
            BarButton("▶", enabled = tab.canGoForward) { tab.webView.goForward() }
            BarButton(if (tab.loading) "✕" else "⟳", enabled = tab.url.isNotBlank()) {
                if (tab.loading) tab.webView.stopLoading() else tab.webView.reload()
            }
            BarButton("▢ ${state.tabs.size}") { open(Screen.TABS) }
            Box {
                BarButton("⋮") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuItem("New tab") { menuOpen = false; state.newTab() }
                    MenuItem("New private tab") { menuOpen = false; state.newTab(isPrivate = true) }
                    MenuItem(if (settings.dataSaver.value) "Data saver: ON" else "Data saver: OFF") {
                        menuOpen = false
                        settings.dataSaver.set(!settings.dataSaver.value)
                        state.refreshSettings()
                        if (tab.url.isNotBlank()) tab.webView.reload()
                    }
                    HorizontalDivider()
                    MenuItem("Downloads") { menuOpen = false; open(Screen.DOWNLOADS) }
                    MenuItem("Passwords") { menuOpen = false; open(Screen.PASSWORDS) }
                    MenuItem("Save login for this site") {
                        menuOpen = false
                        if (tab.url.isNotBlank()) saveDialog = true
                    }
                    HorizontalDivider()
                    MenuItem("Settings") { menuOpen = false; open(Screen.SETTINGS) }
                }
            }
        }
    }

    if (saveDialog) {
        val host = Uri.parse(tab.url).host.orEmpty()
        SaveLoginDialog(
            site = host,
            onDismiss = { saveDialog = false },
            onSave = { site, user, pass ->
                vault.save(site, user, pass)
                saveDialog = false
                tab.notice = "Login saved (encrypted)"
                tab.noticeUrl = null
            },
        )
    }
}

@Composable
private fun BarButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) { Text(label, fontSize = 18.sp) }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}

@Composable
private fun AddressBar(state: BrowserState, tab: BrowserTab) {
    var text by remember(tab.id, tab.url) { mutableStateOf(tab.url) }
    val bg = if (tab.isPrivate) PrivatePurple else MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().background(if (tab.isPrivate) PrivatePurple else MaterialTheme.colorScheme.background)) {
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(28.dp)),
            placeholder = { Text(if (tab.isPrivate) "Private tab - search or type address" else "Search or type address") },
            leadingIcon = {
                Text(
                    when {
                        tab.isPrivate -> "🕶"
                        tab.url.startsWith("https://") -> "🔒"
                        tab.url.isBlank() -> "🔍"
                        else -> "⚠"
                    },
                    modifier = Modifier.padding(start = 4.dp),
                )
            },
            trailingIcon = {
                if (tab.blockedCount > 0) {
                    Text("🛡 ${tab.blockedCount}", fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) state.navigate(tab, text) }),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = bg,
                unfocusedContainerColor = bg,
                focusedTextColor = if (tab.isPrivate) Color.White else Color.Unspecified,
                unfocusedTextColor = if (tab.isPrivate) Color.White else Color.Unspecified,
            ),
        )
        if (tab.loading) {
            LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun StartPage(state: BrowserState, tab: BrowserTab, settings: Settings) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (tab.isPrivate) "🕶" else "🔒", fontSize = 56.sp)
        Text("Locky", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            if (tab.isPrivate) "Private tab: no history or cache is kept." else "Fast. Quiet. Safe.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            listOf(
                "Ads" to settings.adBlock.value,
                "Popups" to settings.popupBlock.value,
                "Redirects" to settings.redirectGuard.value,
                "Data saver" to settings.dataSaver.value,
            ).joinToString("   ") { (n, on) -> (if (on) "✓ " else "✗ ") + n },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Wikipedia" to "wikipedia.org", "GitHub" to "github.com", "DuckDuckGo" to "duckduckgo.com").forEach { (name, url) ->
                OutlinedButton(onClick = { state.navigate(tab, url) }) { Text(name, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun WarningView(reason: String, onBack: () -> Unit, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⚠", fontSize = 52.sp)
        Spacer(Modifier.height(8.dp))
        Text("This site may not be safe", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(reason, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Go back to safety") }
        TextButton(onClick = onContinue) { Text("Continue anyway", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't open this page", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun NoticeBar(tab: BrowserTab, state: BrowserState, modifier: Modifier) {
    val notice = tab.notice ?: return
    LaunchedEffect(notice) {
        delay(6000)
        if (tab.notice == notice) tab.notice = null
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tab.noticeUrl != null) {
                TextButton(onClick = { state.allowNotice(tab) }) { Text("Allow") }
            }
            TextButton(onClick = { tab.notice = null; tab.noticeUrl = null }) { Text("✕") }
        }
    }
}

@Composable
private fun SaveLoginDialog(site: String, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var siteText by remember { mutableStateOf(site) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(siteText, { siteText = it }, label = { Text("Site") }, singleLine = true)
                OutlinedTextField(user, { user = it }, label = { Text("Username / email") }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                TextButton(onClick = { pass = PasswordVault.generatePassword() }) { Text("Generate strong password") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = siteText.isNotBlank() && pass.isNotEmpty(),
                onClick = { onSave(siteText.trim(), user.trim(), pass) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =================================================================== tabs

@Composable
private fun ScreenFrame(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            actions()
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
fun TabsScreen(state: BrowserState, onClose: () -> Unit) {
    ScreenFrame(
        title = "Tabs (${state.tabs.size})",
        onBack = onClose,
        actions = {
            TextButton(onClick = { state.newTab(); onClose() }) { Text("+ Tab") }
            TextButton(onClick = { state.newTab(isPrivate = true); onClose() }) { Text("+ Private") }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.tabs.toList(), key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { state.select(t); onClose() }) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (t.isPrivate) "🕶 " else "") + t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(t.url.ifBlank { "Start page" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { state.closeTab(t) }) { Text("✕") }
                    }
                }
            }
        }
    }
}

// ============================================================= downloads

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

@Composable
fun DownloadsScreen(downloads: DownloadManager, onBack: () -> Unit, openFile: (DownloadItem) -> Unit) {
    ScreenFrame("Downloads", onBack) {
        if (downloads.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No downloads yet") }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(downloads.items.toList(), key = { it.id }) { d ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            if (d.state != DlState.DONE) {
                                if (d.total > 0) {
                                    LinearProgressIndicator(progress = { (d.downloaded.toFloat() / d.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                } else if (d.state == DlState.RUNNING) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            Text(
                                when (d.state) {
                                    DlState.RUNNING -> "${formatSize(d.downloaded)} / ${formatSize(d.total)}"
                                    DlState.PAUSED -> "Paused - ${formatSize(d.downloaded)} / ${formatSize(d.total)}"
                                    DlState.DONE -> "Done - ${formatSize(d.total.coerceAtLeast(d.downloaded))}"
                                    DlState.FAILED -> "Failed: ${d.error ?: "unknown error"}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Row {
                                when (d.state) {
                                    DlState.RUNNING -> TextButton(onClick = { downloads.pause(d) }) { Text("Pause") }
                                    DlState.PAUSED -> TextButton(onClick = { downloads.start(d) }) { Text("Resume") }
                                    DlState.FAILED -> TextButton(onClick = { downloads.start(d) }) { Text("Retry") }
                                    DlState.DONE -> TextButton(onClick = { openFile(d) }) { Text("Open") }
                                }
                                TextButton(onClick = { downloads.remove(d) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================== passwords

@Composable
fun PasswordsScreen(vault: PasswordVault, currentUrl: String, onBack: () -> Unit, onFill: (PasswordVault.Entry) -> Unit) {
    var entries by remember { mutableStateOf(vault.list()) }
    var adding by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<String?>(null) }
    val currentSite = remember(currentUrl) { UrlSafety.siteOf(Uri.parse(currentUrl).host) }
    val sorted = remember(entries, currentSite) {
        entries.sortedByDescending { UrlSafety.siteOf(it.site.removePrefix("https://").removePrefix("http://").substringBefore('/')) == currentSite }
    }

    ScreenFrame("Passwords", onBack, actions = { TextButton(onClick = { adding = true }) { Text("+ Add") } }) {
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No saved logins.\nPasswords are encrypted with a key kept in the Android Keystore.", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sorted, key = { it.id }) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(e.site, style = MaterialTheme.typography.titleSmall)
                            Text(e.user.ifBlank { "(no username)" }, style = MaterialTheme.typography.bodyMedium)
                            Text(if (revealed == e.id) e.password else "••••••••••", style = MaterialTheme.typography.bodyMedium)
                            Row {
                                TextButton(onClick = { onFill(e) }) { Text("Fill on page") }
                                TextButton(onClick = { revealed = if (revealed == e.id) null else e.id }) {
                                    Text(if (revealed == e.id) "Hide" else "Show")
                                }
                                TextButton(onClick = { vault.delete(e.id); entries = vault.list() }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        SaveLoginDialog(
            site = if (currentSite.isNotBlank()) currentSite else "",
            onDismiss = { adding = false },
            onSave = { s, u, p ->
                vault.save(s, u, p)
                entries = vault.list()
                adding = false
            },
        )
    }
}

// =============================================================== settings

@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onClearData: () -> Unit,
    onEnableLock: ((Boolean) -> Unit) -> Unit,
) {
    var cleared by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    ScreenFrame("Settings", onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Header("Blocking") }
            item { Toggle("Ad & tracker blocking", "Blocks known ad and tracker servers and hides empty ad spaces.", settings.adBlock, onChanged) }
            item { Toggle("Block popups", "Blocks pop-up tabs unless you tap a link on the same site. Some login pop-ups may need this off.", settings.popupBlock, onChanged) }
            item { Toggle("Stop auto-redirects", "Blocks pages that send you to another site without a tap. You can allow a blocked one.", settings.redirectGuard, onChanged) }
            item { Header("Data") }
            item { Toggle("Data saver", "Does not load images and blocks heavy media, saving mobile data.", settings.dataSaver, onChanged) }
            item { Header("Security") }
            item { Toggle("HTTPS only", "Upgrades http:// links to https://.", settings.httpsOnly, onChanged) }
            item { Toggle("Block third-party cookies", "Limits cross-site tracking.", settings.blockThirdPartyCookies, onChanged) }
            item { Toggle("Dangerous site warning", "Warns about look-alike and suspicious addresses.", settings.phishingWarning, onChanged) }
            item {
                val lock = settings.appLock
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App lock", style = MaterialTheme.typography.bodyLarge)
                        Text("Ask for fingerprint, face or screen lock when Locky opens.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = lock.value, onCheckedChange = { on ->
                        if (on) onEnableLock { ok -> if (ok) lock.set(true) } else lock.set(false)
                    })
                }
            }
            item { Header("Privacy") }
            item {
                Column(Modifier.padding(16.dp)) {
                    OutlinedButton(onClick = { onClearData(); cleared = true }) { Text("Clear cookies, cache & history") }
                    if (cleared) Text("Cleared.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(20.dp))
                    Text("Locky 1.0", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, desc: String, pref: BoolPref, onChanged: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = pref.value, onCheckedChange = { pref.set(it); onChanged() })
    }
}

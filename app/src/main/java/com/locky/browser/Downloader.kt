package com.locky.browser

import android.webkit.URLUtil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

enum class DlState { RUNNING, PAUSED, DONE, FAILED }

class DownloadItem(val id: Int, val url: String, val file: File, val userAgent: String?, val cookie: String?) {
    var total by mutableLongStateOf(-1L)
    var downloaded by mutableLongStateOf(0L)
    var state by mutableStateOf(DlState.RUNNING)
    var error by mutableStateOf<String?>(null)

    internal val counter = AtomicLong(0)
    internal var probed = false
    internal var rangeOk = false
    internal var finalUrl: String = url
    internal var starts: LongArray? = null
    internal var ends: LongArray? = null
    internal var pos: LongArray? = null
    internal var job: Job? = null

    val name: String get() = file.name
}

/**
 * Multi-connection downloader: when the server supports HTTP Range requests the file is split into
 * several parts that download in parallel. Pause/resume keeps each part's position in memory.
 */
class DownloadManager(private val dir: File, private val scope: CoroutineScope) {
    val items = mutableStateListOf<DownloadItem>()
    private var nextId = 1

    fun enqueue(url: String, userAgent: String?, contentDisposition: String?, mime: String?, cookie: String?): DownloadItem {
        dir.mkdirs()
        val name = URLUtil.guessFileName(url, contentDisposition, mime).replace('/', '_')
        val item = DownloadItem(nextId++, url, uniqueFile(name), userAgent, cookie)
        items.add(0, item)
        start(item)
        return item
    }

    fun start(item: DownloadItem) {
        item.state = DlState.RUNNING
        item.error = null
        val old = item.job
        item.job = scope.launch(Dispatchers.IO) {
            old?.cancelAndJoin()
            runDownload(item)
        }
    }

    fun pause(item: DownloadItem) {
        if (item.state != DlState.RUNNING) return
        item.state = DlState.PAUSED
        item.job?.cancel()
    }

    fun remove(item: DownloadItem) {
        item.job?.cancel()
        items.remove(item)
        scope.launch(Dispatchers.IO) { item.job?.join(); item.file.delete() }
    }

    private fun uniqueFile(name: String): File {
        var f = File(dir, name)
        if (!f.exists() && items.none { it.file == f }) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            f = File(dir, "$base ($n)$ext")
            if (!f.exists() && items.none { it.file == f }) return f
            n++
        }
    }

    private suspend fun runDownload(item: DownloadItem) {
        try {
            if (!item.probed) probe(item)
            if (item.rangeOk && item.total > 0) {
                val parts = if (item.total >= MIN_SPLIT) PARTS else 1
                if (item.starts == null) {
                    val chunk = item.total / parts
                    item.starts = LongArray(parts) { it * chunk }
                    item.ends = LongArray(parts) { if (it == parts - 1) item.total - 1 else (it + 1) * chunk - 1 }
                    item.pos = item.starts!!.copyOf()
                    RandomAccessFile(item.file, "rw").use { it.setLength(item.total) }
                }
                val done = item.pos!!.indices.sumOf { item.pos!![it] - item.starts!![it] }
                item.counter.set(done)
                item.downloaded = done
                coroutineScope {
                    for (i in 0 until parts) launch { downloadPart(item, i) }
                }
            } else {
                singleStream(item)
            }
            item.state = DlState.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            item.state = DlState.FAILED
            item.error = e.message ?: "Download failed"
        }
    }

    private suspend fun downloadPart(item: DownloadItem, i: Int) {
        val pos = item.pos!!
        val ends = item.ends!!
        var stalls = 0
        while (pos[i] <= ends[i]) {
            val before = pos[i]
            val conn = open(item, item.finalUrl, "bytes=${pos[i]}-${ends[i]}")
            try {
                if (conn.responseCode != 206) throw IOException("Server ignored range request (HTTP ${conn.responseCode})")
                RandomAccessFile(item.file, "rw").use { raf ->
                    raf.seek(pos[i])
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val r = input.read(buf)
                            if (r < 0) break
                            val n = minOf(r.toLong(), ends[i] - pos[i] + 1).toInt()
                            raf.write(buf, 0, n)
                            pos[i] += n
                            item.downloaded = item.counter.addAndGet(n.toLong())
                            if (pos[i] > ends[i]) break
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (pos[i] == before) {
                if (++stalls >= 3) throw IOException("Connection keeps closing, try again later")
            } else stalls = 0
        }
    }

    private suspend fun singleStream(item: DownloadItem) {
        item.counter.set(0)
        item.downloaded = 0
        val conn = open(item, item.finalUrl, null)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            item.total = conn.contentLengthLong
            conn.inputStream.use { input ->
                item.file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        item.downloaded = item.counter.addAndGet(r.toLong())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun probe(item: DownloadItem) {
        val conn = open(item, item.url, "bytes=0-0")
        try {
            when (val code = conn.responseCode) {
                206 -> {
                    val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                    item.total = total
                    item.rangeOk = total > 0
                }
                200 -> {
                    item.total = conn.contentLengthLong
                    item.rangeOk = false
                }
                else -> throw IOException("HTTP $code")
            }
            item.finalUrl = conn.url.toString()
        } finally {
            conn.disconnect()
        }
        item.probed = true
    }

    /** Opens a connection, following redirects manually so cookies are never sent to another host. */
    private fun open(item: DownloadItem, startUrl: String, range: String?): HttpURLConnection {
        var current = startUrl
        val originalHost = URL(item.url).host
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", item.userAgent ?: "Locky")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (range != null) c.setRequestProperty("Range", range)
            if (item.cookie != null && URL(current).host == originalHost) c.setRequestProperty("Cookie", item.cookie)

            val code = c.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Bad redirect")
                c.disconnect()
                val next = URL(URL(current), loc).toString()
                if (current.startsWith("https://") && next.startsWith("http://")) {
                    throw IOException("Blocked insecure redirect from HTTPS to HTTP")
                }
                current = next
                return@repeat
            }
            return c
        }
        throw IOException("Too many redirects")
    }

    companion object {
        const val PARTS = 8
        const val MIN_SPLIT = 1_000_000L
    }
}

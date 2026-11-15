package app.hermes.channels

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.hermes.channels.core.Guide
import app.hermes.channels.core.Library
import app.hermes.channels.core.M3u
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The playlist and the guide, fetched and kept on the device.
 *
 * The playlist is read from its copy on the device at once (so a channel plays within a second of starting) and
 * fetched again in the background when the copy is half a day old. The guide is walked once into two small files
 * (its channels, and the programmes of the next day and a half); memory holds only what is on in the next hours.
 * Nothing that holds the playlist link or a stream address is logged.
 */
object Repo {
    private const val TAG = "Channels"
    private const val FRESH_MS = 12L * 3600 * 1000
    private const val WINDOW_MS = 36L * 3600 * 1000
    private const val SOON_MS = 8L * 3600 * 1000

    private val main = Handler(Looper.getMainLooper())
    @Volatile var lib: Library? = null
        private set
    @Volatile var error = ""
        private set
    /** "", "Reading the guide", "No guide" ... for the settings */
    @Volatile var guideNote = ""
        private set
    @Volatile private var busy = false
    @Volatile private var gids: Array<String?> = emptyArray()
    @Volatile private var icons: Map<String, String> = emptyMap()
    @Volatile private var soon: Map<String, List<Guide.Prog>> = emptyMap()
    @Volatile private var soonAt = 0L

    private fun fetch(address: String): InputStream {
        var c = URL(address).openConnection() as HttpURLConnection
        var hops = 0
        while (true) {
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Channels")
            val code = c.responseCode
            if (code in 301..308 && hops++ < 4) {
                val next = URL(c.url, c.getHeaderField("Location") ?: throw java.io.IOException("HTTP $code"))
                c.disconnect(); c = next.openConnection() as HttpURLConnection
                continue
            }
            if (code != 200) throw java.io.IOException("HTTP $code")
            return c.inputStream
        }
    }
    private fun text(address: String) = fetch(address).bufferedReader().use { it.readText() }

    private fun listFile(ctx: Context) = File(ctx.filesDir, "playlist.m3u")
    private fun extraFile(ctx: Context) = File(ctx.filesDir, "extra.m3u")
    private fun chanFile(ctx: Context) = File(ctx.filesDir, "guide-channels.tsv")
    private fun progFile(ctx: Context) = File(ctx.filesDir, "guide.tsv")

    /** a new playlist link was given: what was kept of the old one goes */
    fun forget(ctx: Context) {
        listFile(ctx).delete(); extraFile(ctx).delete(); chanFile(ctx).delete(); progFile(ctx).delete()
        lib = null; gids = emptyArray(); icons = emptyMap(); soon = emptyMap(); error = ""
    }

    /**
     * Makes the channels ready ([ready] on the main thread, also when it fails: see [error]), then the guide
     * ([guide] on the main thread whenever there is more to show). [force] fetches everything again.
     */
    fun start(ctx: Context, force: Boolean, ready: () -> Unit, guide: () -> Unit) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (lib != null && !force) { ready(); if (!busy && soon.isEmpty()) loadGuide(app, prefs, false, guide) else guide(); return }
        if (busy) return
        if (prefs.playlist.isEmpty()) { error = "none"; ready(); return }
        busy = true
        Thread({
            try {
                val lf = listFile(app); val ef = extraFile(app)
                val t0 = SystemClock.elapsedRealtime()
                val stale = force || !lf.exists() || System.currentTimeMillis() - lf.lastModified() > FRESH_MS
                var own: String? = if (lf.exists() && !force) lf.readText() else null
                var fetched = false
                if (own == null) { own = text(prefs.playlist); lf.writeText(own); fetched = true }
                var other: String? = if (ef.exists() && !force) ef.readText() else null
                if (other == null && prefs.extra.isNotEmpty()) other = try { text(prefs.extra).also { ef.writeText(it) } } catch (_: Exception) { null }
                val merged = M3u.merge(M3u.parse(own), other?.let { M3u.parse(it) })
                if (merged.channels.isEmpty()) throw java.io.IOException("empty")
                lib = Library(merged.channels)
                guideUrl = prefs.guide.ifEmpty { merged.guideUrl }
                error = ""
                Log.i(TAG, "playlist: ${merged.channels.size} channels, ${M3u.groups(merged.channels).size} groups, ${merged.channels.count { it.logo.isNotEmpty() }} logos, ${SystemClock.elapsedRealtime() - t0} ms" + (if (fetched) " (fetched)" else ""))
                main.post(ready)
                // an old copy is replaced for the next start
                if (stale && !fetched) try {
                    val fresh = text(prefs.playlist)
                    if (M3u.parse(fresh).channels.isNotEmpty()) lf.writeText(fresh)
                    if (prefs.extra.isNotEmpty()) text(prefs.extra).let { if (it.isNotEmpty()) ef.writeText(it) }
                } catch (_: Exception) {}
                guideWork(app, force, guide)
            } catch (e: Exception) {
                Log.w(TAG, "playlist failed: ${e.javaClass.simpleName}")
                error = "failed"
                main.post(ready)
            } finally { busy = false }
        }, "channels-list").start()
    }

    @Volatile private var guideUrl = ""

    private fun loadGuide(app: Context, prefs: Prefs, force: Boolean, guide: () -> Unit) {
        if (busy) return
        busy = true
        Thread({ try { guideWork(app, force, guide) } finally { busy = false } }, "channels-guide").start()
    }

    private fun guideWork(app: Context, force: Boolean, changed: () -> Unit) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val l = lib ?: return
        if (guideUrl.isEmpty()) { guideNote = "No guide link"; index(app, l); main.post(changed); return }
        val cf = chanFile(app); val pf = progFile(app)
        if (cf.exists() && pf.exists()) { index(app, l); loadSoon(app); main.post(changed) }
        if (force || !cf.exists() || !pf.exists() || System.currentTimeMillis() - pf.lastModified() > FRESH_MS) {
            guideNote = "Reading the guide"
            main.post(changed)
            val tc = File(app.filesDir, "guide-channels.tmp"); val tp = File(app.filesDir, "guide.tmp")
            try {
                val t0 = SystemClock.elapsedRealtime()
                val now = System.currentTimeMillis()
                var chans = 0; var progs = 0
                val raw = fetch(guideUrl)
                val input = if (guideUrl.substringBefore('?').endsWith(".gz", true)) GZIPInputStream(raw, 1 shl 16) else raw
                tc.bufferedWriter().use { wc ->
                    tp.bufferedWriter().use { wp ->
                        val scan = Guide.Scanner({ b ->
                            Guide.chanOf(b)?.let { c -> wc.write(c.id + "\t" + c.icon + "\t" + c.names.joinToString("\t") { it.replace('\t', ' ') } + "\n"); chans++ }
                        }, { b ->
                            Guide.progOf(b)?.let { (id, p) ->
                                if (p.stop > now - 3600_000 && p.start < now + WINDOW_MS) { wp.write(id + "\t" + p.start + "\t" + p.stop + "\t" + p.title.replace('\t', ' ').replace('\n', ' ') + "\n"); progs++ }
                            }
                        })
                        input.use { s ->
                            val buf = ByteArray(1 shl 16)
                            while (true) { val n = s.read(buf); if (n < 0) break; scan.feed(buf, n) }
                        }
                    }
                }
                if (chans == 0) throw java.io.IOException("no channels")
                tc.renameTo(cf); tp.renameTo(pf)
                Log.i(TAG, "guide: $chans channels, $progs programmes, ${(SystemClock.elapsedRealtime() - t0) / 1000} s")
                guideNote = ""
                index(app, l); loadSoon(app)
            } catch (e: Exception) {
                tc.delete(); tp.delete()
                guideNote = if (cf.exists()) "" else "Could not read the guide"
                Log.w(TAG, "guide failed: ${e.javaClass.simpleName}")
            }
            main.post(changed)
        } else guideNote = ""
    }

    /** which channel of the guide each channel of the playlist is: by the id the playlist states, else by name */
    private fun index(app: Context, l: Library) {
        val ids = HashSet<String>(); val names = HashMap<String, String>(); val ic = HashMap<String, String>()
        try {
            chanFile(app).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val f = line.split('\t')
                    if (f.size < 2) continue
                    ids.add(f[0])
                    if (f[1].isNotEmpty()) ic[f[0]] = f[1]
                    for (i in 2 until f.size) M3u.norm(f[i]).let { if (it.isNotEmpty()) names.putIfAbsent(it, f[0]) }
                }
            }
        } catch (_: Exception) {}
        val g = arrayOfNulls<String>(l.channels.size)
        var n = 0
        for ((i, c) in l.channels.withIndex()) {
            g[i] = if (c.tvgId.isNotEmpty() && c.tvgId in ids) c.tvgId else names[M3u.norm(c.name)]
            if (g[i] != null) n++
        }
        icons = ic; gids = g
        if (ids.isNotEmpty()) Log.i(TAG, "guide covers $n of ${l.channels.size} channels")
    }

    /** what is on in the next hours, for the channels of the playlist */
    private fun loadSoon(app: Context) {
        val want = gids.filterNotNull().toHashSet()
        val now = System.currentTimeMillis()
        val out = HashMap<String, ArrayList<Guide.Prog>>()
        try {
            progFile(app).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val a = line.indexOf('\t')
                    if (a <= 0 || line.substring(0, a) !in want) continue
                    val f = line.split('\t', limit = 4)
                    if (f.size < 4) continue
                    val start = f[1].toLongOrNull() ?: continue; val stop = f[2].toLongOrNull() ?: continue
                    if (stop > now && start < now + SOON_MS) out.getOrPut(f[0]) { ArrayList() }.add(Guide.Prog(start, stop, f[3]))
                }
            }
        } catch (_: Exception) {}
        for (v in out.values) v.sortBy { it.start }
        soon = out; soonAt = now
    }

    /** call now and then: what is "soon" moves on with the clock */
    fun keepFresh(ctx: Context, changed: () -> Unit) {
        if (busy || lib == null || soon.isEmpty() || System.currentTimeMillis() - soonAt < 1800_000) return
        val app = ctx.applicationContext
        Thread({ loadSoon(app); main.post(changed) }, "channels-soon").start()
    }

    fun logo(i: Int): String {
        val c = lib?.channels?.getOrNull(i) ?: return ""
        return c.logo.ifEmpty { gids.getOrNull(i)?.let { icons[it] }.orEmpty() }
    }

    fun nowNext(i: Int, now: Long): Pair<Guide.Prog?, Guide.Prog?> = Guide.nowNext(gids.getOrNull(i)?.let { soon[it] }, now)

    /** every programme the guide has for channel [i], in time order. Reads a file: not for the main thread. */
    fun programmes(ctx: Context, i: Int): List<Guide.Prog> {
        val id = gids.getOrNull(i) ?: return emptyList()
        val out = ArrayList<Guide.Prog>()
        try {
            progFile(ctx.applicationContext).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith(id) || line.getOrNull(id.length) != '\t') continue
                    val f = line.split('\t', limit = 4)
                    if (f.size == 4) out.add(Guide.Prog(f[1].toLongOrNull() ?: continue, f[2].toLongOrNull() ?: continue, f[3]))
                }
            }
        } catch (_: Exception) {}
        out.sortBy { it.start }
        return out
    }
}

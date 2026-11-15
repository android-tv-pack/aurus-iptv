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
    /** the thread that fetches for the playlist that is watched (null: none does) */
    @Volatile private var worker: Any? = null
    private val busy: Boolean get() = worker != null
    /** the playlist that is watched (see Prefs.source), and a count of the times it or its link changed: what a thread
     *  started before such a change still finds out goes to that playlist's files, not to what is shown */
    @Volatile private var src = 0
    @Volatile private var gen = 0
    val source: Int get() = src
    private fun tail(s: Int) = if (s == 0) "" else "-$s"

    /** another playlist is watched from now on */
    fun switchTo(s: Int) {
        src = s; gen++; worker = null
        lib = null; gids = emptyArray(); icons = emptyMap(); soon = emptyMap(); error = ""; guideNote = ""; covered = 0; recDays = 0; recCount = 0; guideUrl = ""
    }
    @Volatile private var gids: Array<String?> = emptyArray()
    @Volatile private var icons: Map<String, String> = emptyMap()
    @Volatile private var soon: Map<String, List<Guide.Prog>> = emptyMap()
    @Volatile private var soonAt = 0L

    private fun fetch(address: String): InputStream {
        var c = URL(address).openConnection() as HttpURLConnection
        var hops = 0
        while (true) {
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AurusIPTV")
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

    private fun listFile(ctx: Context, s: Int = src) = File(ctx.filesDir, "playlist${tail(s)}.m3u")
    private fun extraFile(ctx: Context, s: Int = src) = File(ctx.filesDir, "extra${tail(s)}.m3u")
    private fun chanFile(ctx: Context, s: Int = src) = File(ctx.filesDir, "guide-channels${tail(s)}.tsv")
    private fun progFile(ctx: Context, s: Int = src) = File(ctx.filesDir, "guide2${tail(s)}.tsv")
    /** what was on earlier (titles only), for the channels that are recorded */
    private fun pastFile(ctx: Context, s: Int = src) = File(ctx.filesDir, "guide-past${tail(s)}.tsv")
    /** how many days back the playlist's channels are recorded at most (0 = none is) */
    @Volatile var recDays = 0
        private set
    @Volatile var recCount = 0
        private set

    // ---- figures for the settings
    @Volatile var covered = 0
        private set
    fun listTime(ctx: Context): Long = listFile(ctx.applicationContext).let { if (it.exists()) it.lastModified() else 0L }
    fun guideTime(ctx: Context): Long = progFile(ctx.applicationContext).let { if (it.exists()) it.lastModified() else 0L }
    val guideLink: String get() = guideUrl
    val working: Boolean get() = busy

    /** playlist [s] gets its programme data from elsewhere: what was read of the old EPG goes (the playlist's own copy stays) */
    fun forgetGuide(ctx: Context, s: Int) {
        chanFile(ctx, s).delete(); progFile(ctx, s).delete(); pastFile(ctx, s).delete()
        if (s == src) { covered = 0; lib = null; gids = emptyArray(); icons = emptyMap(); soon = emptyMap(); guideNote = ""; gen++; worker = null }
    }

    /** playlist [s] is removed: what was kept of it on the device goes */
    fun forgetFiles(ctx: Context, s: Int) {
        if (s == src) { forget(ctx); return }
        listFile(ctx, s).delete(); extraFile(ctx, s).delete(); chanFile(ctx, s).delete(); progFile(ctx, s).delete(); pastFile(ctx, s).delete()
    }

    /** a new playlist link was given: what was kept of the old one goes */
    fun forget(ctx: Context) {
        listFile(ctx).delete(); extraFile(ctx).delete(); chanFile(ctx).delete(); progFile(ctx).delete(); pastFile(ctx).delete()
        File(ctx.filesDir, "guide.tsv").delete()            // (the file of an earlier version)
        covered = 0
        lib = null; gids = emptyArray(); icons = emptyMap(); soon = emptyMap(); error = ""
        gen++; worker = null
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
        val link = prefs.playlist; val extraLink = prefs.extra; val ownGuide = prefs.guideLink
        if (link.isEmpty()) { error = "none"; ready(); return }
        val s = src; val g = gen
        val me = Any(); worker = me
        val readyG = Runnable { if (g == gen) ready() }; val guideG = { if (g == gen) guide() }
        Thread({
            try {
                val lf = listFile(app, s); val ef = extraFile(app, s)
                val t0 = SystemClock.elapsedRealtime()
                val stale = force || !lf.exists() || System.currentTimeMillis() - lf.lastModified() > FRESH_MS
                var own: String? = if (lf.exists() && !force) lf.readText() else null
                var fetched = false
                if (own == null) { own = text(link); lf.writeText(own); fetched = true }
                var other: String? = if (ef.exists() && !force) ef.readText() else null
                if (other == null && extraLink.isNotEmpty()) other = try { text(extraLink).also { ef.writeText(it) } } catch (_: Exception) { null }
                val merged = M3u.merge(M3u.parse(own), other?.let { M3u.parse(it) })
                if (merged.channels.isEmpty()) throw java.io.IOException("empty")
                if (g != gen) return@Thread
                recCount = merged.channels.count { it.rec != null }
                recDays = merged.channels.maxOfOrNull { it.rec?.days ?: 0 } ?: 0
                lib = Library(merged.channels)
                guideUrl = ownGuide.ifEmpty { merged.guideUrl }
                error = ""
                Log.i(TAG, "playlist: ${merged.channels.size} channels, ${M3u.groups(merged.channels).size} groups, ${merged.channels.count { it.logo.isNotEmpty() }} logos, $recCount recorded (up to $recDays days), ${SystemClock.elapsedRealtime() - t0} ms" + (if (fetched) " (fetched)" else ""))
                main.post(readyG)
                // an old copy is replaced for the next start
                if (stale && !fetched) try {
                    val fresh = text(link)
                    if (M3u.parse(fresh).channels.isNotEmpty()) lf.writeText(fresh)
                    if (extraLink.isNotEmpty()) text(extraLink).let { if (it.isNotEmpty()) ef.writeText(it) }
                } catch (_: Exception) {}
                guideWork(app, force, guideG, s, g)
            } catch (e: Exception) {
                Log.w(TAG, "playlist failed: ${e.javaClass.simpleName}")
                if (g == gen) error = "failed"
                main.post(readyG)
            } finally { if (worker === me) worker = null }
        }, "channels-list").start()
    }

    @Volatile private var guideUrl = ""

    private fun loadGuide(app: Context, prefs: Prefs, force: Boolean, guide: () -> Unit) {
        if (busy) return
        val s = src; val g = gen
        val me = Any(); worker = me
        Thread({ try { guideWork(app, force, { if (g == gen) guide() }, s, g) } finally { if (worker === me) worker = null } }, "channels-guide").start()
    }

    private fun guideWork(app: Context, force: Boolean, changed: () -> Unit, s: Int, g: Int) {
        if (g != gen) return
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val l = lib ?: return
        if (guideUrl.isEmpty()) { if (g == gen) guideNote = "No EPG link"; index(app, l, s, g); main.post { if (g == gen) changed() }; return }
        val cf = chanFile(app, s); val pf = progFile(app, s)
        if (cf.exists() && pf.exists()) { index(app, l, s, g); loadSoon(app, s, g); main.post { if (g == gen) changed() } }
        val qf = pastFile(app, s)
        val pastMs = recDays.coerceAtMost(7) * 86_400_000L
        if (force || !cf.exists() || !pf.exists() || (pastMs > 0 && !qf.exists()) || System.currentTimeMillis() - pf.lastModified() > FRESH_MS) {
            if (g == gen) guideNote = "Reading the EPG"
            File(app.filesDir, "guide.tsv").delete()            // (the file of an earlier version, without descriptions)
            main.post { if (g == gen) changed() }
            // (each reading has files of its own: one that was overtaken by a newer choice of EPG must not write into the newer one's)
            val tc = File(app.filesDir, "guide-channels${tail(s)}.$g.tmp"); val tp = File(app.filesDir, "guide${tail(s)}.$g.tmp"); val tq = File(app.filesDir, "guide-past${tail(s)}.$g.tmp")
            try {
                val t0 = SystemClock.elapsedRealtime()
                val now = System.currentTimeMillis()
                var chans = 0; var progs = 0
                val raw = fetch(guideUrl)
                // (packed or not: told from the first two bytes, since a link need not end in .gz and may lead on to another)
                val head = java.io.BufferedInputStream(raw, 1 shl 16)
                head.mark(2); val b0 = head.read(); val b1 = head.read(); head.reset()
                val input: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(head, 1 shl 16) else head
                tc.bufferedWriter().use { wc ->
                    tq.bufferedWriter().use { wq ->
                    tp.bufferedWriter().use { wp ->
                        val scan = Guide.Scanner({ b ->
                            Guide.chanOf(b)?.let { c -> wc.write(c.id + "\t" + c.icon + "\t" + c.names.joinToString("\t") { it.replace('\t', ' ') } + "\n"); chans++ }
                        }, { b ->
                            Guide.progOf(b)?.let { (id, p) ->
                                if (p.stop <= now - 3600_000 && p.stop > now - pastMs) wq.write(id + "\t" + p.start + "\t" + p.stop + "\t" + p.title.replace('\t', ' ').replace('\n', ' ') + "\n")
                                if (p.stop > now - 3600_000 && p.start < now + WINDOW_MS) { wp.write(id + "\t" + p.start + "\t" + p.stop + "\t" + p.title.replace('\t', ' ').replace('\n', ' ') + "\t" + p.desc.take(600).replace('\t', ' ').replace('\n', ' ') + "\n"); progs++ }
                            }
                        })
                        input.use { s ->
                            val buf = ByteArray(1 shl 16)
                            while (true) { if (g != gen) throw java.io.IOException("overtaken"); val n = s.read(buf); if (n < 0) break; scan.feed(buf, n) }
                        }
                    }
                    }
                }
                if (chans == 0) throw java.io.IOException("no channels")
                if (g != gen) throw java.io.IOException("overtaken")
                tc.renameTo(cf); tp.renameTo(pf); tq.renameTo(qf)
                Log.i(TAG, "guide: $chans channels, $progs programmes, ${(SystemClock.elapsedRealtime() - t0) / 1000} s")
                if (g == gen) guideNote = ""
                index(app, l, s, g); loadSoon(app, s, g)
            } catch (e: Exception) {
                tc.delete(); tp.delete(); tq.delete()
                if (g == gen) guideNote = if (cf.exists()) "" else "Could not read the EPG"
                Log.w(TAG, "guide failed: ${e.javaClass.simpleName}")
            }
            main.post { if (g == gen) changed() }
        } else if (g == gen) guideNote = ""
    }

    /** which channel of the guide each channel of the playlist is: by the id the playlist states, else by name */
    private fun index(app: Context, l: Library, s: Int, gn: Int) {
        val ids = HashSet<String>(); val names = HashMap<String, String>(); val ic = HashMap<String, String>()
        try {
            chanFile(app, s).bufferedReader().useLines { lines ->
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
        if (gn != gen) return
        icons = ic; gids = g; covered = n
        if (ids.isNotEmpty()) Log.i(TAG, "guide covers $n of ${l.channels.size} channels")
    }

    /** what is on in the next hours, for the channels of the playlist */
    private fun loadSoon(app: Context, s: Int, g: Int) {
        val want = gids.filterNotNull().toHashSet()
        val now = System.currentTimeMillis()
        val out = HashMap<String, ArrayList<Guide.Prog>>()
        try {
            progFile(app, s).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val a = line.indexOf('\t')
                    if (a <= 0 || line.substring(0, a) !in want) continue
                    val f = line.split('\t', limit = 5)
                    if (f.size < 4) continue
                    val start = f[1].toLongOrNull() ?: continue; val stop = f[2].toLongOrNull() ?: continue
                    if (stop > now && start < now + SOON_MS) out.getOrPut(f[0]) { ArrayList() }.add(Guide.Prog(start, stop, f[3]))
                }
            }
        } catch (_: Exception) {}
        for (v in out.values) v.sortBy { it.start }
        if (g != gen) return
        soon = out; soonAt = now
    }

    /** call now and then: what is "soon" moves on with the clock */
    fun keepFresh(ctx: Context, changed: () -> Unit) {
        if (busy || lib == null || soon.isEmpty() || System.currentTimeMillis() - soonAt < 1800_000) return
        val app = ctx.applicationContext
        val s = src; val g = gen
        Thread({ loadSoon(app, s, g); main.post { if (g == gen) changed() } }, "channels-soon").start()
    }

    fun logo(i: Int): String {
        val c = lib?.channels?.getOrNull(i) ?: return ""
        return c.logo.ifEmpty { gids.getOrNull(i)?.let { icons[it] }.orEmpty() }
    }

    fun nowNext(i: Int, now: Long): Pair<Guide.Prog?, Guide.Prog?> = Guide.nowNext(gids.getOrNull(i)?.let { soon[it] }, now)

    /** every programme the guide has for channel [i], in time order; with [past] also what was on in the days before. Reads files: not for the main thread. */
    fun programmes(ctx: Context, i: Int, past: Boolean = false): List<Guide.Prog> {
        val id = gids.getOrNull(i) ?: return emptyList()
        val out = ArrayList<Guide.Prog>()
        for (file in listOfNotNull(if (past) pastFile(ctx.applicationContext) else null, progFile(ctx.applicationContext))) try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith(id) || line.getOrNull(id.length) != '\t') continue
                    val f = line.split('\t', limit = 5)
                    if (f.size >= 4) out.add(Guide.Prog(f[1].toLongOrNull() ?: continue, f[2].toLongOrNull() ?: continue, f[3], f.getOrNull(4).orEmpty()))
                }
            }
        } catch (_: Exception) {}
        out.sortBy { it.start }
        return out
    }
}

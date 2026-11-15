package app.hermes.channels

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * The TV's home screen: the channels watched last (and the favourites) are published as one row, each with its
 * logo and a link that opens this app on that channel. Any Android TV home screen can show them.
 */
object Home {
    private const val TAG = "Channels"
    private val CHANNELS = Uri.parse("content://android.media.tv/channel")
    private val PROGRAMS = Uri.parse("content://android.media.tv/preview_program")

    fun link(key: String): String = "channels://play/" + Uri.encode(key)

    /** [rows]: key, name, logo per line (see Prefs.homeRows) */
    fun publish(ctx: Context, rows: List<String>) {
        if (Build.VERSION.SDK_INT < 26) return
        val app = ctx.applicationContext
        Thread({
            try {
                val prefs = Prefs(app)
                val cr = app.contentResolver
                var id = prefs.homeChannel
                if (id != 0L && cr.query(ContentUris.withAppendedId(CHANNELS, id), arrayOf("_id"), null, null, null)?.use { it.count } != 1) id = 0L
                if (id == 0L) {
                    val v = ContentValues().apply {
                        put("type", "TYPE_PREVIEW")
                        put("display_name", "Channels")
                        put("input_id", app.packageName + "/.MainActivity")
                        put("app_link_intent_uri", "channels://open")
                        put("internal_provider_id", "recent")
                    }
                    id = ContentUris.parseId(cr.insert(CHANNELS, v) ?: throw IllegalStateException("no channel"))
                    prefs.homeChannel = id
                    try { android.media.tv.TvContract.requestChannelBrowsable(app, id) } catch (_: Exception) {}
                }
                // (the provider takes no "where": the row's programmes are addressed through the channel)
                cr.delete(PROGRAMS.buildUpon().appendQueryParameter("channel", id.toString()).build(), null, null)
                val values = rows.take(15).mapIndexedNotNull { i, line ->
                    val f = line.split('\t')
                    if (f.size < 2) null else ContentValues().apply {
                        put("channel_id", id)
                        put("type", 6)                                    // a TV channel
                        put("title", f[1])
                        put("internal_provider_id", f[0])
                        put("intent_uri", link(f[0]))
                        put("weight", 100 - i)
                        if (f.getOrNull(2).orEmpty().isNotEmpty()) { put("poster_art_uri", f[2]); put("poster_art_aspect_ratio", 3) }     // 3 = square
                    }
                }
                if (values.isNotEmpty()) cr.bulkInsert(PROGRAMS, values.toTypedArray())
                Log.i(TAG, "home: ${values.size} channels published")
            } catch (e: Exception) {
                Log.w(TAG, "home failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
            }
        }, "channels-home").start()
    }
}

/** The home screen asks apps to publish their rows after they are installed. */
class HomeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Home.publish(ctx, Prefs(ctx).homeRows)
    }
}

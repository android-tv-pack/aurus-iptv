package app.hermes.channels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Channel logos and film posters: small, kept in memory, fetched a few at a time. */
object Images {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()

    /** shows the picture at [url] in [v] (nothing when there is none); [v] may be given another picture meanwhile */
    fun show(url: String, v: ImageView, px: Int) {
        v.tag = url
        val have = if (url.isEmpty()) null else cache.get(url)
        v.setImageBitmap(have)
        if (have != null || url.isEmpty() || synchronized(missing) { url in missing }) return
        pool.execute {
            if (v.tag != url) return@execute                  // the row has moved on while this waited
            val b = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 8000
                val bytes = c.inputStream.use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Exception) { null }
            if (b == null) synchronized(missing) { missing.add(url) } else cache.put(url, b)
            main.post { if (v.tag == url) v.setImageBitmap(b) }
        }
    }
}

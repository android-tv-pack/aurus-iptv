package app.hermes.channels

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * The screen is kept on while something plays. When the picture stands still (paused, or a channel that delivers
 * nothing) it is let go after a minute, so that the TV turns to its screen saver and to sleep in its own time, as it
 * does when nothing plays. (A projector left on a channel that stopped would otherwise stay lit all night.)
 */
class ScreenOn(private val a: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private val letGo = Runnable { a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    /** something plays ([on]), or it has stopped */
    fun playing(on: Boolean) {
        main.removeCallbacks(letGo)
        if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else main.postDelayed(letGo, LET_GO_MS)
    }

    private companion object {
        /** how long a picture may stand still before the screen is let go (a channel that waits for its server comes back sooner) */
        const val LET_GO_MS = 60_000L
    }
}

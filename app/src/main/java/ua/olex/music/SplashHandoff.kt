package ua.olex.music

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.window.SplashScreenView

/**
 * The start, as Google's own apps hand over from the splash:
 *  1. the mark finishes its own animation (the note settling), never cut short by a fast start;
 *  2. the splash stays until Home has its library, so the first frame shown is Home itself rather
 *     than its loading shape, but never longer than [MAX_HOLD_MS] past launch;
 *  3. then the mark comes forward a little and fades, the page colour clears just after it, and
 *     the app beneath settles from 96 % to full size, all within 300 ms.
 */
object SplashHandoff {
    private const val MAX_HOLD_MS = 700L
    private const val EXIT_MS = 300L
    private val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f) // emphasized decelerate
    private val accelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f) // emphasized accelerate

    fun install(activity: Activity, ready: () -> Boolean) {
        val launched = SystemClock.uptimeMillis()
        val content = activity.findViewById<View>(android.R.id.content)
        // Hold the first frame until Home can draw itself, or the time is up.
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (ready() || SystemClock.uptimeMillis() - launched > MAX_HOLD_MS) {
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                content.postOnAnimation { content.invalidate() } // look again next frame
                return false
            }
        })
        activity.splashScreen.setOnExitAnimationListener { splash ->
            if (!ValueAnimator.areAnimatorsEnabled()) { splash.remove(); return@setOnExitAnimationListener }
            // Let the mark's own animation end before the exit starts.
            val start = splash.iconAnimationStart?.toEpochMilli()
            val length = splash.iconAnimationDuration?.toMillis() ?: 0L
            val left = if (start == null) 0L else (start + length - System.currentTimeMillis()).coerceIn(0L, 400L)
            splash.postDelayed({ handOver(splash, content) }, left)
        }
    }

    private fun handOver(splash: SplashScreenView, content: View) {
        val icon = splash.iconView
        val background = (splash.background as? ColorDrawable)?.color
        content.scaleX = 0.96f
        content.scaleY = 0.96f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXIT_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // The mark: forward and gone in the first half.
                val markT = (t / 0.5f).coerceIn(0f, 1f)
                icon?.apply {
                    alpha = 1f - accelerate.getInterpolation(markT)
                    val scale = 1f + 0.12f * decelerate.getInterpolation(markT)
                    scaleX = scale; scaleY = scale
                }
                // The page colour clears a beat later, so the mark never floats on the app.
                val clear = ((t - 0.17f) / 0.66f).coerceIn(0f, 1f)
                val opacity = 1f - decelerate.getInterpolation(clear)
                if (background != null) splash.setBackgroundColor(((Color.alpha(background) * opacity).toInt() shl 24) or (background and 0xFFFFFF))
                else splash.alpha = opacity
                // The app settles into place beneath.
                val settle = 0.96f + 0.04f * decelerate.getInterpolation(t)
                content.scaleX = settle; content.scaleY = settle
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    content.scaleX = 1f; content.scaleY = 1f
                    splash.remove()
                }
            })
            start()
        }
    }
}

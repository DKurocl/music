package ua.olex.music

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.window.SplashScreenView
import java.time.Duration
import java.time.Instant

/**
 * From the splash into the app, as Google's apps do it. Android shows the launcher icon in its
 * circle and plays its gesture (drawable/splash_icon.xml: the record turns one scallop, the note
 * dips and springs, a ring of sound leaves the edge). Then the circle sinks back a little and
 * fades while the splash dissolves into the page behind it, on an emphasized curve.
 */
object SplashHandoff {
    // Material's emphasized decelerate curve.
    private val emphasized = android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    fun install(activity: Activity) {
        activity.splashScreen.setOnExitAnimationListener { splash ->
            // Let the icon finish its gesture; an app that is ready sooner still waits for it.
            val end = splash.iconAnimationStart?.plus(splash.iconAnimationDuration ?: Duration.ZERO)
            val wait = end?.let { (it.toEpochMilli() - Instant.now().toEpochMilli()).coerceIn(0, 800) } ?: 0
            splash.postDelayed({ handOver(splash) }, wait)
        }
    }

    private fun handOver(splash: SplashScreenView) {
        val icon = splash.iconView
        val background = (splash.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val e = emphasized.getInterpolation(t)
                splash.setBackgroundColor((((1 - e) * Color.alpha(background)).toInt() shl 24) or (background and 0xFFFFFF))
                icon?.apply {
                    val k = 1f - 0.18f * e
                    scaleX = k; scaleY = k
                    alpha = 1f - (t / 0.8f).coerceAtMost(1f)
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { splash.remove() }
            })
            start()
        }
    }
}

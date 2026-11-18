package ua.olex.music

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.animation.PathInterpolator
import android.window.SplashScreenView

/** Lets the Play mark build during startup and releases the splash when Home is ready. */
object SplashHandoff {
    private val settle = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    fun install(activity: Activity) {
        activity.splashScreen.setOnExitAnimationListener { splash ->
            if (ValueAnimator.areAnimatorsEnabled()) handOver(splash) else splash.remove()
        }
    }

    private fun handOver(splash: SplashScreenView) {
        val icon = splash.iconView
        val background = (splash.background as? ColorDrawable)?.color
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedValue as Float
                val opacity = 1f - progress
                if (background != null) {
                    splash.setBackgroundColor(((Color.alpha(background) * opacity).toInt() shl 24) or (background and 0xFFFFFF))
                    icon?.alpha = opacity
                } else {
                    splash.alpha = opacity
                }
                icon?.apply {
                    val scale = 1f - 0.06f * settle.getInterpolation(progress)
                    scaleX = scale
                    scaleY = scale
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { splash.remove() }
            })
            start()
        }
    }
}

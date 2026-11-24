package com.jarvis.launcher.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.jarvis.launcher.ai.CoreMode

/**
 * A glowing border around the whole screen that shows JARVIS's state on top of any app:
 * listening, thinking or speaking. It never takes touches and disappears when JARVIS is idle.
 */
class EdgeGlow(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: GlowView? = null

    val permitted: Boolean get() = Settings.canDrawOverlays(context)

    /** Show the glow for [mode] in [color] (ARGB), or hide it when idle. Main thread. */
    fun show(mode: CoreMode, color: Int) {
        if (mode == CoreMode.IDLE || !permitted) { hide(); return }
        val v = view ?: GlowView(context).also { add(it); view = it }
        v.set(mode, color)
    }

    fun hide() {
        view?.let { v -> v.stop(); runCatching { wm.removeView(v) } }
        view = null
    }

    private fun add(v: GlowView) {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "JARVIS glow"
        }
        runCatching { wm.addView(v, lp) }
    }

    private class GlowView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var color = 0xFF2EE6FF.toInt()
        private var mode = CoreMode.LISTENING
        private var phase = 0f
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }

        fun set(m: CoreMode, c: Int) { mode = m; color = c; anim.duration = if (m == CoreMode.THINKING) 900 else 1600; invalidate() }
        fun stop() = anim.cancel()

        private fun withAlpha(c: Int, a: Float) = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val pulse = (kotlin.math.sin(phase * 2 * Math.PI) * 0.5 + 0.5).toFloat()
            val strength = when (mode) {
                CoreMode.SPEAKING -> 0.55f + 0.35f * pulse
                CoreMode.THINKING -> 0.45f + 0.4f * pulse
                else -> 0.5f + 0.2f * pulse
            }
            val d = (minOf(w, h) * 0.05f) * (0.8f + 0.4f * pulse)
            val inner = withAlpha(color, strength)
            val clear = withAlpha(color, 0f)
            // Four edges, each a gradient fading inward.
            paint.shader = LinearGradient(0f, 0f, 0f, d, inner, clear, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, w, d, paint)
            paint.shader = LinearGradient(0f, h, 0f, h - d, inner, clear, Shader.TileMode.CLAMP); canvas.drawRect(0f, h - d, w, h, paint)
            paint.shader = LinearGradient(0f, 0f, d, 0f, inner, clear, Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, d, h, paint)
            paint.shader = LinearGradient(w, 0f, w - d, 0f, inner, clear, Shader.TileMode.CLAMP); canvas.drawRect(w - d, 0f, w, h, paint)
            // A bright travelling highlight along the edges while thinking / speaking.
            if (mode != CoreMode.LISTENING) {
                paint.shader = null
                paint.color = withAlpha(0xFFFFFFFF.toInt(), 0.5f * strength)
                val per = 2 * (w + h); var p = phase * per
                val len = per * 0.08f
                fun seg(x1: Float, y1: Float, x2: Float, y2: Float) { paint.strokeWidth = 6f; canvas.drawLine(x1, y1, x2, y2, paint) }
                if (p < w) seg(p, 1f, minOf(w, p + len), 1f) else { p -= w
                    if (p < h) seg(w - 1, p, w - 1, minOf(h, p + len)) else { p -= h
                        if (p < w) seg(w - p, h - 1, maxOf(0f, w - p - len), h - 1) else { p -= w
                            seg(1f, h - p, 1f, maxOf(0f, h - p - len)) } } }
            }
        }
    }
}

package com.andrerinas.openheadunit.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R

/**
 * A car screen drawn small with fingers playing one of the projection gestures in a loop, and what
 * the gesture brings up once the fingers lift.
 */
class GestureDemoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Gesture { MENU, FULLSCREEN, TOP, HOME }

    var gesture = Gesture.MENU
        set(value) {
            field = value
            invalidate()
        }

    private val accent = ContextCompat.getColor(context, R.color.da_accent)
    private val surface = ContextCompat.getColor(context, R.color.da_surface)
    private val border = ContextCompat.getColor(context, R.color.da_border)
    private val muted = ContextCompat.getColor(context, R.color.da_muted)
    private val background = ContextCompat.getColor(context, R.color.da_background)
    private val dp = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.da_text)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val rect = RectF()
    private var t = 0f

    /** Holds the loop at [progress] (0 – 1), for screenshots. */
    internal fun freeze(progress: Float) {
        animator.cancel()
        t = progress
        invalidate()
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // The screen, 8:3 like the MG4's, centred.
        val w = width - 8 * dp
        val h = minOf(height - 8 * dp, w * 3 / 8)
        val sw = h * 8 / 3
        val left = (width - sw) / 2
        val top = (height - h) / 2
        val screen = RectF(left, top, left + sw, top + h)

        // Fingers lift at 0.6; the result stays until the loop starts again.
        val shown = t > 0.6f
        val fullscreenOn = gesture == Gesture.FULLSCREEN && shown

        fill.color = surface
        canvas.drawRoundRect(screen, 18 * dp, 18 * dp, fill)
        drawAndroidAuto(canvas, screen, fullscreenOn)
        stroke.color = border
        canvas.drawRoundRect(screen, 18 * dp, 18 * dp, stroke)

        if (shown) when (gesture) {
            Gesture.MENU -> drawMenu(canvas, screen)
            Gesture.HOME -> drawHome(canvas, screen)
            Gesture.TOP -> drawLauncher(canvas, screen)
            Gesture.FULLSCREEN -> {}
        }
        drawFingers(canvas, screen)
    }

    /** Android Auto's look in outline: a rail with three icons, a map card and a media card. */
    private fun drawAndroidAuto(canvas: Canvas, s: RectF, fullscreen: Boolean) {
        val pad = 12 * dp
        // Without fullscreen the car's own bars take a strip at the top.
        var top = s.top + pad
        if (!fullscreen) {
            fill.color = border
            rect.set(s.left + pad, s.top + pad, s.right - pad, s.top + pad + 10 * dp)
            canvas.drawRoundRect(rect, 5 * dp, 5 * dp, fill)
            top += 18 * dp
        }
        val railW = s.height() * 0.16f
        fill.color = background
        rect.set(s.left + pad, top, s.left + pad + railW, s.bottom - pad)
        canvas.drawRoundRect(rect, 10 * dp, 10 * dp, fill)
        fill.color = muted
        val r = railW * 0.22f
        for (i in 0..2) {
            val cy = top + (s.bottom - pad - top) * (0.22f + i * 0.28f)
            canvas.drawCircle(s.left + pad + railW / 2, cy, r, fill)
        }
        val cardsLeft = s.left + pad * 2 + railW
        val split = cardsLeft + (s.right - pad - cardsLeft) * 0.62f
        fill.color = background
        rect.set(cardsLeft, top, split - pad / 2, s.bottom - pad)
        canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
        rect.set(split + pad / 2, top, s.right - pad, s.bottom - pad)
        canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
        // A road on the map.
        stroke.color = border
        stroke.strokeWidth = 6 * dp
        canvas.drawLine(cardsLeft + 20 * dp, s.bottom - pad - 14 * dp, split - pad - 30 * dp, top + 16 * dp, stroke)
        stroke.strokeWidth = 3 * dp
    }

    /** The real menu: Disconnect (red) and Settings, side by side. */
    private fun drawMenu(canvas: Canvas, s: RectF) {
        fill.color = 0xC0000000.toInt()
        canvas.drawRoundRect(s, 18 * dp, 18 * dp, fill)
        val tileW = s.width() * 0.3f
        val tileH = s.height() * 0.62f
        val gap = 20 * dp
        val cy = s.centerY()
        val tiles = listOf(
            Triple(R.drawable.ic_stop, R.string.menu_disconnect, true),
            Triple(R.drawable.ic_settings_white, R.string.da_settings, false),
        )
        tiles.forEachIndexed { i, (icon, label, danger) ->
            val l = s.centerX() - tileW - gap / 2 + i * (tileW + gap)
            rect.set(l, cy - tileH / 2, l + tileW, cy + tileH / 2)
            fill.color = if (danger) 0xFF3A1820.toInt() else surface
            canvas.drawRoundRect(rect, 14 * dp, 14 * dp, fill)
            stroke.color = if (danger) 0xFF7A2C38.toInt() else border
            canvas.drawRoundRect(rect, 14 * dp, 14 * dp, stroke)
            val size = (tileH * 0.32f).toInt()
            val iconTop = (cy - tileH * 0.28f).toInt()
            ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
                setTint(if (danger) 0xFFFF6B6B.toInt() else accent)
                setBounds((rect.centerX() - size / 2).toInt(), iconTop, (rect.centerX() + size / 2).toInt(), iconTop + size)
                draw(canvas)
            }
            text.textSize = tileH * 0.11f
            canvas.drawText(context.getString(label), rect.centerX(), cy + tileH * 0.3f, text)
        }
    }

    /** DiAuto's own home: a large card and two tiles. */
    private fun drawHome(canvas: Canvas, s: RectF) {
        fill.color = background
        canvas.drawRoundRect(s, 18 * dp, 18 * dp, fill)
        stroke.color = border
        canvas.drawRoundRect(s, 18 * dp, 18 * dp, stroke)
        val pad = 14 * dp
        val split = s.left + s.width() * 0.6f
        fill.color = surface
        rect.set(s.left + pad, s.top + pad, split, s.bottom - pad)
        canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
        fill.color = accent
        val bw = (split - s.left - pad) * 0.6f
        rect.set(rect.centerX() - bw / 2, s.bottom - pad - 34 * dp, rect.centerX() + bw / 2, s.bottom - pad - 16 * dp)
        canvas.drawRoundRect(rect, 9 * dp, 9 * dp, fill)
        canvas.drawCircle((s.left + pad + split) / 2, s.top + s.height() * 0.36f, s.height() * 0.13f, fill)
        fill.color = surface
        val mid = (s.top + s.bottom) / 2
        rect.set(split + pad, s.top + pad, s.right - pad, mid - pad / 2)
        canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
        rect.set(split + pad, mid + pad / 2, s.right - pad, s.bottom - pad)
        canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
    }

    /** The car's launcher: a row of four cards. */
    private fun drawLauncher(canvas: Canvas, s: RectF) {
        fill.color = background
        canvas.drawRoundRect(s, 18 * dp, 18 * dp, fill)
        stroke.color = border
        canvas.drawRoundRect(s, 18 * dp, 18 * dp, stroke)
        val pad = 14 * dp
        val cardW = (s.width() - pad * 5) / 4
        fill.color = surface
        for (i in 0..3) {
            val l = s.left + pad + i * (cardW + pad)
            rect.set(l, s.top + pad * 2, l + cardW, s.bottom - pad * 2)
            canvas.drawRoundRect(rect, 12 * dp, 12 * dp, fill)
        }
    }

    private fun drawFingers(canvas: Canvas, s: RectF) {
        // 0 – 0.1 press, 0.1 – 0.5 slide, 0.5 – 0.6 lift.
        if (t > 0.6f) return
        val alpha = when {
            t < 0.1f -> t / 0.1f
            t > 0.5f -> (0.6f - t) / 0.1f
            else -> 1f
        }
        val p = ((t - 0.1f) / 0.4f).coerceIn(0f, 1f)
        val ease = p * p * (3 - 2 * p)
        val travel = s.width() * 0.4f
        val down = s.height() * 0.45f
        val fingers: List<Pair<Float, Float>> = when (gesture) {
            Gesture.MENU -> List(2) { i -> s.left + 14 * dp + travel * ease to s.centerY() + (i - 0.5f) * 46 * dp }
            Gesture.FULLSCREEN -> List(2) { i -> s.right - 14 * dp - travel * ease to s.centerY() + (i - 0.5f) * 46 * dp }
            Gesture.TOP -> List(2) { i -> s.centerX() + (i - 0.5f) * 46 * dp to s.top + 14 * dp + down * ease }
            Gesture.HOME -> List(3) { i -> s.centerX() + (i - 1) * 44 * dp to s.top + s.height() * 0.2f + down * ease }
        }
        val radius = 17 * dp
        fill.color = accent
        fingers.forEach { (x, y) ->
            // A trail back to where the finger landed.
            val (sx, sy) = when (gesture) {
                Gesture.MENU -> s.left + 14 * dp to y
                Gesture.FULLSCREEN -> s.right - 14 * dp to y
                Gesture.TOP -> x to s.top + 14 * dp
                Gesture.HOME -> x to s.top + s.height() * 0.2f
            }
            stroke.color = accent
            stroke.alpha = (90 * alpha).toInt()
            stroke.strokeWidth = radius * 1.2f
            canvas.drawLine(sx, sy, x, y, stroke)
            fill.alpha = (235 * alpha).toInt()
            canvas.drawCircle(x, y, radius, fill)
        }
        stroke.alpha = 255
        stroke.strokeWidth = 3 * dp
        fill.alpha = 255
    }
}

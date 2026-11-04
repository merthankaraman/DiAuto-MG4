package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.app.BaseActivity

/**
 * Shown once at first start, and again from Settings → About: how to connect, the gestures
 * over Android Auto and where the useful settings are. Can be skipped at any step.
 */
class DaTutorialActivity : BaseActivity() {

    private class Step(
        val title: Int,
        val text: Int,
        val gesture: GestureDemoView.Gesture? = null,
        val icon: Int = 0,
    )

    private val steps = listOf(
        Step(R.string.tuto_welcome_title, R.string.tuto_welcome_text, icon = R.drawable.ic_android_auto),
        Step(R.string.tuto_menu_title, R.string.tuto_menu_text, GestureDemoView.Gesture.MENU),
        Step(R.string.tuto_fullscreen_title, R.string.tuto_fullscreen_text, GestureDemoView.Gesture.FULLSCREEN),
        Step(R.string.tuto_top_title, R.string.tuto_top_text, GestureDemoView.Gesture.TOP),
        Step(R.string.tuto_home_title, R.string.tuto_home_text, GestureDemoView.Gesture.HOME),
        Step(R.string.tuto_tips_title, R.string.tuto_tips_text, icon = R.drawable.ic_da_charging),
    )
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.da_tutorial)
        // Seen once is enough, even if Android Auto takes the screen before the end.
        Settings(this).apply {
            tutorialShown = true
            gestureHintShown = true
        }
        index = savedInstanceState?.getInt(KEY_STEP) ?: 0
        findViewById<View>(R.id.tuto_skip).setOnClickListener { finish() }
        findViewById<View>(R.id.tuto_back).setOnClickListener { show(index - 1) }
        findViewById<View>(R.id.tuto_next).setOnClickListener {
            if (index == steps.lastIndex) finish() else show(index + 1)
        }
        show(index)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_STEP, index)
    }

    private fun show(i: Int) {
        index = i.coerceIn(0, steps.lastIndex)
        val step = steps[index]
        val last = index == steps.lastIndex
        findViewById<TextView>(R.id.tuto_step).text = getString(R.string.tuto_step, index + 1, steps.size)
        findViewById<TextView>(R.id.tuto_title).setText(step.title)
        findViewById<TextView>(R.id.tuto_text).apply {
            setText(step.text)
            // The last step holds five points.
            textSize = if (last) 24f else 27f
        }
        findViewById<GestureDemoView>(R.id.tuto_demo).apply {
            visibility = if (step.gesture != null) View.VISIBLE else View.GONE
            step.gesture?.let { gesture = it }
        }
        findViewById<ImageView>(R.id.tuto_icon).apply {
            visibility = if (step.icon != 0) View.VISIBLE else View.GONE
            if (step.icon != 0) setImageResource(step.icon)
        }
        findViewById<View>(R.id.tuto_back).visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.tuto_skip).visibility = if (last) View.INVISIBLE else View.VISIBLE
        findViewById<TextView>(R.id.tuto_next_label).setText(if (last) R.string.tuto_done else R.string.tuto_next)
        val dots = findViewById<LinearLayout>(R.id.tuto_dots)
        dots.removeAllViews()
        val size = (14 * resources.displayMetrics.density).toInt()
        steps.indices.forEach { d ->
            dots.addView(View(this).apply {
                background = ContextCompat.getDrawable(context, R.drawable.da_primary)?.mutate()
                alpha = if (d == index) 1f else 0.3f
            }, LinearLayout.LayoutParams(if (d == index) size * 3 else size, size).apply {
                marginStart = size / 2
                marginEnd = size / 2
            })
        }
    }

    companion object {
        private const val KEY_STEP = "step"

        fun intent(context: Context) = Intent(context, DaTutorialActivity::class.java)
    }
}

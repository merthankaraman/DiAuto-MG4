package com.andrerinas.openheadunit.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.view.View
import com.andrerinas.openheadunit.aap.protocol.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.andrerinas.openheadunit.hud.SaicNavigationMapper
import com.andrerinas.openheadunit.hud.SaicNavigationMapper.Circulation

/**
 * The image Google's Navigation SDK provides for a maneuver (the `ic_*.png` files of the
 * "Manoeuvres Navigation SDK" table, bundled in assets/maneuver_icons), for the navigation test screen.
 * The images are alpha masks, drawn white on a dark tile.
 */
class ManeuverIconView(context: Context) : View(context) {

    private var fileName: String? = null
    private var bitmap: Bitmap? = null

    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF263238.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }

    /** Shows the image for [type]; returns its file name. */
    fun show(type: NavigationType, exitNumber: Int?, exitAngle: Int?): String {
        val name = imageFor(type, exitNumber, exitAngle)
        if (name != fileName) {
            fileName = name
            bitmap = runCatching { context.assets.open("maneuver_icons/$name").use(BitmapFactory::decodeStream) }.getOrNull()
        }
        invalidate()
        return name
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (140 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) / 10f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, tile)
        val image = bitmap ?: return
        val margin = minOf(width, height) / 8f
        canvas.drawBitmap(image, null, RectF(margin, margin, width - margin, height - margin), ink)
    }

    companion object {
        /**
         * The Google image for an Android Auto maneuver type, following the Navigation SDK table:
         * ramps use the image of their shape, a fork or a keep the fork image. A roundabout takes
         * the image of the direction of its exit (see [SaicNavigationMapper.roundaboutHeading]),
         * one per direction of circulation.
         */
        fun imageFor(type: NavigationType, exitNumber: Int? = null, exitAngle: Int? = null): String = when (type) {
            NavigationType.UNKNOWN, NavigationType.DEPART -> "ic_depart.png"
            NavigationType.NAME_CHANGE, NavigationType.STRAIGHT -> "ic_straight.png"

            NavigationType.TURN_NORMAL_LEFT, NavigationType.ON_RAMP_NORMAL_LEFT -> "ic_turn_left.png"
            NavigationType.TURN_NORMAL_RIGHT, NavigationType.ON_RAMP_NORMAL_RIGHT -> "ic_turn_right.png"
            NavigationType.TURN_SLIGHT_LEFT, NavigationType.ON_RAMP_SLIGHT_LEFT,
            NavigationType.OFF_RAMP_SLIGHT_LEFT -> "ic_turn_slight_left.png"
            NavigationType.TURN_SLIGHT_RIGHT, NavigationType.ON_RAMP_SLIGHT_RIGHT,
            NavigationType.OFF_RAMP_SLIGHT_RIGHT -> "ic_turn_slight_right.png"
            NavigationType.TURN_SHARP_LEFT, NavigationType.ON_RAMP_SHARP_LEFT -> "ic_turn_sharp_left.png"
            NavigationType.TURN_SHARP_RIGHT, NavigationType.ON_RAMP_SHARP_RIGHT -> "ic_turn_sharp_right.png"
            // Left-hand traffic turns a U-turn clockwise, right-hand traffic counter-clockwise.
            NavigationType.U_TURN_LEFT, NavigationType.ON_RAMP_U_TURN_LEFT -> "ic_turn_u_turn_counterclockwise.png"
            NavigationType.U_TURN_RIGHT, NavigationType.ON_RAMP_U_TURN_RIGHT -> "ic_turn_u_turn_clockwise.png"

            NavigationType.KEEP_LEFT, NavigationType.FORK_LEFT -> "ic_fork_left.png"
            NavigationType.KEEP_RIGHT, NavigationType.FORK_RIGHT -> "ic_fork_right.png"
            NavigationType.OFF_RAMP_NORMAL_LEFT, NavigationType.MERGE_LEFT -> "ic_merge_left.png"
            NavigationType.OFF_RAMP_NORMAL_RIGHT, NavigationType.MERGE_RIGHT -> "ic_merge_right.png"
            NavigationType.MERGE_SIDE_UNSPECIFIED -> "ic_merge.png"

            NavigationType.ROUNDABOUT_ENTER -> "ic_roundabout_counterclockwise.png"
            NavigationType.ROUNDABOUT_EXIT -> "ic_roundabout_exit_counterclockwise.png"
            NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
            NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE ->
                roundabout(Circulation.CLOCKWISE, exitNumber, exitAngle)
            NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE ->
                roundabout(Circulation.COUNTER_CLOCKWISE, exitNumber, exitAngle)

            NavigationType.FERRY_BOAT -> "ic_ferry_boat.png"
            NavigationType.FERRY_TRAIN -> "ic_ferry_train.png"
            NavigationType.DESTINATION, NavigationType.DESTINATION_STRAIGHT -> "ic_destination.png"
            NavigationType.DESTINATION_LEFT -> "ic_destination_left.png"
            NavigationType.DESTINATION_RIGHT -> "ic_destination_right.png"
        }

        private fun roundabout(circulation: Circulation, exitNumber: Int?, exitAngle: Int?): String {
            val suffix = if (circulation == Circulation.CLOCKWISE) "clockwise" else "counterclockwise"
            val heading = SaicNavigationMapper.roundaboutHeading(circulation, exitNumber, exitAngle)
                ?: return "ic_roundabout_$suffix.png"
            // Signed deviation from straight on, -180..180 (positive = right).
            val d = ((heading + 180) % 360) - 180
            val shape = when (Math.abs(d)) {
                in 0..22 -> "straight"
                in 23..67 -> if (d > 0) "slight_right" else "slight_left"
                in 68..112 -> if (d > 0) "right" else "left"
                in 113..157 -> if (d > 0) "sharp_right" else "sharp_left"
                else -> "u_turn"
            }
            return "ic_roundabout_${shape}_$suffix.png"
        }
    }
}

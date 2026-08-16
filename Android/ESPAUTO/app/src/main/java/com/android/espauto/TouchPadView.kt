package com.android.espauto

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.core.graphics.withClip
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TouchPadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnTouchPadMoveListener {
        fun onMove(x: Int, y: Int)
        fun onStop()
    }

    var listener: OnTouchPadMoveListener? = null

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f

    private var touchX = 0f
    private var touchY = 0f
    private var isTouching = false

    private val touchPaint = Paint().apply {
        color = TypedValue().let {
            context.theme.resolveAttribute(android.R.attr.colorControlHighlight, it, true)
            it.data
        }
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val touchPointRadius = 40f

    private val clipPath = Path()
    private val cornerRadius = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 20f, context.resources.displayMetrics
    )

    init {
        inflate(context, R.layout.touch_pad_layout, this)
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = minOf(w, h) / 2f * 0.8f
        clipPath.reset()
        clipPath.addRoundRect(
            RectF(0f, 0f, w.toFloat(), h.toFloat()),
            cornerRadius,
            cornerRadius,
            Path.Direction.CW
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                isTouching = true
                invalidate()

                var dx = event.x - centerX
                var dy = event.y - centerY
                val dist = sqrt(dx * dx + dy * dy)

                if (dist > baseRadius) {
                    val angle = atan2(dy, dx)
                    dx = cos(angle) * baseRadius
                    dy = sin(angle) * baseRadius
                }

                val x = (dx / baseRadius * 100).toInt()
                val y = (dy / baseRadius * 100).toInt()
                listener?.onMove(x, y)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                invalidate()
                listener?.onStop()
                if (event.action == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener?.onStop()
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.withClip(clipPath) {
            super.dispatchDraw(canvas)

            if (isTouching) {
                canvas.drawCircle(touchX, touchY, touchPointRadius, touchPaint)
            }
        }
    }
}
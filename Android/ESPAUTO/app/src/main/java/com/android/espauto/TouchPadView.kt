/*
 * ESPAUTO
 * Copyright (c) 2026 honoz
 * Licensed under the MIT License.
 */

package com.android.espauto

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
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

    init {
        // 利用布局充气器（Inflate）动态加载控制手柄的外观 xml 结构并将其挂载在当前 View 容器树下
        inflate(context, R.layout.touch_pad_layout, this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 测算物理尺寸：在测量完成后确定手柄的中心参考零点，并根据宽高的极小值分配 80% 作为合法的操纵区域半径
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = minOf(w, h) / 2f * 0.8f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // 计算当前触控点相对于物理圆心的极坐标增量 dx, dy
                var dx = event.x - centerX
                var dy = event.y - centerY
                // 基于勾股定理求出当前触控向量的绝对欧氏距离
                val dist = sqrt(dx * dx + dy * dy)

                // 物理边界锁死：如果拖拽矢量超出了最大规定半径，使用反三角函数提取方位角，并将坐标强行解算到圆周边界上
                if (dist > baseRadius) {
                    val angle = atan2(dy, dx)
                    dx = cos(angle) * baseRadius
                    dy = sin(angle) * baseRadius
                }

                // 标定标称化映射：将像素位移转换为 [-100, 100] 的百分比线性区间，对齐下位机双向马达电调的控制协议
                val x = (dx / baseRadius * 100).toInt()
                val y = (dy / baseRadius * 100).toInt()
                listener?.onMove(x, y)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 当离手或事件流由于外在干扰被截断时，对外发出刹车挂起通知
                listener?.onStop()
            }
        }
        return true
    }
}
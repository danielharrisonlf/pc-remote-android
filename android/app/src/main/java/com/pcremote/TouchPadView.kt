package com.pcremote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Sends only relative movement while the current PC session has armed input. */
class TouchPadView(context: Context, private val client: RemoteController, private val onClickResult: (String?) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var tracking = false

    init { isClickable = true; isFocusable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        paint.color = if (dark) 0xff37465e.toInt() else 0xffe4eafa.toInt()
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, 24f, 24f, paint)
        paint.color = if (dark) 0xffacc2fa.toInt() else 0xff546b9e.toInt()
        paint.textSize = 16f * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Deslize para mover · toque para clicar", width / 2f, height / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !client.view.inputArmed) {
            if (tracking) cancel()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true; moved = false
                startX = event.x; startY = event.y; lastX = event.x; lastY = event.y
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (abs(event.x - startX) > slop || abs(event.y - startY) > slop) moved = true
                val dx = event.x - lastX; val dy = event.y - lastY
                lastX = event.x; lastY = event.y
                if (moved) client.movePointer(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                if (!moved && abs(event.x - startX) <= slop && abs(event.y - startY) <= slop) performClick()
                cancel()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { cancel(); return true }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (isEnabled && client.view.inputArmed) client.execute(RemoteAction.PointerClick("left", 1), onClickResult)
        return true
    }

    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }

    private fun cancel() {
        tracking = false
        client.cancelPointerMotion()
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}

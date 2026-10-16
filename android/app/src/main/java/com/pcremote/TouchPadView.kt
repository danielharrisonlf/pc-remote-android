package com.pcremote

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Full-height touchpad. It keeps the gesture until the finger lifts (parents
 * cannot intercept), so dragging never scrolls the page.
 *
 * One finger moves the pointer with acceleration and a short tap clicks; two
 * fingers scroll in the natural direction and a two-finger tap opens the
 * context menu (right click).
 */
internal class TouchPadView(
  private val ui: Ui,
  private val client: RemoteController,
  private val active: () -> Boolean,
  private val onTap: (button: String) -> Unit,
) : View(ui.context) {
  private val density = resources.displayMetrics.density
  private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
  private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.palette.padDot }
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dp(1.5f); color = ui.palette.accent }
  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.palette.ripple }
  private val fillAlpha = ui.palette.ripple ushr 24
  private var tracking = false
  private var lastX = 0f
  private var lastY = 0f
  private var lastTime = 0L
  private var startTime = 0L
  private var travel = 0f
  private var maxPointers = 1
  private var scrollRemainder = 0f
  private var indicatorX = 0f
  private var indicatorY = 0f
  private var indicator = 0f
  private var fade: ValueAnimator? = null

  init {
    isClickable = true
    isFocusable = true
    contentDescription = "Touchpad. Um dedo move o cursor e toque clica. Dois dedos rolam; toque com dois dedos abre o menu."
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val step = ui.dp(22f)
    val radius = ui.dp(1.3f)
    var y = step / 2
    while (y < height) {
      var x = step / 2
      while (x < width) { canvas.drawCircle(x, y, radius, dotPaint); x += step }
      y += step
    }
    if (indicator > 0f) {
      val r = ui.dp(32f) * (0.6f + 0.4f * indicator)
      fillPaint.alpha = (fillAlpha * indicator).toInt()
      ringPaint.alpha = (indicator * 255).toInt()
      canvas.drawCircle(indicatorX, indicatorY, r, fillPaint)
      canvas.drawCircle(indicatorX, indicatorY, r, ringPaint)
    }
  }

  @SuppressLint("ClickableViewAccessibility") // finish() calls performClick() for taps.
  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (!isEnabled || !active()) {
      if (tracking) finish(tap = false)
      return false
    }
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        tracking = true
        travel = 0f; maxPointers = 1; scrollRemainder = 0f
        startTime = event.eventTime; lastTime = event.eventTime
        anchor(event, -1)
        parent?.requestDisallowInterceptTouchEvent(true)
        showIndicator(event.x, event.y)
        return true
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        maxPointers = max(maxPointers, event.pointerCount)
        anchor(event, -1)
        return true
      }
      MotionEvent.ACTION_POINTER_UP -> {
        // The centroid jumps when a finger lifts; re-anchor instead of moving.
        anchor(event, event.actionIndex)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        if (!tracking) return false
        val (x, y) = centroid(event, -1)
        val dx = x - lastX
        val dy = y - lastY
        val dt = max(1L, event.eventTime - lastTime)
        lastX = x; lastY = y; lastTime = event.eventTime
        travel += abs(dx) + abs(dy)
        indicatorX = event.getX(0); indicatorY = event.getY(0); invalidate()
        if (event.pointerCount >= 2) {
          // Natural direction, like the phone itself: fingers down reveal content above.
          scrollRemainder += dy / density
          val steps = (scrollRemainder / SCROLL_STEP_DP).toInt()
          if (steps != 0) { scrollRemainder -= steps * SCROLL_STEP_DP; client.scroll(steps) }
        } else if (travel > slop) {
          val dxDp = dx / density
          val dyDp = dy / density
          val speed = hypot(dxDp, dyDp) / dt
          val gain = BASE_GAIN + min(MAX_EXTRA_GAIN, speed * ACCELERATION)
          client.movePointer(dxDp * gain, dyDp * gain)
        }
        return true
      }
      MotionEvent.ACTION_UP -> {
        val tap = tracking && event.eventTime - startTime < TAP_MS && travel < slop * 2
        finish(tap)
        return true
      }
      MotionEvent.ACTION_CANCEL -> { finish(tap = false); return true }
    }
    return false
  }

  private fun anchor(event: MotionEvent, excluded: Int) {
    val (x, y) = centroid(event, excluded)
    lastX = x; lastY = y
  }

  private fun centroid(event: MotionEvent, excluded: Int): Pair<Float, Float> {
    var sumX = 0f; var sumY = 0f; var count = 0
    for (i in 0 until event.pointerCount) {
      if (i == excluded) continue
      sumX += event.getX(i); sumY += event.getY(i); count++
    }
    return if (count == 0) event.x to event.y else (sumX / count) to (sumY / count)
  }

  private fun finish(tap: Boolean) {
    val pointers = maxPointers
    tracking = false
    client.cancelPointerMotion()
    parent?.requestDisallowInterceptTouchEvent(false)
    hideIndicator()
    if (tap) {
      performClick()
      onTap(if (pointers >= 2) "right" else "left")
    }
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  private fun showIndicator(x: Float, y: Float) {
    fade?.cancel()
    indicatorX = x; indicatorY = y
    animateIndicator(1f, 110)
  }

  private fun hideIndicator() = animateIndicator(0f, 180)

  private fun animateIndicator(target: Float, duration: Long) {
    fade?.cancel()
    fade = ValueAnimator.ofFloat(indicator, target).apply {
      this.duration = duration
      addUpdateListener { indicator = it.animatedValue as Float; invalidate() }
      start()
    }
  }

  override fun onDetachedFromWindow() {
    fade?.cancel()
    if (tracking) finish(tap = false)
    super.onDetachedFromWindow()
  }

  companion object {
    /** Slow drags stay precise; fast flicks cross the screen. */
    const val BASE_GAIN = 1.4f
    const val ACCELERATION = 2.2f
    const val MAX_EXTRA_GAIN = 2.6f
    const val SCROLL_STEP_DP = 22f
    const val TAP_MS = 260L
  }
}

/** Edge strip that scrolls like a scrollbar: drag down to move the page down. */
internal class ScrollStripView(
  private val ui: Ui,
  private val client: RemoteController,
  private val active: () -> Boolean,
  private val onTap: () -> Unit,
) : View(ui.context) {
  private val density = resources.displayMetrics.density
  private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ui.palette.borderStrong; strokeWidth = ui.dp(2f); strokeCap = Paint.Cap.ROUND }
  private val up = ui.drawable(R.drawable.ic_chevron_up, ui.palette.textTertiary, 18)
  private val down = ui.drawable(R.drawable.ic_chevron_down, ui.palette.textTertiary, 18)
  private var lastY = 0f
  private var startY = 0f
  private var startTime = 0L
  private var travel = 0f
  private var remainder = 0f
  private var pressedState = false

  init {
    isClickable = true
    contentDescription = "Faixa de rolagem. Arraste para rolar a página no PC."
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    canvas.drawColor(if (pressedState) ui.palette.accentSoft else ui.palette.surfaceSunken)
    val cx = width / 2f
    val iconSize = ui.dp(18)
    up.setBounds((cx - iconSize / 2).toInt(), ui.dp(14), (cx + iconSize / 2).toInt(), ui.dp(14) + iconSize)
    up.draw(canvas)
    down.setBounds((cx - iconSize / 2).toInt(), height - ui.dp(14) - iconSize, (cx + iconSize / 2).toInt(), height - ui.dp(14))
    down.draw(canvas)
    val half = ui.dp(8f)
    for (i in -1..1) {
      val y = height / 2f + i * ui.dp(6f)
      canvas.drawLine(cx - half, y, cx + half, y, linePaint)
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (!isEnabled || !active()) return false
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        parent?.requestDisallowInterceptTouchEvent(true)
        startY = event.y; lastY = event.y; startTime = event.eventTime; travel = 0f; remainder = 0f
        pressedState = true; invalidate()
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val dy = event.y - lastY
        lastY = event.y
        travel += abs(dy)
        remainder += dy / density
        val steps = (remainder / STEP_DP).toInt()
        if (steps != 0) { remainder -= steps * STEP_DP; client.scroll(-steps) }
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        pressedState = false; invalidate()
        parent?.requestDisallowInterceptTouchEvent(false)
        if (event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - startTime < 260 && travel < ui.dp(10f)) {
          // Tap the upper half to scroll up, the lower half to scroll down.
          performClick()
          onTap()
          client.scroll(if (startY < height / 2f) 3 else -3)
        }
        return true
      }
    }
    return false
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }

  companion object {
    const val STEP_DP = 16f
  }
}

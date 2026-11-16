package com.mio.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// ---------- Una pantalla del escritorio: cuadrícula de celdas ----------
class CellLayout(c: Context, val cols: Int, val rows: Int) : ViewGroup(c) {
    class LP(val cx: Int, val cy: Int, val w: Int, val h: Int) : ViewGroup.LayoutParams(0, 0)

    var cw = 1; private set
    var ch = 1; private set
    var hintOk = true
    var hint: Rect? = null
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        cw = maxOf(1, (w - paddingLeft - paddingRight) / cols)
        ch = maxOf(1, (h - paddingTop - paddingBottom) / rows)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as LP
            v.measure(MeasureSpec.makeMeasureSpec(cw * lp.w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch * lp.h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as LP
            val x = paddingLeft + lp.cx * cw
            val y = paddingTop + lp.cy * ch
            v.layout(x, y, x + cw * lp.w, y + ch * lp.h)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LP
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LP(0, 0, 1, 1)
    override fun shouldDelayChildPressedState() = false

    fun cellX(localX: Float) = floor((localX - paddingLeft) / cw).toInt().coerceIn(0, cols - 1)
    fun cellY(localY: Float) = floor((localY - paddingTop) / ch).toInt().coerceIn(0, rows - 1)

    fun childAt(localX: Float, localY: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i)
            if (v.visibility == View.VISIBLE && localX >= v.left && localX < v.right &&
                localY >= v.top && localY < v.bottom) return v
        }
        return null
    }

    // Marco azul Holo donde va a caer lo que se está arrastrando
    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val h = hint ?: return
        val d = resources.displayMetrics.density
        val r = RectF(
            paddingLeft + h.left * cw + 3 * d, paddingTop + h.top * ch + 3 * d,
            paddingLeft + h.right * cw - 3 * d, paddingTop + h.bottom * ch - 3 * d
        )
        paint.style = Paint.Style.FILL
        paint.color = if (hintOk) Color.argb(45, 51, 181, 229) else Color.argb(45, 255, 68, 68)
        c.drawRoundRect(r, 4 * d, 4 * d, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * d
        paint.color = if (hintOk) HOLO_BLUE else HOLO_RED
        c.drawRoundRect(r, 4 * d, 4 * d, paint)
    }
}

// ---------- Contenedor de pantallas que se desliza a los lados ----------
class PagedView(c: Context) : ViewGroup(c) {
    var current = 0; private set
    var onScroll: ((Float) -> Unit)? = null
    private val scroller = OverScroller(c, DecelerateInterpolator(1.6f))
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null

    val pageCount get() = childCount
    fun page(i: Int) = getChildAt(i) as CellLayout

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        val cws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val chs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cws, chs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, h)
        if (scroller.isFinished && !dragging) {
            current = current.coerceIn(0, maxOf(0, childCount - 1))
            scrollTo(current * w, 0)
        }
    }

    private fun maxScroll() = maxOf(0, (childCount - 1) * width)

    private fun track(ev: MotionEvent) {
        if (vt == null) vt = VelocityTracker.obtain()
        vt?.addMovement(ev)
    }

    private fun recycle() { vt?.recycle(); vt = null }

    private fun checkStart(ev: MotionEvent): Boolean {
        if (!dragging && childCount > 1) {
            val dx = ev.x - downX
            val dy = ev.y - downY
            if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                dragging = true
                lastX = ev.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return dragging
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragging = !scroller.isFinished
                if (dragging) scroller.abortAnimation()
            }
            MotionEvent.ACTION_MOVE -> checkStart(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) recycle()
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                if (!scroller.isFinished) { scroller.abortAnimation(); dragging = true }
            }
            MotionEvent.ACTION_MOVE -> if (checkStart(ev)) {
                val nx = (scrollX + (lastX - ev.x)).toInt().coerceIn(0, maxScroll())
                scrollTo(nx, 0)
                lastX = ev.x
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vx = vt?.xVelocity ?: 0f
                    val pos = if (width > 0) scrollX.toFloat() / width else 0f
                    val target = when {
                        vx < -600 -> ceil(pos).toInt()
                        vx > 600 -> floor(pos).toInt()
                        else -> pos.roundToInt()
                    }
                    snapTo(target)
                }
                dragging = false
                recycle()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) snapTo(if (width > 0) (scrollX.toFloat() / width).roundToInt() else 0)
                dragging = false
                recycle()
            }
        }
        return true
    }

    fun snapTo(p: Int, animate: Boolean = true) {
        current = p.coerceIn(0, maxOf(0, childCount - 1))
        if (!animate || width == 0) {
            scroller.abortAnimation()
            scrollTo(current * width, 0)
            return
        }
        scroller.startScroll(scrollX, 0, current * width - scrollX, 0, 320)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScroll?.invoke(if (width > 0) l.toFloat() / width else 0f)
    }
}

// ---------- Indicador de pantallas: rayitas, la actual en azul Holo ----------
class PageIndicator(c: Context) : View(c) {
    var count = 1
        set(v) { field = v; invalidate() }
    var pos = 0f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(cv: Canvas) {
        if (count <= 1) return
        val d = resources.displayMetrics.density
        val bw = 22 * d
        val gap = 6 * d
        val bh = 3 * d
        val total = count * bw + (count - 1) * gap
        val x0 = (width - total) / 2
        val y = (height - bh) / 2
        p.color = Color.argb(90, 255, 255, 255)
        for (i in 0 until count) {
            val x = x0 + i * (bw + gap)
            cv.drawRect(x, y, x + bw, y + bh, p)
        }
        p.color = HOLO_BLUE
        val hx = x0 + pos * (bw + gap)
        cv.drawRect(hx, y - 0.5f * d, hx + bw, y + bh + 0.5f * d, p)
    }
}

// ---------- Widgets: detecta mantener presionado aunque el widget use los toques ----------
class LauncherWidgetView(c: Context) : AppWidgetHostView(c) {
    var onLongPress: ((View) -> Unit)? = null
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val check = Runnable { longPressed = true; onLongPress?.invoke(this) }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x; downY = ev.y
                postDelayed(check, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(check)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(check)
        }
        return longPressed
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) return true
        return super.onTouchEvent(ev)
    }
}

class LauncherWidgetHost(c: Context, id: Int) : AppWidgetHost(c, id) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LauncherWidgetView(context)
}

// ---------- Botón circular de "todas las apps", estilo ICS ----------
class AllAppsButton(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) * 0.36f
        p.style = Paint.Style.FILL; p.color = Color.argb(200, 40, 40, 40)
        cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.08f
        p.color = if (isPressed) HOLO_BLUE else Color.argb(220, 255, 255, 255)
        cv.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        val gap = r * 0.36f; val dot = r * 0.09f
        for (i in -1..1) for (j in -1..1) cv.drawCircle(cx + i * gap, cy + j * gap, dot, p)
    }
}

// ---------- Pestaña estilo Holo: texto con raya azul de 4dp abajo ----------
class TabView(c: Context) : android.widget.TextView(c) {
    var lineColor = Color.TRANSPARENT
        set(v) { field = v; invalidate() }
    private val p = Paint()

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        p.color = lineColor
        val h = 4 * resources.displayMetrics.density
        cv.drawRect(0f, height - h, width.toFloat(), height.toFloat(), p)
    }
}

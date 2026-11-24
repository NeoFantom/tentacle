package dev.tentacle

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView

/** Keeps the main screen's reading position when content above it changes size. */
internal class AnchoredScrollView(context: Context) : ScrollView(context) {
    private data class Anchor(val view: View, val top: Int)
    private data class Viewport(
        val width: Int, val height: Int,
        val left: Int, val top: Int, val right: Int, val bottom: Int,
    )

    private var touchedView: View? = null
    private var touchedAnchor: Anchor? = null
    private var cardAnchors = emptyList<Anchor>()
    private var recordedScrollY = 0
    private var recordedViewport: Viewport? = null
    private var layingOut = false
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        correctAnchor()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(beforeDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(beforeDraw)
        touchedView = null
        touchedAnchor = null
        cardAnchors = emptyList()
        recordedViewport = null
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchedView = (getChildAt(0) as? ViewGroup)?.let {
                deepestHit(it, event.x.toInt(), event.y.toInt())?.takeUnless { hit -> hit === it }
            }
            recordAnchors()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // Defer layout-time scrolling until we can distinguish a range clamp
        // from a focus/restore scroll. Ordinary scrolling rebases immediately.
        if (!layingOut) recordAnchors()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val previousScrollY = scrollY
        layingOut = true
        try {
            super.onLayout(changed, l, t, r, b)
        } finally {
            layingOut = false
        }
        // A range clamp must not erase the old anchor, but focus reveals and
        // restored positions are intentional navigation and become the new baseline.
        if (scrollY != previousScrollY && scrollY != previousScrollY.coerceIn(0, maxScrollY())) {
            recordAnchors()
        }
    }

    private fun viewport() = Viewport(width, height, paddingLeft, paddingTop, paddingRight, paddingBottom)

    private fun correctAnchor() {
        // Resizing (including IME and insets) belongs to the platform's focus and
        // saved-position handling. Only compensate changes within the same viewport.
        if (recordedViewport == viewport() && recordedScrollY != 0) {
            val anchor = touchedAnchor?.takeIf { bounds(it.view) != null }
                ?: cardAnchors.firstOrNull { bounds(it.view) != null }
            if (anchor != null) {
                // A newly inserted card may have pushed the anchor outside the viewport.
                // Test attachment/visibility now, and viewport intersection after correction.
                val newTop = bounds(anchor.view)!!.top
                // Leave normal overscroll alone when the anchor has not moved.
                if (newTop != anchor.top) {
                    val target = anchoredScrollY(anchor.top, newTop, scrollY, maxScrollY(), wasAtTop = false)
                    if (target != scrollY) scrollTo(scrollX, target)
                }
            }
        }
        recordAnchors()
    }

    private fun maxScrollY(): Int {
        val column = getChildAt(0) ?: return 0
        val margins = column.layoutParams as? ViewGroup.MarginLayoutParams
        return (column.height + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0) -
            (height - paddingTop - paddingBottom)).coerceAtLeast(0)
    }

    private fun recordAnchors() {
        recordedScrollY = scrollY
        recordedViewport = viewport()
        val column = getChildAt(0) as? ViewGroup
        touchedAnchor = touchedView?.let { view ->
            bounds(view)?.takeIf(::intersectsViewport)?.let { Anchor(view, it.top) }
        }
        if (touchedAnchor == null) touchedView = null
        // Keep visible fallbacks from the same layout, so a removed or hidden
        // touched view can fall back to a card without mixing old/new coordinates.
        cardAnchors = if (column == null) emptyList() else (0 until column.childCount).mapNotNull { index ->
            val view = column.getChildAt(index)
            bounds(view)?.takeIf(::intersectsViewport)?.let { Anchor(view, it.top) }
        }
    }

    private fun intersectsViewport(rect: Rect): Boolean =
        rect.bottom > paddingTop && rect.top < height - paddingBottom &&
            rect.right > paddingLeft && rect.left < width - paddingRight

    private fun bounds(view: View): Rect? {
        if (!view.isAttachedToWindow || !view.isShown) return null
        val column = getChildAt(0) ?: return null
        var ancestor: View? = view
        while (ancestor !== column) ancestor = ancestor?.parent as? View ?: return null
        return Rect().also {
            view.getDrawingRect(it)
            offsetDescendantRectToMyCoords(view, it)
            it.offset(-scrollX, -scrollY)
        }
    }

    private fun deepestHit(view: View, x: Int, y: Int): View? {
        if (bounds(view)?.contains(x, y) != true) return null
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                deepestHit(view.getChildAt(index), x, y)?.let { return it }
            }
        }
        return view
    }
}

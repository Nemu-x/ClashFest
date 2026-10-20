package com.github.kr328.clash.design.dialog

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Drag-to-dismiss for the node picker sheet.
 *
 * With `skipCollapsed` Material's `BottomSheetBehavior.shouldHide()` always answers true, so any
 * downward release of a nested scroll hid the sheet: a few pixels of pull on a list already at its
 * top dismissed it. Users kept closing the picker by accident while browsing nodes.
 *
 * Here the behavior's own dragging is turned off and this helper moves the sheet instead:
 * - the list scrolls first; only once it is at its top does a downward drag pull the sheet;
 * - the pull has resistance, so the sheet visibly "gives" before it goes;
 * - release past a third of the sheet's height, or a fast downward fling, dismisses; anything
 *   less springs back;
 * - the handle and header area (everything that is not a control) drag the sheet directly.
 */
class SheetDragToDismiss private constructor(
    private val dialog: BottomSheetDialog,
    private val sheet: View,
) {
    private val slop = ViewConfiguration.get(sheet.context).scaledTouchSlop
    private val density = sheet.resources.displayMetrics.density
    private var startY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    private fun begin(e: MotionEvent) {
        startY = e.rawY
        dragging = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
        sheet.animate().cancel()
    }

    private fun move(e: MotionEvent) {
        velocity?.addMovement(e)
        val pulled = (e.rawY - startY - slop).coerceAtLeast(0f)
        sheet.translationY = pulled * RESISTANCE
    }

    private fun end(cancelled: Boolean) {
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        val vy = tracker?.yVelocity ?: 0f
        tracker?.recycle()
        velocity = null
        dragging = false

        val offset = sheet.translationY
        val fling = vy > FLING_DP_PER_S * density && offset > MIN_FLING_OFFSET_DP * density
        if (!cancelled && (offset > sheet.height * DISMISS_FRACTION || fling)) {
            sheet.animate()
                .translationY(sheet.height.toFloat())
                .setDuration(180)
                .withEndAction { dialog.dismiss() }
                .start()
        } else if (offset > 0f) {
            sheet.animate()
                .translationY(0f)
                .setInterpolator(OvershootInterpolator(1.2f))
                .setDuration(260)
                .start()
        }
    }

    /** Pulls on the list hand over to the sheet only once the list cannot scroll further up. */
    private val listListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(e)
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(e)
                    if (!dragging && e.rawY - startY > slop && !rv.canScrollVertically(-1)) {
                        dragging = true
                        rv.stopScroll()
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> move(e)
                MotionEvent.ACTION_UP -> end(cancelled = false)
                MotionEvent.ACTION_CANCEL -> end(cancelled = true)
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    @SuppressLint("ClickableViewAccessibility")
    private val headerListener = View.OnTouchListener { _, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                begin(e)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && e.rawY - startY > slop) dragging = true
                if (dragging) move(e)
                true
            }
            MotionEvent.ACTION_UP -> {
                end(cancelled = !dragging)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                end(cancelled = true)
                true
            }
            else -> false
        }
    }

    companion object {
        private const val RESISTANCE = 0.6f
        private const val DISMISS_FRACTION = 0.33f
        private const val FLING_DP_PER_S = 1400f
        private const val MIN_FLING_OFFSET_DP = 24f

        /**
         * Call after `dialog.show()`. [list] is the sheet's scrolling list; touches that start on
         * any other non-interactive part of the sheet (handle, title) drag the sheet directly.
         */
        @SuppressLint("ClickableViewAccessibility")
        fun attach(dialog: BottomSheetDialog, list: RecyclerView) {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
            dialog.behavior.isDraggable = false
            val helper = SheetDragToDismiss(dialog, sheet)
            list.addOnItemTouchListener(helper.listListener)
            sheet.setOnTouchListener(helper.headerListener)
        }
    }
}

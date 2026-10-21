package com.github.kr328.clash.design.dialog

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.motion.dismissSheetAfterPull
import com.github.kr328.clash.design.motion.expressiveSpatial
import com.github.kr328.clash.design.motion.motionEnabled
import com.google.android.material.bottomsheet.BottomSheetDialog

/** The list scrolls first; a deliberate pull at its top moves the sheet with resistance. */
internal class SheetDragToDismiss private constructor(private val dialog: BottomSheetDialog,
    private val sheet: View, private val list: RecyclerView) {
    private val slop = ViewConfiguration.get(sheet.context).scaledTouchSlop
    private val density = sheet.resources.displayMetrics.density
    private var startY = 0f
    private var dragging = false
    private var ignoreGesture = false
    private var velocity: VelocityTracker? = null

    private fun begin(event: MotionEvent) {
        startY = event.rawY - sheet.translationY / .6f
        dragging = false
        ignoreGesture = false
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
        sheet.animate().cancel()
    }

    private fun move(event: MotionEvent) {
        velocity?.addMovement(event)
        sheet.translationY = (event.rawY - startY - slop).coerceAtLeast(0f) * .6f
    }

    private fun end(cancelled: Boolean) {
        velocity?.computeCurrentVelocity(1000)
        val dismiss = dismissSheetAfterPull(sheet.translationY, sheet.height,
            velocity?.yVelocity ?: 0f, density, cancelled)
        velocity?.recycle()
        velocity = null
        dragging = false
        if (!motionEnabled()) {
            if (dismiss) dialog.dismiss() else sheet.translationY = 0f
        } else if (dismiss) {
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(180L)
                .withEndAction { dialog.dismiss() }.start()
        } else if (sheet.translationY > 0f) {
            sheet.animate().translationY(0f).setInterpolator(expressiveSpatial).setDuration(420L).start()
        }
    }

    private val listListener = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event)
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    if (rv.canScrollVertically(-1)) startY = event.rawY
                    else if (!dragging && event.rawY - startY > slop) {
                        dragging = true
                        rv.stopScroll()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) end(true)
            }
            return false
        }
        override fun onTouchEvent(rv: RecyclerView, event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) move(event)
                MotionEvent.ACTION_UP -> { velocity?.addMovement(event); end(false) }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { ignoreGesture = true; end(true) }
            }
        }
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    }

    @SuppressLint("ClickableViewAccessibility")
    private val headerListener = View.OnTouchListener { view, event ->
        if (ignoreGesture && event.actionMasked != MotionEvent.ACTION_DOWN) return@OnTouchListener true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { begin(event); true }
            MotionEvent.ACTION_MOVE -> {
                if (event.rawY - startY > slop) dragging = true
                if (dragging) move(event)
                true
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                if (!dragging) view.performClick()
                end(!dragging)
                true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { ignoreGesture = true; end(true); true }
            else -> false
        }
    }

    fun dispose() {
        sheet.animate().cancel()
        velocity?.recycle()
        velocity = null
        list.removeOnItemTouchListener(listListener)
        sheet.setOnTouchListener(null)
    }

    companion object {
        fun attach(dialog: BottomSheetDialog, list: RecyclerView): SheetDragToDismiss? {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return null
            dialog.behavior.isDraggable = false
            return SheetDragToDismiss(dialog, sheet, list).also {
                list.addOnItemTouchListener(it.listListener)
                sheet.setOnTouchListener(it.headerListener)
            }
        }
    }
}

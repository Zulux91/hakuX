package com.rfandango.haku_x

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class PauseMenuOverlay(context: Context) : FrameLayout(context) {

    var onExitEmulation: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onDiagCapture: ((Int) -> Unit)? = null
    var isDebugToolsEnabled: (() -> Boolean)? = null

    private val card: LinearLayout
    private var debugSection: LinearLayout? = null
    private var debugButton: Button? = null
    private var debugExpanded = false

    init {
        setBackgroundColor(Color.argb(160, 0, 0, 0))
        visibility = View.GONE
        isClickable = true
        isFocusable = true

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val bg = GradientDrawable().apply {
                setColor(Color.argb(230, 30, 30, 30))
                cornerRadius = dpToPx(16f)
            }
            background = bg
            setPadding(dpToPx(32f).toInt(), dpToPx(24f).toInt(), dpToPx(32f).toInt(), dpToPx(24f).toInt())
            elevation = dpToPx(8f)
        }

        val exitButton = createMenuButton(context, "Exit").apply {
            val bg = (background as GradientDrawable)
            bg.setColor(Color.argb(230, 90, 90, 90))
            setOnClickListener { onExitEmulation?.invoke() }
        }
        card.addView(exitButton, createButtonParams())

        val cardParams = LayoutParams(
            dpToPx(280f).toInt(),
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        addView(card, cardParams)

        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && visibility == View.VISIBLE) {
                onDismiss?.invoke()
                true
            } else {
                true
            }
        }
    }

    fun show() {
        rebuildDebugSection()
        debugExpanded = false
        visibility = View.VISIBLE
    }

    fun dismiss() {
        visibility = View.GONE
    }

    fun isShowing(): Boolean = visibility == View.VISIBLE

    private fun rebuildDebugSection() {
        // Remove old debug UI
        debugSection?.let { card.removeView(it) }
        debugSection = null
        debugButton?.let { card.removeView(it) }
        debugButton = null

        val enabled = isDebugToolsEnabled?.invoke() ?: false
        if (!enabled) return

        // Add "Debug" button
        debugButton = createMenuButton(context, "\uD83D\uDD27 Debug Capture").apply {
            val bg = (background as GradientDrawable)
            bg.setColor(Color.argb(200, 50, 80, 120))
            setOnClickListener { toggleDebugSection() }
        }
        card.addView(debugButton, createButtonParams())

        // Prepare expandable section (hidden initially)
        debugSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            val capture1 = createCaptureButton(context, "Capture 1 Frame", 1)
            addView(capture1, createButtonParams())

            val capture5 = createCaptureButton(context, "Capture 5 Frames", 5)
            addView(capture5, createButtonParams())

            val capture10 = createCaptureButton(context, "Capture 10 Frames", 10)
            addView(capture10, createButtonParams())
        }
        card.addView(debugSection, createButtonParams())
    }

    private fun toggleDebugSection() {
        debugExpanded = !debugExpanded
        debugSection?.visibility = if (debugExpanded) View.VISIBLE else View.GONE
    }

    private fun createCaptureButton(context: Context, label: String, frames: Int): Button {
        return createMenuButton(context, label).apply {
            val bg = (background as GradientDrawable)
            bg.setColor(Color.argb(200, 40, 100, 70))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener {
                android.util.Log.i("xemu-diag", "capture: dismissing first (resume), then setting $frames frame pending")
                onDismiss?.invoke()
                // Post with delay to let the game produce a few frames first
                postDelayed({
                    android.util.Log.i("xemu-diag", "capture: now setting $frames frame pending")
                    onDiagCapture?.invoke(frames)
                }, 2000)
            }
        }
    }

    private fun createMenuButton(context: Context, label: String): Button {
        return Button(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            val bg = GradientDrawable().apply {
                setColor(Color.argb(200, 70, 70, 70))
                cornerRadius = dpToPx(8f)
            }
            background = bg
            setPadding(dpToPx(16f).toInt(), dpToPx(14f).toInt(), dpToPx(16f).toInt(), dpToPx(14f).toInt())
            stateListAnimator = null
        }
    }

    private fun createButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(12f).toInt() }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
    }
}

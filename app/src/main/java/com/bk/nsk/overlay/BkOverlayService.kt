package com.bk.nsk.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

class BkOverlayService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var badgeLayout: LinearLayout? = null
    private var tvPriceRange: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { badgeLayout?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createFloatingBadge()
    }

    private fun createFloatingBadge() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        badgeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 18, 36, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6141414"))
                cornerRadius = 32f
                setStroke(3, Color.parseColor("#FFD700"))
            }
            visibility = View.GONE
        }

        val tvTitle = TextView(this).apply {
            text = "БК"
            setTextColor(Color.parseColor("#FFD700"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        tvPriceRange = TextView(this).apply {
            text = "0-0"
            setTextColor(Color.parseColor("#FF2A2A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        badgeLayout?.addView(tvTitle)
        badgeLayout?.addView(tvPriceRange)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 180
        }

        badgeLayout?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager?.updateViewLayout(badgeLayout, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager?.addView(badgeLayout, params)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val rootNode = rootInActiveWindow ?: return
        val screenText = StringBuilder()
        extractText(rootNode, screenText)
        val fullText = screenText.toString()

        if (fullText.contains("вручен", ignoreCase = true) && fullText.contains("Принять", ignoreCase = true)) {
            val priceRange = calculateNovosibirskOrder(fullText)
            showBadge(priceRange)
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (!node.text.isNullOrEmpty()) {
            sb.append(node.text).append(" | ")
        }
        for (i in 0 until node.childCount) {
            extractText(node.getChild(i), sb)
        }
    }

    private fun calculateNovosibirskOrder(text: String): String {
        var totalMin = 15
        val hmRegex = Regex("""~\s*(?:(\d+)\s*ч\s*)?(\d+)\s*мин""", RegexOption.IGNORE_CASE)
        hmRegex.find(text)?.let { match ->
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 15
            totalMin = h * 60 + m
        }

        var pickupMin = 5
        val allMinMatches = Regex("""(?:<\s*)?(\d+)\s*мин""", RegexOption.IGNORE_CASE).findAll(text).toList()
        if (allMinMatches.size > 1) {
            pickupMin = allMinMatches.first().groupValues[1].toIntOrNull() ?: 5
        }

        val points = Regex("""(\d+)\s*вручен""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        var surge = 0
        var door = 0
        val hasDoorText = text.contains("двер", ignoreCase = true)
        val plusMatches = Regex("""\+\s*(\d+)\s*[₽Рp]""", RegexOption.IGNORE_CASE).findAll(text)

        for (m in plusMatches) {
            val value = m.groupValues[1].toIntOrNull() ?: 0
            if (hasDoorText && door == 0 && (value == 48 || value == 96 || value == 144 || value == 192)) {
                door = value
            } else if (value > surge) {
                surge = value
            }
        }

        val driveMin = max(3.0, totalMin - pickupMin - (points - 1) * 6.5)
        val baseCost = max(105.0, 37.0 + (driveMin * 9.5))
        val exactGross = baseCost + surge + door

        val minEst = ((exactGross * 0.95) / 10.0).roundToInt() * 10
        val maxEst = ((exactGross * 1.05) / 10.0).roundToInt() * 10

        return "$minEst-$maxEst"
    }

    private fun showBadge(rangeText: String) {
        handler.post {
            tvPriceRange?.text = rangeText
            badgeLayout?.visibility = View.VISIBLE
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, 9500)
        }
    }

    override fun onInterrupt() {}
}

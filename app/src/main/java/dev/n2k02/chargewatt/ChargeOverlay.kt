package dev.n2k02.chargewatt

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * An optional, user-visible floating power meter, similar to NetSpeed.
 * The display is not a service and owns no polling loop; sampling is performed
 * by WidgetPollingService, which also services the home widget and posts its
 * required ongoing foreground-service notification.
 */
@SuppressLint("StaticFieldLeak")
object ChargeOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var primary: TextView? = null
    private var secondary: TextView? = null
    private var voltage: TextView? = null

    @Volatile var isRunning = false
        private set

    fun start(context: Context): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { start(context.applicationContext) }
            return false
        }
        val app = context.applicationContext
        if (!ChargeWattSettings.overlayEnabled(app) || !Settings.canDrawOverlays(app)) return false
        if (isRunning) return true
        val density = app.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun text(size: Float, color: Int): TextView = TextView(app).apply {
            setTextColor(color)
            textSize = size
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
        }
        val accent = Color.rgb(91, 241, 156)
        val background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(245, 28, 38, 41), Color.argb(247, 16, 24, 28))
        ).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.argb(180, 91, 241, 156))
        }
        val card = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(7), dp(9), dp(7))
            this.background = background
            contentDescription = "ChargeWatt floating battery meter"
        }
        val statusView = text(11f, accent)
        val primaryView = text(17f, accent)
        val secondaryView = text(13f, Color.WHITE)
        val voltageView = text(13f, Color.rgb(155, 214, 222))
        card.addView(statusView)
        card.addView(primaryView)
        card.addView(secondaryView)
        card.addView(voltageView)
        statusView.text = "ChargeWatt"
        primaryView.text = "—W"
        secondaryView.text = "—mA"
        voltageView.text = "—V"

        val window = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val prefs = app.getSharedPreferences("chargewatt_overlay_position", Context.MODE_PRIVATE)
        val layout = WindowManager.LayoutParams(
            dp(124), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(16))
            y = prefs.getInt("y", dp(120))
        }
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        @SuppressLint("ClickableViewAccessibility")
        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    initialX = layout.x
                    initialY = layout.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (initialX + event.rawX - downX).roundToInt().coerceAtLeast(0)
                    layout.y = (initialY + event.rawY - downY).roundToInt().coerceAtLeast(0)
                    runCatching { window.updateViewLayout(card, layout) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
                    true
                }
                else -> false
            }
        }
        return try {
            window.addView(card, layout)
            windowManager = window
            root = card
            params = layout
            status = statusView
            primary = primaryView
            secondary = secondaryView
            voltage = voltageView
            isRunning = true
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun render(reading: BatteryReading, unit: String) {
        if (!isRunning) return
        main.post {
            if (!isRunning) return@post
            val draining = reading.currentMa?.let { it < 0 } ?: (reading.mode == ChargeMode.DISCHARGING)
            val accent = if (draining) Color.rgb(255, 180, 67) else Color.rgb(91, 241, 156)
            status?.text = WidgetTextPolicy.chargerBadge(reading)
            status?.setTextColor(
                if (reading.source == PowerSource.BATTERY) Color.rgb(255, 180, 67)
                else Color.rgb(91, 241, 156)
            )
            primary?.text = WidgetTextPolicy.primary(reading, unit)
            primary?.setTextColor(accent)
            secondary?.text = WidgetTextPolicy.secondary(reading, unit)
            voltage?.text = WidgetTextPolicy.voltage(reading.voltageV)
        }
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { stop() }
            return
        }
        root?.let { card -> runCatching { windowManager?.removeView(card) } }
        root = null
        params = null
        status = null
        primary = null
        secondary = null
        voltage = null
        windowManager = null
        isRunning = false
    }
}

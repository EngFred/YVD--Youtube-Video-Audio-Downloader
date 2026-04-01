package com.engfred.yvd.service

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import com.engfred.yvd.MainActivity
import kotlin.math.abs
import android.animation.ValueAnimator.AnimatorUpdateListener
import com.engfred.yvd.R

/**
 * Foreground service that draws a floating "return to app" bubble over all apps.
 *
 * Activated when the user taps the in-app YouTube button ([ACTION_SHOW]).
 * Dismissed automatically when the user returns to the app ([ACTION_HIDE] from onResume).
 *
 * NOTE: Clipboard monitoring was removed — Android 10+ blocks background clipboard
 * access for all non-IME apps. URL delivery now happens via the Share sheet (ACTION_SEND).
 */
class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_SHOW = "com.engfred.yvd.SHOW_BUBBLE"
        const val ACTION_HIDE = "com.engfred.yvd.HIDE_BUBBLE"

        private const val NOTIF_ID = 9001
        private const val CHANNEL_ID = "bubble_channel"
    }

    private lateinit var wm: WindowManager

    private var bubbleRoot: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var dismissZone: FrameLayout? = null

    private var isShowing = false
    private var dismissZoneHighlighted = false

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false

    private val handler = Handler(Looper.getMainLooper())

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> if (!isShowing) animateShowBubble()
            ACTION_HIDE -> animateHideBubble()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        forceRemoveBubble()
        forceRemoveDismissZone()
    }

    // ─── Show / Hide ──────────────────────────────────────────────────────────

    private fun animateShowBubble() {
        if (isShowing) return
        val root = inflateBubble()
        val params = defaultBubbleParams()
        bubbleParams = params

        try {
            wm.addView(root, params)
            bubbleRoot = root
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        root.scaleX = 0f; root.scaleY = 0f; root.alpha = 0f
        root.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()
    }

    private fun animateHideBubble() {
        val root = bubbleRoot ?: run { isShowing = false; return }
        root.animate()
            .scaleX(0f).scaleY(0f).alpha(0f)
            .setDuration(200)
            .withEndAction { forceRemoveBubble() }
            .start()
        isShowing = false
    }

    private fun forceRemoveBubble() {
        bubbleRoot?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubbleRoot = null
        isShowing = false
    }

    private fun forceRemoveDismissZone() {
        dismissZone?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dismissZone = null
    }

    // ─── Bubble inflation ─────────────────────────────────────────────────────

    private fun inflateBubble(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val size = dp(56)
        val circle = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFFEF5350.toInt(), 0xFFB71C1C.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(2), 0x33FFFFFF)
            }
        }

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        }
        circle.addView(icon)
        root.addView(circle)

        circle.setOnTouchListener { _, event -> handleTouch(root, event) }
        return root
    }

    // ─── Drag & dismiss ───────────────────────────────────────────────────────

    private fun handleTouch(root: LinearLayout, event: android.view.MotionEvent): Boolean {
        val params = bubbleParams ?: return false
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = params.x; dragStartY = params.y
                touchStartX = event.rawX; touchStartY = event.rawY
                isDragging = false
                showDismissZone()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY
                if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) isDragging = true
                if (isDragging) {
                    params.x = (dragStartX + dx).toInt()
                    params.y = (dragStartY + dy).toInt()
                    try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    pulseDismissZoneOnHover(event.rawY)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hideDismissZone()
                if (isDragging) {
                    if (isOverDismissZone(event.rawY)) animateHideBubble()
                    else snapToEdge(root, params)
                } else {
                    launchApp()
                }
                isDragging = false
                true
            }
            else -> false
        }
    }

    private fun showDismissZone() {
        if (dismissZone != null) return
        val zone = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xBB1A1A1A.toInt())
                setStroke(dp(2), 0x44FFFFFF)
            }
            addView(android.widget.TextView(context).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        val params = WindowManager.LayoutParams(
            dp(60), dp(60),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }
        try {
            wm.addView(zone, params)
            dismissZone = zone
            zone.alpha = 0f
            zone.animate().alpha(1f).setDuration(180).start()
        } catch (_: Exception) {}
    }

    private fun hideDismissZone() {
        dismissZone?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            forceRemoveDismissZone()
            dismissZoneHighlighted = false
        }?.start()
    }

    private fun pulseDismissZoneOnHover(rawY: Float) {
        val inZone = isOverDismissZone(rawY)
        if (inZone == dismissZoneHighlighted) return
        dismissZoneHighlighted = inZone
        dismissZone?.animate()
            ?.scaleX(if (inZone) 1.35f else 1f)
            ?.scaleY(if (inZone) 1.35f else 1f)
            ?.setDuration(120)?.start()
    }

    private fun isOverDismissZone(rawY: Float) =
        rawY > resources.displayMetrics.heightPixels - dp(140)

    private fun snapToEdge(root: LinearLayout, params: WindowManager.LayoutParams) {
        val screenW = resources.displayMetrics.widthPixels
        val bubbleW = dp(56)
        val targetX = if (params.x + bubbleW / 2 < screenW / 2) dp(8)
        else screenW - bubbleW - dp(8)
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.5f)
            addUpdateListener {
                params.x = it.animatedValue as Int
                try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun launchApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    // ─── WindowManager helpers ────────────────────────────────────────────────

    private fun defaultBubbleParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = resources.displayMetrics.widthPixels - dp(56) - dp(12)
        y = (resources.displayMetrics.heightPixels * 0.28f).toInt()
    }

    @Suppress("DEPRECATION")
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("YV Downloader")
        .setContentText("Tap the bubble to return to the app")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Bubble Service", NotificationManager.IMPORTANCE_MIN)
                .apply {
                    description = "Floating bubble to return to YV Downloader"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                }
                .also {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .createNotificationChannel(it)
                }
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()
}
package com.dsh.petshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var lp: WindowManager.LayoutParams
    private var focused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
    }

    private fun startForegroundCompat() {
        val ch = "pet"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "桌宠", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val n = Notification.Builder(this, ch)
            .setContentTitle("桌宠运行中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildOverlay() {
        val prefs = getSharedPreferences("pet", MODE_PRIVATE)
        val base = (prefs.getString("url", "http://127.0.0.1:3080") ?: "").trimEnd('/')
        val token = prefs.getString("token", "") ?: ""
        val density = resources.displayMetrics.density

        web = WebView(this).also { w ->
            w.setBackgroundColor(Color.TRANSPARENT)
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            w.settings.mediaPlaybackRequiresUserGesture = false
            w.webViewClient = WebViewClient()
        }
        if (token.isNotEmpty()) {
            CookieManager.getInstance().setCookie(base, "dsh_pet_desk=$token")
            CookieManager.getInstance().setAcceptCookie(true)
        }
        val url = "$base/dsh-pet/standalone" + if (token.isNotEmpty()) "?k=$token" else ""
        web.loadUrl(url)

        // 顶部拖动条：拖 = 移动，点 = 切「可输入 / 只观看」
        val bar = View(this).apply { setBackgroundColor(Color.argb(70, 0, 0, 0)) }

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(bar, FrameLayout.LayoutParams(
                (28 * density).toInt(),
                (36 * density).toInt()
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
        }

        lp = WindowManager.LayoutParams(
            (240 * density).toInt(),
            (360 * density).toInt(),
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = (8 * density).toInt()
            y = (120 * density).toInt()
        }

        wm.addView(root, lp)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) + abs(dy) > 12) moved = true
                    // gravity 是 BOTTOM|END，所以位移要反着算
                    lp.x = startX - dx
                    lp.y = startY - dy
                    wm.updateViewLayout(root, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleFocus()
                    true
                }
                else -> false
            }
        }
    }

    /** 可输入模式：去掉 NOT_FOCUSABLE，WebView 才能弹键盘；代价是抢焦点。 */
    private fun toggleFocus() {
        focused = !focused
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focused) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.flags = flags
        wm.updateViewLayout(root, lp)
        if (focused) {
            web.requestFocus()
        } else {
            web.clearFocus()
        }
    }

    override fun onDestroy() {
        try {
            wm.removeView(root)
            web.destroy()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}

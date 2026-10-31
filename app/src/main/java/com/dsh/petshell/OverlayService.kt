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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
        // 页面把「模型实际宽高比」通过这个桥报回来，壳据此定窗口形状
        web.addJavascriptInterface(Bridge(), "DshPetShell")
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

        // 初始给个接近她身形的窗口；页面量完真实比例会再调一次
        val dm = resources.displayMetrics
        val startH = (dm.heightPixels * 0.55).toInt()
        val startW = (startH * 0.62).toInt().coerceAtMost((dm.widthPixels * 0.92).toInt())
        lp = WindowManager.LayoutParams(
            startW,
            startH,
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

    /** 页面 → 壳 的桥：只暴露「设置宽高比」这一件事。 */
    inner class Bridge {
        @JavascriptInterface
        fun setAspect(ratio: Double) = applyAspect(ratio)

        /** 页面问「我该多大」：给一个 CSS 像素高度（屏高的一半），页面据此定尺寸 */
        @JavascriptInterface
        fun heightCss(): Double {
            val dm = resources.displayMetrics
            return (dm.heightPixels / dm.density) * 0.5
        }

        /** 页面量完自己要多大的画布，把窗口精确缩到那个大小——这样就不会溢出 */
        @JavascriptInterface
        fun setSize(wCss: Double, hCss: Double) = applySize(wCss, hCss)

        @JavascriptInterface
        fun dbg(msg: String) {
            Log.i("PetShellDBG", msg)
        }
    }

    /** 把窗口精确设成页面报来的 CSS 尺寸（换算成 px，并夹在屏幕内） */
    private fun applySize(wCss: Double, hCss: Double) {
        if (wCss.isNaN() || hCss.isNaN() || wCss < 40 || hCss < 40) return
        Handler(Looper.getMainLooper()).post {
            try {
                val dm = resources.displayMetrics
                var w = (wCss * dm.density).toInt()
                var h = (hCss * dm.density).toInt()
                val maxW = (dm.widthPixels * 0.92).toInt()
                val maxH = (dm.heightPixels * 0.92).toInt()
                if (w > maxW) { h = (h.toDouble() * maxW / w).toInt(); w = maxW }
                if (h > maxH) { w = (w.toDouble() * maxH / h).toInt(); h = maxH }
                lp.width = w
                lp.height = h
                wm.updateViewLayout(root, lp)
            } catch (_: Throwable) {
            }
        }
    }

    /** 按页面报来的宽高比重排窗口：高度取屏高 55%，宽度不够就压回来。 */
    private fun applyAspect(ratio: Double) {
        if (ratio.isNaN() || ratio < 0.15 || ratio > 8) return
        Handler(Looper.getMainLooper()).post {
            try {
                val dm = resources.displayMetrics
                var h = (dm.heightPixels * 0.55).toInt()
                var w = (h * ratio).toInt()
                val maxW = (dm.widthPixels * 0.92).toInt()
                if (w > maxW) {
                    w = maxW
                    h = (w / ratio).toInt()
                }
                lp.width = w
                lp.height = h
                wm.updateViewLayout(root, lp)
            } catch (_: Throwable) {
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

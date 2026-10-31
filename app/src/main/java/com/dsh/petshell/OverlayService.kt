package com.dsh.petshell

import android.app.Notification
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
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
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * 桌宠壳：一个吃不到触摸的模型窗 + 一个小的可拖动把手窗。
 *
 * 为什么拆成两个窗：安卓的悬浮窗按矩形吃触摸，没法按像素穿透。
 * 所以模型窗设成 NOT_TOUCHABLE（桌面随便点，不会被它挡），
 * 只留一个 24dp 的小把手用来拖动。全屏点按由外部监听（getevent）喂进来。
 */
// build: touchable-model-window v2
class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.dsh.petshell.TOGGLE_TOUCH"
    }

    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var handle: View
    private lateinit var handleLp: WindowManager.LayoutParams
    private val main = Handler(Looper.getMainLooper())

    private var tapFile: File? = null
    private var tapLen = 0L
    private var lastFire = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
        startTapWatch()
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
        web.addJavascriptInterface(Bridge(), "DshPetShell")
        val url = "$base/dsh-pet/standalone" + if (token.isNotEmpty()) "?k=$token" else ""
        web.loadUrl(url)

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(web, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

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
            // 她附近这一块吃触摸：点在这里她会有反应（窗口透明，看不出来）
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

        // 把手：唯一能吃触摸的小窗，用来拖她 / 复位
        val hs = (24 * density).toInt()
        handle = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        handleLp = WindowManager.LayoutParams(
            hs, hs,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }
        wm.addView(handle, handleLp)
        syncHandle()

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var startHx = 0
        var startHy = 0
        var moved = false

        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    startHx = handleLp.x; startHy = handleLp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) + abs(dy) > 8) moved = true
                    lp.x = startX - dx
                    lp.y = startY - dy
                    handleLp.x = startHx - dx
                    handleLp.y = startHy - dy
                    try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
                    try { wm.updateViewLayout(handle, handleLp) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) resetPosition()
                    true
                }
                else -> false
            }
        }
    }

    /** 把手贴在模型窗的左上角 */
    private fun syncHandle() {
        try {
            handleLp.x = lp.x + lp.width - handleLp.width
            handleLp.y = lp.y + lp.height - handleLp.height
            wm.updateViewLayout(handle, handleLp)
        } catch (_: Throwable) {
        }
    }

    private fun resetPosition() {
        val d = resources.displayMetrics.density
        lp.x = (8 * d).toInt()
        lp.y = (120 * d).toInt()
        try { wm.updateViewLayout(root, lp) } catch (_: Throwable) {}
        syncHandle()
    }

    // ———— 全屏点按：外部监听写文件，这里轮询并让她演一次 ————
    private fun startTapWatch() {
        try {
            val dir = getExternalFilesDir(null) ?: return
            dir.mkdirs()
            tapFile = File(dir, "tap")
            tapLen = tapFile?.length() ?: 0L
        } catch (_: Throwable) { tapFile = null }
        main.postDelayed(object : Runnable {
            override fun run() {
                checkTap()
                main.postDelayed(this, 150)
            }
        }, 400)
    }

    private fun checkTap() {
        val f = tapFile ?: return
        try {
            val len = f.length()
            if (len <= tapLen) { tapLen = len; return }
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(tapLen)
                val n = (len - tapLen).toInt().coerceAtLeast(0)
                val buf = ByteArray(n)
                raf.readFully(buf)
                val lines = String(buf).count { it == '\n' }
                tapLen = len
                if (lines > 0) firePoke(lines)
            }
        } catch (_: Throwable) {
        }
    }

    private fun firePoke(times: Int) {
        val now = System.currentTimeMillis()
        if (now - lastFire < 120) return
        lastFire = now
        for (i in 0 until times.coerceAtMost(3)) {
            main.postDelayed({
                try {
                    web.evaluateJavascript("window.DSHPet && window.DSHPet.poke && window.DSHPet.poke()", null)
                } catch (_: Throwable) {}
            }, i * 90L)
        }
    }

    /** 页面 → 壳 的桥 */
    inner class Bridge {
        @JavascriptInterface
        fun setAspect(ratio: Double) = applyAspect(ratio)

        @JavascriptInterface
        fun heightCss(): Double {
            val dm = resources.displayMetrics
            return (dm.heightPixels / dm.density) * 0.5
        }

        @JavascriptInterface
        fun setSize(wCss: Double, hCss: Double) = applySize(wCss, hCss)

        @JavascriptInterface
        fun dbg(msg: String) {
            Log.i("PetShellDBG", msg)
        }
    }

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
                syncHandle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun applyAspect(ratio: Double) {
        if (ratio.isNaN() || ratio < 0.15 || ratio > 8) return
        Handler(Looper.getMainLooper()).post {
            try {
                val dm = resources.displayMetrics
                var h = (dm.heightPixels * 0.55).toInt()
                var w = (h * ratio).toInt()
                val maxW = (dm.widthPixels * 0.92).toInt()
                if (w > maxW) { w = maxW; h = (w / ratio).toInt() }
                lp.width = w
                lp.height = h
                wm.updateViewLayout(root, lp)
                syncHandle()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        try {
            wm.removeView(root)
            wm.removeView(handle)
            web.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}

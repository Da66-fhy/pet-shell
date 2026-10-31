package com.dsh.petshell

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlField = findViewById<EditText>(R.id.url)
        val tokenField = findViewById<EditText>(R.id.token)
        val prefs = getSharedPreferences("pet", MODE_PRIVATE)

        urlField.setText(prefs.getString("url", "http://127.0.0.1:3080"))
        tokenField.setText(prefs.getString("token", ""))

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        findViewById<Button>(R.id.start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先给「显示在其他应用上层」权限，再回来", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }
            prefs.edit()
                .putString("url", urlField.text.toString().trim())
                .putString("token", tokenField.text.toString().trim())
                .apply()
            startForegroundService(Intent(this, OverlayService::class.java))
            finish()
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }
    }
}

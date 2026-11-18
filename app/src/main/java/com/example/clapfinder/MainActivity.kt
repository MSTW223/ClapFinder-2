package com.example.clapfinder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btn: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btn = findViewById(R.id.btnToggle)
        status = findViewById(R.id.tvStatus)
        val seek = findViewById<SeekBar>(R.id.seekSensitivity)
        val prefs = getSharedPreferences(ClapService.PREFS, MODE_PRIVATE)

        seek.progress = prefs.getInt(ClapService.KEY_SENSITIVITY, 60)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt(ClapService.KEY_SENSITIVITY, p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btn.setOnClickListener {
            if (ClapService.running) {
                startService(Intent(this, ClapService::class.java).setAction(ClapService.ACTION_STOP_SERVICE))
                btn.postDelayed({ refresh() }, 300)
            } else {
                if (hasPermissions()) startClapService() else requestPermissions()
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun hasPermissions() = neededPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, neededPermissions(), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micOk) startClapService() else status.setText(R.string.need_mic)
    }

    private fun startClapService() {
        ContextCompat.startForegroundService(this, Intent(this, ClapService::class.java))
        btn.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        if (ClapService.running) {
            btn.setText(R.string.stop)
            status.setText(R.string.status_on)
        } else {
            btn.setText(R.string.start)
            status.setText(R.string.status_off)
        }
    }
}

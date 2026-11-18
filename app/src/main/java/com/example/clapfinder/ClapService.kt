package com.example.clapfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * خدمة تعمل في الخلفية: تستمع للميكروفون، وعند اكتشاف تصفيقتين متتاليتين
 * تشغّل صوتاً عالياً مع وميض الفلاش واهتزاز. تصفيقتان أخريان (أو زر الإيقاف) توقفان التنبيه.
 */
class ClapService : Service() {

    companion object {
        const val ACTION_STOP_ALARM = "com.example.clapfinder.STOP_ALARM"
        const val ACTION_STOP_SERVICE = "com.example.clapfinder.STOP_SERVICE"
        const val PREFS = "clap_prefs"
        const val KEY_SENSITIVITY = "sensitivity" // 0..100
        private const val CHANNEL_ID = "clap_finder_channel"
        private const val NOTIF_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val MIN_GAP_MS = 150L   // أقل فاصل بين التصفيقتين
        private const val MAX_GAP_MS = 900L   // أكبر فاصل بين التصفيقتين
        private const val DEAD_TIME_MS = 120L // تجاهل صدى التصفيقة نفسها

        @Volatile var running = false
    }

    private var record: AudioRecord? = null
    private var listenThread: Thread? = null
    @Volatile private var listening = false

    private var player: MediaPlayer? = null
    private var alarmOn = false
    private val handler = Handler(Looper.getMainLooper())
    private var torchOn = false
    private var cameraId: String? = null
    private var oldVolume = -1

    // ---------------- دورة حياة الخدمة ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALARM -> { stopAlarm(); return START_STICKY }
            ACTION_STOP_SERVICE -> { stopSelf(); return START_NOT_STICKY }
        }
        startForegroundCompat()
        running = true
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        listening = false
        stopAlarm()
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        running = false
        super.onDestroy()
    }

    // ---------------- الاستماع للتصفيق ----------------

    private fun startListening() {
        if (listening) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE / 10)
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            record?.startRecording()
        } catch (e: SecurityException) {
            stopSelf(); return
        }
        listening = true

        listenThread = Thread {
            val buf = ShortArray(SAMPLE_RATE / 50) // نافذة 20 ميلي ثانية
            var noise = 500.0                       // متوسط الضجيج المحيط
            var lastClap = 0L
            var firstClap = 0L

            while (listening) {
                val n = record?.read(buf, 0, buf.size) ?: break
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))

                // الحساسية: 0 = تحتاج صوتاً عالياً جداً، 100 = حساس جداً
                val sens = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_SENSITIVITY, 60)
                val minPeak = 20000 - sens * 150           // من 20000 إلى 5000
                val threshold = maxOf(minPeak.toDouble(), noise * 6)

                val now = System.currentTimeMillis()
                if (peak > threshold && now - lastClap > DEAD_TIME_MS) {
                    lastClap = now
                    val gap = now - firstClap
                    if (firstClap != 0L && gap in MIN_GAP_MS..MAX_GAP_MS) {
                        firstClap = 0L
                        handler.post { onDoubleClap() }
                    } else {
                        firstClap = now
                    }
                } else if (peak <= threshold) {
                    // تحديث تدريجي لمستوى الضجيج المحيط
                    noise = noise * 0.98 + peak * 0.02
                }
                if (firstClap != 0L && now - firstClap > MAX_GAP_MS) firstClap = 0L
            }
        }.apply { name = "ClapListener"; start() }
    }

    private fun onDoubleClap() {
        if (alarmOn) stopAlarm() else startAlarm()
    }

    // ---------------- التنبيه: صوت + فلاش + اهتزاز ----------------

    private fun startAlarm() {
        if (alarmOn) return
        alarmOn = true

        // رفع صوت المنبه للأقصى
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ClapService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}

        // اهتزاز متكرر
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 500, 300)
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createWaveform(pattern, 0))
        else @Suppress("DEPRECATION") vib.vibrate(pattern, 0)

        // وميض الفلاش
        handler.post(flashRunnable)
        updateNotification()
    }

    private val flashRunnable = object : Runnable {
        override fun run() {
            if (!alarmOn) return
            setTorch(!torchOn)
            handler.postDelayed(this, 150)
        }
    }

    private fun setTorch(on: Boolean) {
        val id = cameraId ?: return
        try {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on)
            torchOn = on
        } catch (_: Exception) {}
    }

    private fun stopAlarm() {
        if (!alarmOn && player == null) return
        alarmOn = false
        handler.removeCallbacks(flashRunnable)
        setTorch(false)
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
        if (oldVolume >= 0) {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
            oldVolume = -1
        }
        updateNotification()
    }

    // ---------------- الإشعار ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Clap Finder", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAlarm = PendingIntent.getService(
            this, 1, Intent(this, ClapService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAll = PendingIntent.getService(
            this, 2, Intent(this, ClapService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (alarmOn) getString(R.string.notif_ringing) else getString(R.string.notif_listening)
            )
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_alarm), stopAlarm)
            .addAction(0, getString(R.string.stop_service), stopAll)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
}

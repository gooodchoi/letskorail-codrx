package com.example.letskorail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

class ReservationForegroundService : Service() {

    @Volatile
    private var isRunning = false

    @Volatile
    private var isPaused = false

    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val userId = intent.getStringExtra(EXTRA_USER_ID).orEmpty()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                val departure = intent.getStringExtra(EXTRA_DEPARTURE).orEmpty()
                val arrival = intent.getStringExtra(EXTRA_ARRIVAL).orEmpty()
                val date = intent.getStringExtra(EXTRA_DATE).orEmpty()
                val minTime = intent.getStringExtra(EXTRA_MIN_TIME).orEmpty()
                val maxTime = intent.getStringExtra(EXTRA_MAX_TIME).orEmpty()
                val avgIntervalSec = intent.getDoubleExtra(EXTRA_AVG_INTERVAL_SEC, 2.0)

                startForeground(NOTIFICATION_ID_FOREGROUND, buildProgressNotification("예매 시도 중..."))
                startLoop(userId, password, departure, arrival, date, minTime, maxTime, avgIntervalSec)
            }

            ACTION_PAUSE -> {
                isPaused = true
                updateProgressNotification("예매 일시중지")
            }

            ACTION_RESUME -> {
                isPaused = false
                updateProgressNotification("예매 재시도 중...")
            }

            ACTION_STOP -> {
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLoop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startLoop(
        userId: String,
        password: String,
        departure: String,
        arrival: String,
        date: String,
        minTime: String,
        maxTime: String,
        avgIntervalSec: Double,
    ) {
        stopLoop()
        isRunning = true
        isPaused = false

        workerThread = Thread {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }

            val bridge = Python.getInstance().getModule("korail_bridge")
            var attempts = 0

            while (isRunning) {
                if (isPaused) {
                    Thread.sleep(200)
                    continue
                }

                attempts += 1
                sendStatusBroadcast(attempts, "[$attempts] 조회 시도 중...", null, null)

                val raw = callPython(
                    bridge,
                    "reserve_once",
                    userId,
                    password,
                    departure,
                    arrival,
                    date,
                    minTime,
                    maxTime,
                )
                val parsed = parseReserveResponse(raw)
                val message = parsed.optString("message", "응답 메시지가 없습니다.")
                sendStatusBroadcast(attempts, "[$attempts] $message", null, raw)

                if (parsed.optBoolean("success", false)) {
                    val detail = buildCompactReservationText(parsed)
                    showSuccessNotification(detail)
                    sendStatusBroadcast(attempts, message, "completed", raw)
                    isRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                val delaySec = randomizedDelay(avgIntervalSec)
                val delayText = String.format(Locale.KOREA, "%.2f", delaySec)
                updateProgressNotification("재시도 대기 ${delayText}초")
                sendStatusBroadcast(attempts, message, delayText, raw)
                Thread.sleep(max(200L, (delaySec * 1000).toLong()))
            }
        }
        workerThread?.start()
    }

    private fun stopLoop() {
        isRunning = false
        isPaused = false
        workerThread?.interrupt()
        workerThread = null
    }

    private fun sendStatusBroadcast(attempts: Int, message: String, nextDelaySec: String?, rawJson: String?) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ATTEMPTS, attempts)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_NEXT_DELAY_SEC, nextDelaySec)
            putExtra(EXTRA_RAW_JSON, rawJson)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "백그라운드 예매 진행",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "예매 루프가 실행 중임을 표시합니다."
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                SUCCESS_CHANNEL_ID,
                "예매 성공 알림",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "예매 성공 시 알림을 표시합니다."
            },
        )
    }

    private fun buildProgressNotification(content: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("코레일 예매 시도 중")
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateProgressNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_FOREGROUND, buildProgressNotification(content))
    }

    private fun showSuccessNotification(content: String) {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, SUCCESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("예매 성공")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_SUCCESS, notification)
    }

    private fun randomizedDelay(avgSec: Double): Double {
        val lower = avgSec * 0.5
        val upper = avgSec * 1.5
        return Random.nextDouble(lower, upper)
    }

    private fun parseReserveResponse(raw: String): JSONObject {
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject().apply {
                put("success", false)
                put("message", raw)
            }
        }
    }

    private fun buildCompactReservationText(parsed: JSONObject): String {
        val builder = StringBuilder()
        val reservationNo = parsed.optString("reservation_no", "-")
        val price = parsed.optInt("price", 0)
        val deadline = parsed.optString("deadline", "-")

        builder.appendLine("예약번호: $reservationNo")
        builder.appendLine("결제기한: $deadline")
        builder.appendLine("결제금액: ${java.text.NumberFormat.getNumberInstance(Locale.KOREA).format(price)}원")

        val details = parsed.optJSONArray("details")
        if (details != null && details.length() > 0) {
            val first = details.optJSONObject(0)
            if (first != null) {
                builder.appendLine("열차: ${first.optString("train_name")} ${first.optString("train_no")}")
                builder.appendLine("출발: ${first.optString("departure")}")
                builder.appendLine("도착: ${first.optString("arrival")}")
            }
        }
        return builder.toString().trim()
    }

    private fun callPython(module: PyObject, functionName: String, vararg args: Any): String {
        return try {
            module.callAttr(functionName, *args).toString()
        } catch (e: Exception) {
            "오류: ${e.message}"
        }
    }

    companion object {
        const val ACTION_START = "com.example.letskorail.action.START_RESERVATION"
        const val ACTION_STOP = "com.example.letskorail.action.STOP_RESERVATION"
        const val ACTION_PAUSE = "com.example.letskorail.action.PAUSE_RESERVATION"
        const val ACTION_RESUME = "com.example.letskorail.action.RESUME_RESERVATION"
        const val ACTION_STATUS_UPDATE = "com.example.letskorail.action.STATUS_UPDATE"

        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_DEPARTURE = "extra_departure"
        const val EXTRA_ARRIVAL = "extra_arrival"
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_MIN_TIME = "extra_min_time"
        const val EXTRA_MAX_TIME = "extra_max_time"
        const val EXTRA_AVG_INTERVAL_SEC = "extra_avg_interval_sec"
        const val EXTRA_ATTEMPTS = "extra_attempts"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_NEXT_DELAY_SEC = "extra_next_delay_sec"
        const val EXTRA_RAW_JSON = "extra_raw_json"

        private const val FOREGROUND_CHANNEL_ID = "reservation_foreground_channel"
        private const val SUCCESS_CHANNEL_ID = "reservation_success_channel"
        private const val NOTIFICATION_ID_FOREGROUND = 2001
        private const val NOTIFICATION_ID_SUCCESS = 2002
    }
}

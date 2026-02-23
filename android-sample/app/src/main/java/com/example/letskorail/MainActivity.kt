package com.example.letskorail

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView
    private lateinit var loginErrorText: TextView
    private lateinit var loginContainer: LinearLayout
    private lateinit var reserveContainer: LinearLayout
    private lateinit var reserveFormContainer: LinearLayout
    private lateinit var reserveProgressContainer: LinearLayout
    private lateinit var reserveSuccessContainer: LinearLayout
    private lateinit var startedAtText: TextView
    private lateinit var attemptCountText: TextView
    private lateinit var nextDelayText: TextView
    private lateinit var compactInfoText: TextView
    private lateinit var countdownText: TextView
    private lateinit var paymentFailText: TextView
    private lateinit var buttonBackToReserve: Button
    private lateinit var buttonReservePrevious: Button
    private lateinit var buttonReserveToggle: Button
    private lateinit var buttonCancelReservation: Button

    private lateinit var departureInput: AutoCompleteTextView
    private lateinit var arrivalInput: AutoCompleteTextView
    private lateinit var departureAdapter: ArrayAdapter<String>
    private lateinit var arrivalAdapter: ArrayAdapter<String>

    private var selectedDate: Calendar = Calendar.getInstance()

    @Volatile
    private var isReserving = false

    @Volatile
    private var isReservationPaused = false

    private var latestReservationNo: String? = null

    private val countdownHandler = Handler(Looper.getMainLooper())
    private var paymentDeadlineMs: Long = 0L
    private var countdownRunnable: Runnable? = null
    private val reservationStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ReservationForegroundService.ACTION_STATUS_UPDATE) return

            val attempts = intent.getIntExtra(ReservationForegroundService.EXTRA_ATTEMPTS, 0)
            val message = intent.getStringExtra(ReservationForegroundService.EXTRA_MESSAGE).orEmpty()
            val nextDelay = intent.getStringExtra(ReservationForegroundService.EXTRA_NEXT_DELAY_SEC)
            val rawJson = intent.getStringExtra(ReservationForegroundService.EXTRA_RAW_JSON)

            if (attempts > 0) {
                attemptCountText.text = "조회 시도: ${attempts}회"
            }
            if (message.isNotBlank()) {
                resultText.text = message
            }

            if (nextDelay == "completed") {
                nextDelayText.text = "다음 조회 대기: 완료"
                isReserving = false
                isReservationPaused = false
                updateReserveToggleButton(isPaused = false)
                val parsed = parseReserveResponse(rawJson ?: "")
                if (parsed.optBoolean("success", false)) {
                    showReservationSuccess(parsed)
                }
                return
            }

            if (!nextDelay.isNullOrBlank()) {
                nextDelayText.text = "다음 조회 대기: ${nextDelay}초"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ensureNotificationPermission()
        ensureBatteryOptimizationException()

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val py = Python.getInstance()
        val bridge = py.getModule("korail_bridge")

        val idInput = findViewById<EditText>(R.id.editUserId)
        val pwInput = findViewById<EditText>(R.id.editPassword)
        val loginButton = findViewById<Button>(R.id.buttonLogin)

        departureInput = findViewById(R.id.editDeparture)
        arrivalInput = findViewById(R.id.editArrival)

        val minTimeInput = findViewById<EditText>(R.id.editMinTime)
        val maxTimeInput = findViewById<EditText>(R.id.editMaxTime)
        val avgIntervalInput = findViewById<EditText>(R.id.editAvgInterval)
        val datePickerButton = findViewById<Button>(R.id.buttonDatePicker)
        val swapStationsButton = findViewById<Button>(R.id.buttonSwapStations)
        val reserveStartButton = findViewById<Button>(R.id.buttonStartReserve)
        val logoutButton = findViewById<Button>(R.id.buttonLogout)

        resultText = findViewById(R.id.textResult)
        loginErrorText = findViewById(R.id.textLoginError)
        loginContainer = findViewById(R.id.loginContainer)
        reserveContainer = findViewById(R.id.reserveContainer)
        reserveFormContainer = findViewById(R.id.reserveFormContainer)
        reserveProgressContainer = findViewById(R.id.reserveProgressContainer)
        reserveSuccessContainer = findViewById(R.id.reserveSuccessContainer)
        startedAtText = findViewById(R.id.textStartedAt)
        attemptCountText = findViewById(R.id.textAttemptCount)
        nextDelayText = findViewById(R.id.textNextDelay)
        compactInfoText = findViewById(R.id.textCompactReservationInfo)
        countdownText = findViewById(R.id.textPaymentCountdown)
        paymentFailText = findViewById(R.id.textPaymentFail)
        buttonBackToReserve = findViewById(R.id.buttonBackToReserve)
        buttonReservePrevious = findViewById(R.id.buttonReservePrevious)
        buttonReserveToggle = findViewById(R.id.buttonReserveToggle)
        buttonCancelReservation = findViewById(R.id.buttonCancelReservation)

        setupStationSelectors()

        departureInput.setText("서울", false)
        arrivalInput.setText("부산", false)
        minTimeInput.setText("06:00")
        maxTimeInput.setText("23:00")
        avgIntervalInput.setText("2.0")
        updateDateButtonText(datePickerButton)

        refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
        refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)

        pwInput.addTextChangedListener(SimpleTextWatcher {
            loginErrorText.visibility = View.GONE
        })

        datePickerButton.setOnClickListener {
            showDatePicker(datePickerButton)
        }

        swapStationsButton.setOnClickListener {
            val from = departureInput.text.toString().trim()
            val to = arrivalInput.text.toString().trim()
            departureInput.setText(to, false)
            arrivalInput.setText(from, false)
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
        }

        loginButton.setOnClickListener {
            val id = idInput.text.toString()
            val pw = pwInput.text.toString()

            val result = callPython(bridge, "login", id, pw)
            if (result.startsWith("로그인 성공")) {
                Toast.makeText(this, "로그인 성공", Toast.LENGTH_SHORT).show()
                loginErrorText.visibility = View.GONE
                showReservePage()
            } else {
                loginErrorText.visibility = View.VISIBLE
            }
        }

        reserveStartButton.setOnClickListener {
            if (isReserving) {
                return@setOnClickListener
            }

            val departure = departureInput.text.toString().trim()
            val arrival = arrivalInput.text.toString().trim()

            if (!ALL_STATIONS.contains(departure)) {
                resultText.text = "출발역은 목록 내 역만 선택할 수 있습니다."
                return@setOnClickListener
            }

            if (!ALL_STATIONS.contains(arrival)) {
                resultText.text = "도착역은 목록 내 역만 선택할 수 있습니다."
                return@setOnClickListener
            }

            if (!areConnectedStations(departure, arrival)) {
                resultText.text = "선택한 출발/도착역은 동일 노선으로 연결되지 않습니다."
                return@setOnClickListener
            }

            val avgInterval = avgIntervalInput.text.toString().toDoubleOrNull() ?: 0.0
            if (avgInterval <= 0.0) {
                resultText.text = "평균 조회 간격은 0보다 큰 값이어야 합니다."
                return@setOnClickListener
            }

            val minTime = normalizeTime(minTimeInput.text.toString())
            val maxTime = normalizeTime(maxTimeInput.text.toString())
            if (minTime == null || maxTime == null) {
                resultText.text = "시간 형식은 HH:mm 또는 HHmm 으로 입력하세요."
                return@setOnClickListener
            }

            if (minTime > maxTime) {
                resultText.text = "최소 출발 시간은 최대 출발 시간보다 빠르거나 같아야 합니다."
                return@setOnClickListener
            }

            val date = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(selectedDate.time)
            startReservationLoop(
                idInput.text.toString(),
                pwInput.text.toString(),
                departure,
                arrival,
                date,
                minTime,
                maxTime,
                avgInterval,
            )
        }

        buttonReservePrevious.setOnClickListener {
            stopReservationLoopAndReturnToForm()
        }

        buttonReserveToggle.setOnClickListener {
            if (!isReserving) {
                return@setOnClickListener
            }

            if (isReservationPaused) {
                isReservationPaused = false
                updateReserveToggleButton(isPaused = false)
                resultText.text = "예매를 다시 시작합니다. 조건에 맞는 열차를 재조회합니다."
            } else {
                isReservationPaused = true
                updateReserveToggleButton(isPaused = true)
                resultText.text = "예매를 일시중지했습니다. 시작 버튼으로 다시 진행할 수 있습니다."
            }
        }

        buttonBackToReserve.setOnClickListener {
            showReservePageAfterFailure()
        }

        buttonCancelReservation.setOnClickListener {
            val reservationNo = latestReservationNo
            if (reservationNo.isNullOrBlank()) {
                Toast.makeText(this, "취소할 예약번호를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val response = callPython(bridge, "cancel_reservation", reservationNo)
            val parsed = parseReserveResponse(response)
            val message = parsed.optString("message", "예매 취소 요청을 처리했습니다.")
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            if (parsed.optBoolean("success", false)) {
                latestReservationNo = null
                showReservePageAfterFailure()
            }
        }

        logoutButton.setOnClickListener {
            performLogout(bridge)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        isReserving = false
        isReservationPaused = false
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ReservationForegroundService.ACTION_STATUS_UPDATE)
        registerReceiver(reservationStatusReceiver, filter,Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(reservationStatusReceiver)
    }

    private fun setupStationSelectors() {
        departureAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        arrivalAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())

        departureInput.setAdapter(departureAdapter)
        departureInput.threshold = 0

        arrivalInput.setAdapter(arrivalAdapter)
        arrivalInput.threshold = 0

        departureInput.addTextChangedListener(SimpleTextWatcher {
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
            if (departureInput.hasFocus() && departureInput.text.toString().trim().isEmpty()) {
                departureInput.post { departureInput.showDropDown() }
            }
        })

        arrivalInput.addTextChangedListener(SimpleTextWatcher {
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
            if (arrivalInput.hasFocus() && arrivalInput.text.toString().trim().isEmpty()) {
                arrivalInput.post { arrivalInput.showDropDown() }
            }
        })

        departureInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
                departureInput.showDropDown()
            }
        }

        arrivalInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
                arrivalInput.showDropDown()
            }
        }

        departureInput.setOnClickListener {
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
            departureInput.showDropDown()
        }

        arrivalInput.setOnClickListener {
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
            arrivalInput.showDropDown()
        }

        departureInput.setOnItemClickListener { _, _, _, _ ->
            validatePairAndAdjust(arrivalInput, departureInput.text.toString().trim())
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
        }

        arrivalInput.setOnItemClickListener { _, _, _, _ ->
            validatePairAndAdjust(departureInput, arrivalInput.text.toString().trim())
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
        }
    }

    private fun startReservationLoop(
        userId: String,
        password: String,
        departure: String,
        arrival: String,
        date: String,
        minTime: String,
        maxTime: String,
        avgIntervalSec: Double
    ) {
        isReserving = true
        isReservationPaused = false
        latestReservationNo = null
        reserveSuccessContainer.visibility = View.GONE
        reserveFormContainer.visibility = View.GONE
        reserveProgressContainer.visibility = View.VISIBLE
        updateReserveToggleButton(isPaused = false)

        val startedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        startedAtText.text = "시작 시간: $startedAt"
        attemptCountText.text = "조회 시도: 0회"
        nextDelayText.text = "다음 조회 대기: -"
        resultText.text = "예매를 시작했습니다. 조건에 맞는 열차를 조회 중입니다."

        val startIntent = Intent(this, ReservationForegroundService::class.java).apply {
            action = ReservationForegroundService.ACTION_START
            putExtra(ReservationForegroundService.EXTRA_USER_ID, userId)
            putExtra(ReservationForegroundService.EXTRA_PASSWORD, password)
            putExtra(ReservationForegroundService.EXTRA_DEPARTURE, departure)
            putExtra(ReservationForegroundService.EXTRA_ARRIVAL, arrival)
            putExtra(ReservationForegroundService.EXTRA_DATE, date)
            putExtra(ReservationForegroundService.EXTRA_MIN_TIME, minTime)
            putExtra(ReservationForegroundService.EXTRA_MAX_TIME, maxTime)
            putExtra(ReservationForegroundService.EXTRA_AVG_INTERVAL_SEC, avgIntervalSec)
        }
        ContextCompat.startForegroundService(this, startIntent)
    }

    private fun showReservationSuccess(parsed: JSONObject) {
        reserveProgressContainer.visibility = View.GONE
        reserveSuccessContainer.visibility = View.VISIBLE

        latestReservationNo = parsed.optString("reservation_no", null)
        val popupText = buildCompactReservationText(parsed)
        compactInfoText.text = popupText

        val reservedAtMs = parsed.optLong("reserved_at_epoch_ms", System.currentTimeMillis())
        val timeoutSec = parsed.optLong("payment_timeout_sec", 600)
        paymentDeadlineMs = reservedAtMs + timeoutSec * 1000
        paymentFailText.visibility = View.GONE
        buttonBackToReserve.visibility = View.GONE
        startCountdownUi()

        AlertDialog.Builder(this)
            .setTitle("🎉 예매 성공")
            .setMessage(popupText)
            .setPositiveButton("확인", null)
            .show()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS
        )
    }

    private fun ensureBatteryOptimizationException() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            return
        }

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun startCountdownUi() {
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
        countdownRunnable = object : Runnable {
            override fun run() {
                val remaining = max(0L, (paymentDeadlineMs - System.currentTimeMillis()) / 1000)
                val minutes = remaining / 60
                val seconds = remaining % 60
                countdownText.text = "🕒 %02d:%02d".format(minutes, seconds)
                countdownText.setTextColor(Color.parseColor("#D32F2F"))

                if (remaining <= 0) {
                    paymentFailText.visibility = View.VISIBLE
                    buttonBackToReserve.visibility = View.VISIBLE
                } else {
                    countdownHandler.postDelayed(this, 1000)
                }
            }
        }
        countdownHandler.post(countdownRunnable!!)
    }

    private fun buildCompactReservationText(parsed: JSONObject): String {
        val sb = StringBuilder()
        val reservationNo = parsed.optString("reservation_no", "-")
        val price = parsed.optLong("price", 0)
        val formattedPrice = NumberFormat.getNumberInstance(Locale.KOREA).format(price)

        sb.append("예약번호: ").append(reservationNo).append("\n")
        sb.append(parsed.optString("deadline", "결제 기한 정보 없음")).append("\n")
        sb.append("총액: ").append(formattedPrice).append("원\n")

        val arr = parsed.optJSONArray("details")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val seatArray = obj.optJSONArray("seats")
                val seats = if (seatArray == null || seatArray.length() == 0) {
                    "좌석정보 없음"
                } else {
                    (0 until seatArray.length()).joinToString(" | ") { idx -> seatArray.getString(idx) }
                }
                sb.append("\n[${i + 1}] ${obj.optString("train_name")} ${obj.optString("train_no")}\n")
                sb.append("${obj.optString("departure")} → ${obj.optString("arrival")}\n")
                sb.append(seats).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun showReservePageAfterFailure() {
        isReserving = false
        isReservationPaused = false
        reserveSuccessContainer.visibility = View.GONE
        reserveProgressContainer.visibility = View.GONE
        reserveFormContainer.visibility = View.VISIBLE
        resultText.text = ""
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }
    }

    private fun stopReservationLoopAndReturnToForm() {
        isReserving = false
        isReservationPaused = false

        val stopIntent = Intent(this, ReservationForegroundService::class.java).apply {
            action = ReservationForegroundService.ACTION_STOP
        }
        startService(stopIntent)

        nextDelayText.text = "다음 조회 대기: 중지됨"
        resultText.text = "예매 진행을 중지하고 조건 설정 화면으로 돌아왔습니다."
        reserveProgressContainer.visibility = View.GONE
        reserveFormContainer.visibility = View.VISIBLE
    }

    private fun updateReserveToggleButton(isPaused: Boolean) {
        if (isPaused) {
            buttonReserveToggle.text = "시작"
            buttonReserveToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_primary_button)
        } else {
            buttonReserveToggle.text = "취소"
            buttonReserveToggle.background = ContextCompat.getDrawable(this, R.drawable.bg_danger_button)
        }

        if (!isReserving) {
            return
        }

        val action = if (isPaused) ReservationForegroundService.ACTION_PAUSE else ReservationForegroundService.ACTION_RESUME
        val intent = Intent(this, ReservationForegroundService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun parseReserveResponse(raw: String): JSONObject {
        return try {
            JSONObject(raw).also {
                Log.d(TAG, "Parsed reserve response: $it")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reserve response as JSON. raw=$raw", e)
            JSONObject().put("success", false).put("message", raw)
        }
    }

    private fun validatePairAndAdjust(targetField: AutoCompleteTextView, selectedStation: String) {
        val targetValue = targetField.text.toString().trim()
        if (!ALL_STATIONS.contains(selectedStation) || !ALL_STATIONS.contains(targetValue)) {
            return
        }

        if (!areConnectedStations(selectedStation, targetValue)) {
            targetField.setText("", false)
            Toast.makeText(this, "선택한 역과 연결되는 역만 선택할 수 있습니다.", Toast.LENGTH_SHORT).show()
            targetField.post {
                targetField.requestFocus()
                targetField.showDropDown()
            }
        }
    }

    private fun refreshStationSuggestions(
        currentField: AutoCompleteTextView,
        oppositeField: AutoCompleteTextView,
        adapter: ArrayAdapter<String>
    ) {
        val query = currentField.text.toString().trim()
        val opposite = oppositeField.text.toString().trim()
        val candidates = allowedStationsByOpposite(opposite)

        val sorted = candidates
            .sortedWith(compareBy<String> { stationScore(query, it) }.thenBy { it.length }.thenBy { it })

        adapter.clear()
        adapter.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    private fun allowedStationsByOpposite(opposite: String): Set<String> {
        if (!ALL_STATIONS.contains(opposite)) return ALL_STATIONS
        val connected = linkedStations(opposite)
        return connected.ifEmpty { ALL_STATIONS }
    }

    private fun linkedStations(station: String): Set<String> {
        val lines = STATION_TO_LINES[station] ?: return emptySet()
        val linked = mutableSetOf<String>()
        for (line in lines) linked.addAll(LINE_TO_STATIONS[line].orEmpty())
        linked.remove(station)
        return linked
    }

    private fun areConnectedStations(a: String, b: String): Boolean {
        if (a == b) return false
        val linesA = STATION_TO_LINES[a].orEmpty()
        val linesB = STATION_TO_LINES[b].orEmpty()
        return linesA.intersect(linesB).isNotEmpty()
    }

    private fun stationScore(query: String, station: String): Int {
        if (query.isBlank()) return 0
        val q = query.trim().lowercase(Locale.KOREA)
        val s = station.lowercase(Locale.KOREA)
        return when {
            s == q -> 0
            s.startsWith(q) -> 1
            s.contains(q) -> 2
            else -> 100 + levenshtein(q, s)
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private fun showReservePage() {
        isReserving = false
        isReservationPaused = false
        loginContainer.visibility = View.GONE
        reserveContainer.visibility = View.VISIBLE
        reserveFormContainer.visibility = View.VISIBLE
        reserveProgressContainer.visibility = View.GONE
        reserveSuccessContainer.visibility = View.GONE
        window.statusBarColor = ContextCompat.getColor(this, R.color.midnight)
    }

    private fun showDatePicker(targetButton: Button) {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (selectedDate.before(today)) {
            selectedDate.timeInMillis = today.timeInMillis
        }

        val year = selectedDate.get(Calendar.YEAR)
        val month = selectedDate.get(Calendar.MONTH)
        val day = selectedDate.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(this, { _, y, m, d ->
            selectedDate.set(y, m, d)
            updateDateButtonText(targetButton)
        }, year, month, day).apply {
            datePicker.minDate = today.timeInMillis
        }.show()
    }

    private fun updateDateButtonText(button: Button) {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        button.text = formatter.format(selectedDate.time)
    }

    private fun normalizeTime(input: String): String? {
        val digits = input.replace(":", "")
        if (digits.length != 4 || digits.any { !it.isDigit() }) return null
        val hour = digits.substring(0, 2).toIntOrNull() ?: return null
        val min = digits.substring(2, 4).toIntOrNull() ?: return null
        if (hour !in 0..23 || min !in 0..59) return null
        return String.format(Locale.KOREA, "%02d%02d00", hour, min)
    }

    private fun performLogout(bridge: PyObject) {
        isReserving = false
        isReservationPaused = false
        latestReservationNo = null
        countdownRunnable?.let { countdownHandler.removeCallbacks(it) }

        val stopIntent = Intent(this, ReservationForegroundService::class.java).apply {
            action = ReservationForegroundService.ACTION_STOP
        }
        startService(stopIntent)

        val result = callPython(bridge, "logout")
        if (result.startsWith("로그아웃 성공")) {
            Toast.makeText(this, "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
        }

        reserveContainer.visibility = View.GONE
        loginContainer.visibility = View.VISIBLE
        loginErrorText.visibility = View.GONE
        reserveProgressContainer.visibility = View.GONE
        reserveSuccessContainer.visibility = View.GONE
        reserveFormContainer.visibility = View.VISIBLE
        resultText.text = ""
    }

    private fun callPython(module: PyObject, functionName: String, vararg args: Any): String {
        return try {
            module.callAttr(functionName, *args).toString().also { result ->
                Log.d(TAG, "Python call success [$functionName], args=${args.contentToString()}, result=$result")
            }
        } catch (e: Exception) {
            "오류: ${e.message}".also { errorMessage ->
                Log.e(TAG, "Python call failed [$functionName], args=${args.contentToString()}, message=$errorMessage", e)
            }
        }
    }

    companion object {
        private const val TAG = "KorailMainActivity"
        private const val REQUEST_POST_NOTIFICATIONS = 2001

        private val LINE_TO_STATIONS: Map<String, Set<String>> = mapOf(
            "경부선" to setOf("경산", "경주", "광명", "구포", "김천(구미)", "대전", "동대구", "동탄", "물금", "밀양", "부산", "서대구", "서울", "수서", "수원", "영등포", "오송", "울산", "천안아산", "평택지제", "행신"),
            "호남선" to setOf("계룡", "공주", "광명", "광주송정", "김제", "나주", "논산", "동탄", "목포", "서대전", "서울", "수서", "오송", "용산", "익산", "장성", "정읍", "천안아산", "평택지제", "행신"),
            "경전선" to setOf("경산", "광명", "대전", "동대구", "마산", "밀양", "서대구", "서울", "오송", "진영", "진주", "창원", "창원중앙", "천안아산", "행신"),
            "전라선" to setOf("계룡", "곡성", "공주", "광명", "구례구", "남원", "논산", "서대전", "서울", "순천", "여수엑스포", "여천", "오송", "용산", "익산", "전주", "천안아산", "행신"),
            "동해선" to setOf("광명", "대전", "동대구", "서울", "오송", "천안아산", "포항", "행신"),
            "강릉선" to setOf("강릉", "덕소", "동해", "둔내", "만종", "묵호", "상봉", "서울", "서원주", "양평", "정동진", "진부", "청량리", "평창", "행신", "횡성")
        )

        private val STATION_TO_LINES: Map<String, Set<String>> = run {
            val map = mutableMapOf<String, MutableSet<String>>()
            for ((line, stations) in LINE_TO_STATIONS) {
                for (station in stations) map.getOrPut(station) { mutableSetOf() }.add(line)
            }
            map.mapValues { it.value.toSet() }
        }

        private val ALL_STATIONS: Set<String> = STATION_TO_LINES.keys
    }
}

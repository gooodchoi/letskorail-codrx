package com.example.letskorail

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var resultText: TextView
    private lateinit var loginErrorText: TextView
    private lateinit var loginContainer: LinearLayout
    private lateinit var reserveContainer: LinearLayout
    private lateinit var reserveFormContainer: LinearLayout
    private lateinit var reserveProgressContainer: LinearLayout
    private lateinit var startedAtText: TextView
    private lateinit var attemptCountText: TextView
    private lateinit var nextDelayText: TextView

    private lateinit var departureInput: AutoCompleteTextView
    private lateinit var arrivalInput: AutoCompleteTextView
    private lateinit var departureAdapter: ArrayAdapter<String>
    private lateinit var arrivalAdapter: ArrayAdapter<String>

    private var selectedDate: Calendar = Calendar.getInstance()

    @Volatile
    private var isReserving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        val reserveStartButton = findViewById<Button>(R.id.buttonStartReserve)

        resultText = findViewById(R.id.textResult)
        loginErrorText = findViewById(R.id.textLoginError)
        loginContainer = findViewById(R.id.loginContainer)
        reserveContainer = findViewById(R.id.reserveContainer)
        reserveFormContainer = findViewById(R.id.reserveFormContainer)
        reserveProgressContainer = findViewById(R.id.reserveProgressContainer)
        startedAtText = findViewById(R.id.textStartedAt)
        attemptCountText = findViewById(R.id.textAttemptCount)
        nextDelayText = findViewById(R.id.textNextDelay)

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
                resultText.text = "최소 출발 시간은 최대 출발 시간보다 늦을 수 없습니다."
                return@setOnClickListener
            }

            startReservationLoop(
                bridge = bridge,
                userId = idInput.text.toString(),
                password = pwInput.text.toString(),
                departure = departure,
                arrival = arrival,
                date = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(selectedDate.time),
                minTime = minTime,
                maxTime = maxTime,
                avgIntervalSec = avgInterval
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isReserving = false
    }

    private fun setupStationSelectors() {
        departureAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        arrivalAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())

        departureInput.setAdapter(departureAdapter)
        arrivalInput.setAdapter(arrivalAdapter)
        departureInput.threshold = 0
        arrivalInput.threshold = 0

        departureInput.addTextChangedListener(SimpleTextWatcher {
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
        })

        arrivalInput.addTextChangedListener(SimpleTextWatcher {
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
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

        departureInput.setOnItemClickListener { _, _, _, _ ->
            validatePairAndAdjust(arrivalInput, departureInput.text.toString().trim())
            refreshStationSuggestions(arrivalInput, departureInput, arrivalAdapter)
        }

        arrivalInput.setOnItemClickListener { _, _, _, _ ->
            validatePairAndAdjust(departureInput, arrivalInput.text.toString().trim())
            refreshStationSuggestions(departureInput, arrivalInput, departureAdapter)
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
            .sortedWith(
                compareBy<String> { stationScore(query, it) }
                    .thenBy { it.length }
                    .thenBy { it }
            )

        adapter.clear()
        adapter.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    private fun allowedStationsByOpposite(opposite: String): Set<String> {
        if (!ALL_STATIONS.contains(opposite)) {
            return ALL_STATIONS
        }

        val connected = linkedStations(opposite)
        return connected.ifEmpty { ALL_STATIONS }
    }

    private fun linkedStations(station: String): Set<String> {
        val lines = STATION_TO_LINES[station] ?: return emptySet()
        val linked = mutableSetOf<String>()
        for (line in lines) {
            linked.addAll(LINE_TO_STATIONS[line].orEmpty())
        }
        linked.remove(station)
        return linked
    }

    private fun areConnectedStations(a: String, b: String): Boolean {
        if (a == b) {
            return false
        }

        val linesA = STATION_TO_LINES[a].orEmpty()
        val linesB = STATION_TO_LINES[b].orEmpty()
        return linesA.intersect(linesB).isNotEmpty()
    }

    private fun stationScore(query: String, station: String): Int {
        if (query.isBlank()) {
            return 0
        }

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
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[a.length][b.length]
    }

    private fun showReservePage() {
        loginContainer.visibility = View.GONE
        reserveContainer.visibility = View.VISIBLE
    }

    private fun showDatePicker(targetButton: Button) {
        val year = selectedDate.get(Calendar.YEAR)
        val month = selectedDate.get(Calendar.MONTH)
        val day = selectedDate.get(Calendar.DAY_OF_MONTH)

        DatePickerDialog(this, { _, y, m, d ->
            selectedDate.set(y, m, d)
            updateDateButtonText(targetButton)
        }, year, month, day).show()
    }

    private fun updateDateButtonText(button: Button) {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        button.text = formatter.format(selectedDate.time)
    }

    private fun startReservationLoop(
        bridge: PyObject,
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
        reserveFormContainer.visibility = View.GONE
        reserveProgressContainer.visibility = View.VISIBLE

        val startedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
        startedAtText.text = "시작 시간: $startedAt"
        attemptCountText.text = "조회 시도: 0회"
        nextDelayText.text = "다음 조회 대기: -"
        resultText.text = "예매를 시작했습니다. 조건에 맞는 열차를 조회 중입니다."

        Thread {
            var attempts = 0
            while (isReserving) {
                attempts += 1
                runOnUiThread {
                    attemptCountText.text = "조회 시도: ${attempts}회"
                    resultText.text = "[$attempts] 조회 시도 중..."
                }

                val raw = callPython(
                    bridge,
                    "reserve_once",
                    userId,
                    password,
                    departure,
                    arrival,
                    date,
                    minTime,
                    maxTime
                )

                val parsed = parseReserveResponse(raw)
                runOnUiThread {
                    resultText.text = "[$attempts] ${parsed.second}"
                }

                if (parsed.first) {
                    isReserving = false
                    runOnUiThread {
                        nextDelayText.text = "다음 조회 대기: 완료"
                    }
                    break
                }

                val delaySec = randomizedDelay(avgIntervalSec)
                runOnUiThread {
                    nextDelayText.text = "다음 조회 대기: ${String.format(Locale.KOREA, "%.2f", delaySec)}초"
                }

                Thread.sleep(max(200L, (delaySec * 1000).toLong()))
            }
        }.start()
    }

    private fun randomizedDelay(avgSec: Double): Double {
        val lower = avgSec * 0.5
        val upper = avgSec * 1.5
        return Random.nextDouble(lower, upper)
    }

    private fun parseReserveResponse(raw: String): Pair<Boolean, String> {
        return try {
            val obj = JSONObject(raw)
            val success = obj.optBoolean("success", false)
            val message = obj.optString("message", "응답 메시지가 없습니다.")
            Pair(success, message)
        } catch (_: Exception) {
            Pair(false, raw)
        }
    }

    private fun normalizeTime(input: String): String? {
        val digits = input.replace(":", "")
        if (digits.length != 4 || digits.any { !it.isDigit() }) {
            return null
        }

        val hour = digits.substring(0, 2).toIntOrNull() ?: return null
        val min = digits.substring(2, 4).toIntOrNull() ?: return null
        if (hour !in 0..23 || min !in 0..59) {
            return null
        }

        return String.format(Locale.KOREA, "%02d%02d00", hour, min)
    }

    private fun callPython(module: PyObject, functionName: String, vararg args: Any): String {
        return try {
            module.callAttr(functionName, *args).toString()
        } catch (e: Exception) {
            "오류: ${e.message}"
        }
    }

    companion object {
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
                for (station in stations) {
                    map.getOrPut(station) { mutableSetOf() }.add(line)
                }
            }
            map.mapValues { it.value.toSet() }
        }

        private val ALL_STATIONS: Set<String> = STATION_TO_LINES.keys
    }
}

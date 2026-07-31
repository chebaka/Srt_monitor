package com.chebaka.srtmonitor

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private lateinit var store: ProfileStore
    private lateinit var status: TextView
    private lateinit var date: EditText
    private lateinit var timeFrom: EditText
    private lateinit var timeTo: EditText
    private lateinit var srtId: EditText
    private lateinit var srtPassword: EditText
    private lateinit var dep: EditText
    private lateinit var arr: EditText
    private lateinit var passengers: EditText
    private lateinit var cardNumber: EditText
    private lateinit var cardPassword: EditText
    private lateinit var cardExpire: EditText
    private lateinit var cardValidation: EditText
    private lateinit var special: CheckBox
    private lateinit var windowSeat: CheckBox
    private lateinit var autoPay: CheckBox
    private lateinit var profileName: EditText

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(MonitorService.EXTRA_STATUS) ?: ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = ProfileStore(this)
        createChannel()
        requestNotificationPermission()
        buildUi()
        loadSavedProfile()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, IntentFilter(MonitorService.ACTION_STATUS), RECEIVER_NOT_EXPORTED)
        else registerReceiver(statusReceiver, IntentFilter(MonitorService.ACTION_STATUS))
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }

    private fun edit(hint: String, password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        textSize = 16f
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f; setPadding(0, 14, 0, 2) }

    private fun buildUi() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 28) }
        content.addView(TextView(this).apply { text = "🚄 SRT Watch"; textSize = 28f })
        content.addView(label("프로필"))
        profileName = edit("프로필 이름 (예: 아내)"); content.addView(profileName)
        content.addView(label("SRT 계정"))
        srtId = edit("SRT 아이디"); content.addView(srtId)
        srtPassword = edit("SRT 비밀번호", true); content.addView(srtPassword)
        content.addView(label("열차 조건"))
        dep = edit("출발역"); content.addView(dep)
        arr = edit("도착역"); content.addView(arr)
        date = edit("탑승 날짜 YYYYMMDD"); date.isFocusable = false; date.setOnClickListener { pickDate() }; content.addView(date)
        timeFrom = edit("시작 시각 HHMMSS"); timeFrom.isFocusable = false; timeFrom.setOnClickListener { pickTime(timeFrom) }; content.addView(timeFrom)
        timeTo = edit("종료 시각 HHMMSS"); timeTo.isFocusable = false; timeTo.setOnClickListener { pickTime(timeTo) }; content.addView(timeTo)
        passengers = edit("성인 인원"); passengers.inputType = InputType.TYPE_CLASS_NUMBER; content.addView(passengers)
        special = CheckBox(this).apply { text = "특실" }; content.addView(special)
        windowSeat = CheckBox(this).apply { text = "창측 선호" }; content.addView(windowSeat)
        content.addView(label("자동결제 (선택)"))
        autoPay = CheckBox(this).apply { text = "좌석 발견 시 자동결제" }; content.addView(autoPay)
        cardNumber = edit("카드번호", true); content.addView(cardNumber)
        cardPassword = edit("카드 비밀번호 앞 2자리", true); content.addView(cardPassword)
        cardExpire = edit("유효기간 YYMM 예: 2808", true); content.addView(cardExpire)
        cardValidation = edit("개인 생년월일 YYMMDD / 법인 사업자번호", true); content.addView(cardValidation)
        status = TextView(this).apply { text = "대기 중"; textSize = 16f; setPadding(0, 24, 0, 24) }; content.addView(status)
        val save = Button(this).apply { text = "프로필 저장"; setOnClickListener { saveProfile() } }; content.addView(save)
        val start = Button(this).apply { text = "모니터링 시작"; setOnClickListener { startMonitor() } }; content.addView(start)
        val stop = Button(this).apply { text = "중지"; setOnClickListener { stopService(Intent(this@MainActivity, MonitorService::class.java)); status.text = "⏹️ 중지됨" } }; content.addView(stop)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun loadSavedProfile() {
        val c = store.activeProfile()?.let { store.load(it) } ?: return
        profileName.setText(store.activeProfile() ?: "")
        srtId.setText(c.srtId); srtPassword.setText(c.srtPassword); dep.setText(c.dep); arr.setText(c.arr)
        date.setText(c.date); timeFrom.setText(c.timeFrom); timeTo.setText(c.timeTo); passengers.setText(c.passengers.toString())
        special.isChecked = c.special; windowSeat.isChecked = c.windowSeat; autoPay.isChecked = c.autoPay
        cardNumber.setText(c.cardNumber); cardPassword.setText(c.cardPassword); cardExpire.setText(c.cardExpire); cardValidation.setText(c.cardValidation)
    }

    private fun config(): MonitorConfig {
        val name = profileName.text.toString().trim().ifEmpty { "기본" }
        val id = srtId.text.toString().trim()
        val password = srtPassword.text.toString()
        val departure = dep.text.toString().trim()
        val arrival = arr.text.toString().trim()
        val dateValue = date.text.toString()
        val fromValue = timeFrom.text.toString()
        val toValue = timeTo.text.toString()
        val passengerCount = passengers.text.toString().toIntOrNull()
        val normalizedCard = cardNumber.text.toString().filter { it.isDigit() }

        require(name.length <= 40) { "프로필 이름은 40자 이내로 입력해" }
        require(id.isNotEmpty() && password.isNotEmpty()) { "SRT 아이디와 비밀번호를 입력해" }
        require(departure.isNotEmpty() && arrival.isNotEmpty() && departure != arrival) { "출발역과 도착역을 확인해" }
        require(dateValue.matches(Regex("[0-9]{8}"))) { "탑승 날짜를 YYYYMMDD로 선택해" }
        require(fromValue.matches(Regex("[0-9]{6}")) && toValue.matches(Regex("[0-9]{6}"))) { "조회 시간을 HHMMSS로 선택해" }
        require(fromValue <= toValue) { "종료 시각은 시작 시각 이후여야 해" }
        require(passengerCount != null && passengerCount > 0) { "성인 인원은 1명 이상 입력해" }
        if (autoPay.isChecked) {
            require(normalizedCard.length in 12..19) { "카드번호를 확인해" }
            require(cardPassword.text.toString().matches(Regex("[0-9]{2}"))) { "카드 비밀번호 앞 2자리를 입력해" }
            require(cardExpire.text.toString().matches(Regex("[0-9]{4}"))) { "카드 유효기간을 YYMM으로 입력해" }
            require(cardValidation.text.toString().matches(Regex("[0-9]{6}|[0-9]{10}"))) { "카드 인증번호를 확인해" }
        }

        return MonitorConfig(
            id, password, departure, arrival, dateValue, fromValue, toValue,
            passengerCount, special.isChecked, windowSeat.isChecked, 30, 60,
            autoPay.isChecked, normalizedCard, cardPassword.text.toString(),
            cardExpire.text.toString(), cardValidation.text.toString()
        )
    }

    private fun validatedConfig(): MonitorConfig? = try {
        config()
    } catch (error: IllegalArgumentException) {
        val message = error.message ?: "입력값을 확인해"
        status.text = "⚠️ $message"
        AlertDialog.Builder(this).setTitle("입력 확인").setMessage(message).setPositiveButton("확인", null).show()
        null
    }

    private fun saveProfile() {
        val config = validatedConfig() ?: return
        store.save(profileName.text.toString().trim().ifEmpty { "기본" }, config)
        status.text = "✅ 프로필을 암호화해 저장했어"
    }

    private fun startMonitor() {
        val config = validatedConfig() ?: return
        store.save(profileName.text.toString().trim().ifEmpty { "기본" }, config)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        status.text = "🔐 로그인 준비 중"
    }

    private fun pickDate() { val c = Calendar.getInstance(); DatePickerDialog(this, { _, y, m, d -> date.setText("%04d%02d%02d".format(y, m + 1, d)) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show() }
    private fun pickTime(target: EditText) { val c = Calendar.getInstance(); TimePickerDialog(this, { _, h, m -> target.setText("%02d%02d00".format(h, m)) }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show() }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("srt_monitor", "SRT 모니터링", NotificationManager.IMPORTANCE_HIGH)) }
    private fun requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10) }
}

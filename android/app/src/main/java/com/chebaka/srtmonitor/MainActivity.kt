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
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var store: ProfileStore
    private lateinit var status: TextView
    private lateinit var statusCard: MaterialCardView
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
    private lateinit var autoPay: MaterialSwitch
    private lateinit var profileName: EditText
    private lateinit var paymentFields: LinearLayout
    private lateinit var routeFromLabel: TextView
    private lateinit var routeToLabel: TextView
    private lateinit var routeMeta: TextView
    private lateinit var railway: MaterialAutoCompleteTextView

    private val railwayOptions = listOf("SRT", "KORAIL")

    private val srtStationNames = listOf(
        "수서", "동탄", "평택지제", "곡성", "공주", "광주송정", "구례구", "김천(구미)",
        "나주", "남원", "대전", "동대구", "마산", "목포", "밀양", "부산", "서대구", "순천",
        "신경주", "경주", "여수EXPO", "여천", "오송", "울산(통도사)", "익산", "전주",
        "정읍", "진영", "진주", "창원", "창원중앙", "천안아산", "포항"
    )

    private val korailStationNames = listOf(
        "가남", "가평", "각계", "감곡장호원", "강경", "강구", "강릉", "강진",
        "강촌", "개포", "경산", "경주", "계룡", "고래불", "고한", "곡성",
        "공주", "광명", "광양", "광주", "광주송정", "광천", "구례구", "구미",
        "구포", "군북", "군산", "군위", "극락강", "근덕", "기성", "기장",
        "김제", "김천", "김천구미", "나전", "나주", "남성현", "남원", "남창",
        "남춘천", "논산", "능주", "다시", "단양", "대곡", "대구", "대야",
        "대전", "대천", "덕소", "도계", "도고온천", "도라산", "동대구", "동백산",
        "동탄", "동해", "둔내", "득량", "마산", "마석", "만종", "매곡",
        "매화", "명봉", "목포", "몽탄", "무안", "묵호", "문경", "문산",
        "물금", "민둥산", "밀양", "반성", "백양리", "백양사", "벌교", "별어곡",
        "보성", "봉양", "봉화", "부강", "부발", "부산", "부전", "북영천",
        "북울산", "북천", "분천", "비동", "사릉", "사북", "사상", "살미",
        "삼랑진", "삼례", "삼산", "삼척", "삼척해변", "삼탄", "삽교", "상동",
        "상봉", "상주", "서경주", "서광주", "서대구", "서대전", "서울", "서원주",
        "서정리", "서천", "서화성", "석불", "석포", "선평", "성환", "센텀",
        "송추", "수서", "수안보온천", "수원", "순천", "승부", "신기", "신동",
        "신례원", "신보성", "신창", "신탄진", "신태인", "신해운대", "심천", "쌍룡",
        "아산", "아우라지", "아화", "안강", "안동", "안양", "안중", "앙성온천",
        "약목", "양동", "양원", "양평", "여수EXPO", "여천", "연산", "연풍",
        "영덕", "영동", "영등포", "영암", "영월", "영주", "영천", "영해",
        "예당", "예미", "예산", "예천", "오근장", "오산", "오송", "오수",
        "옥산", "옥수", "옥원", "옥천", "온양온천", "완사", "왕십리", "왜관",
        "용궁", "용문", "용산", "운천", "울산(통도사)", "울진", "웅천", "원동",
        "원릉", "원주", "월포", "음성", "의성", "의정부", "이양", "이원",
        "익산", "인주", "인천공항T1", "인천공항T2", "일로", "일신", "일영", "임기",
        "임성리", "임실", "임원", "임진강", "장동", "장사", "장성", "장항",
        "장흥", "전남장흥", "전의", "전주", "점촌", "정동진", "정선", "정읍",
        "제천", "조성", "조치원", "주덕", "죽변", "중리", "증평", "지탄",
        "지평", "진례", "진부(오대산)", "진상", "진영", "진주", "창원", "창원중앙",
        "천안", "천안아산", "철암", "청도", "청량리", "청리", "청소", "청주",
        "청주공항", "청평", "추암", "추풍령", "춘양", "춘천", "충주", "태백",
        "태화강", "퇴계원", "판교(경기)", "판교(충남)", "평내호평", "평창", "평택", "평택지제",
        "평해", "포항", "풍기", "하동", "하양", "한림정", "함안", "함열",
        "함창", "함평", "합덕", "해남", "행신", "향남", "현동", "홍성",
        "화명", "화성시청", "화순", "황간", "횡성", "횡천", "효천", "후포",
        "흥부"
    )

    private val stationAdapters = mutableListOf<ArrayAdapter<String>>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderStatus(intent?.getStringExtra(MonitorService.EXTRA_STATUS) ?: "")
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

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun roundedBackground(fill: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private fun text(value: String, size: Float, colorId: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color(colorId))
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun field(label: String, password: Boolean = false, number: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            textSize = 16f
            includeFontPadding = false
            setTextColor(color(R.color.srt_on_surface))
            inputType = when {
                password && number -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        val wrapper = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            addView(input)
        }
        return wrapper to input
    }

    private fun profileField(savedProfiles: List<String>): Pair<TextInputLayout, EditText> {
        if (savedProfiles.isEmpty()) {
            val pair = field("프로필 이름  예: 아내")
            return Pair(pair.first, pair.second)
        }

        val input = MaterialAutoCompleteTextView(this).apply {
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            isFocusable = false
            isClickable = true
            isCursorVisible = false
            setTextColor(color(R.color.srt_on_surface))
            contentDescription = "저장된 프로필 선택"
            setAdapter(object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_dropdown_item_1line,
                savedProfiles.toMutableList()
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).apply {
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                        (this as? TextView)?.textSize = 15f
                    }
            })
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, position, _ ->
                (adapter?.getItem(position) as? String)?.let { loadProfile(it) }
            }
        }
        val wrapper = TextInputLayout(this).apply {
            hint = "저장된 프로필"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            setBoxCornerRadii(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setEndIconOnClickListener { input.showDropDown() }
            addView(input)
        }
        return Pair(wrapper, input)
    }

    private fun stationField(label: String): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            stationNamesForRailway().toMutableList()
        ) {
            private val stationFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val query = constraint?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    val stations = stationNamesForRailway()
                    val matches = if (query.isEmpty()) {
                        stations
                    } else {
                        stations.filter { it.lowercase(Locale.ROOT).contains(query) }
                    }
                    return FilterResults().apply {
                        values = matches
                        count = matches.size
                    }
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    clear()
                    @Suppress("UNCHECKED_CAST")
                    addAll((results?.values as? List<String>).orEmpty())
                    notifyDataSetChanged()
                }
            }

            override fun getFilter(): Filter = stationFilter

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    (this as? TextView)?.textSize = 15f
                }
        }
        stationAdapters += adapter
        val input = MaterialAutoCompleteTextView(this).apply {
            textSize = 16f
            includeFontPadding = false
            threshold = 0
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(color(R.color.srt_on_surface))
            setAdapter(adapter)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(text: Editable?) {
                    val query = text?.toString()?.trim().orEmpty()
                    post {
                        if (hasFocus() && query.isNotEmpty() && query !in stationNamesForRailway() && adapter.count > 0) {
                            showDropDown()
                        }
                    }
                }
            })
            setOnFocusChangeListener { _, focused -> if (focused) showDropDown() }
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, _, _ -> dismissDropDown() }
        }
        val wrapper = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            setBoxCornerRadii(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setEndIconOnClickListener { input.showDropDown() }
            addView(input)
        }
        return wrapper to input
    }

    private fun stationNamesForRailway(): List<String> =
        if (::railway.isInitialized && railway.text.toString() == "KORAIL") korailStationNames else srtStationNames

    private fun refreshStationOptions() {
        val stations = stationNamesForRailway()
        stationAdapters.forEach { adapter ->
            adapter.clear()
            adapter.addAll(stations)
            adapter.notifyDataSetChanged()
        }
        if (::dep.isInitialized && dep.text.toString() !in stations) dep.setText("")
        if (::arr.isInitialized && arr.text.toString() !in stations) arr.setText("")
        if (::routeFromLabel.isInitialized) updateRoutePreview()
    }

    private fun choiceField(label: String, options: List<String>): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
        val input = MaterialAutoCompleteTextView(this).apply {
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            isFocusable = false
            isClickable = true
            isCursorVisible = false
            setTextColor(color(R.color.srt_on_surface))
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, options.toMutableList()))
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, _, _ -> dismissDropDown() }
        }
        val wrapper = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            setBoxCornerRadii(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            setEndIconOnClickListener { input.showDropDown() }
            addView(input)
        }
        return wrapper to input
    }

    private fun updateRailwayCapabilities() {
        val isKorail = ::railway.isInitialized && railway.text.toString() == "KORAIL"
        refreshStationOptions()
        if (::windowSeat.isInitialized) {
            windowSeat.isEnabled = !isKorail
            if (isKorail) windowSeat.isChecked = false
        }
        if (::autoPay.isInitialized) {
            autoPay.isEnabled = !isKorail
            if (isKorail) autoPay.isChecked = false
            updatePaymentVisibility()
        }
    }

    private fun sectionCard(title: String, caption: String, content: LinearLayout.() -> Unit): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(color(R.color.srt_border))
            setCardBackgroundColor(color(R.color.srt_surface))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val sectionTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sectionTitle.addView(View(this).apply {
            background = roundedBackground(color(R.color.srt_primary), 2)
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(22))
        })
        sectionTitle.addView(text(title, 17f, R.color.srt_on_surface, true).apply {
            setPadding(dp(10), 0, 0, 0)
        })
        body.addView(sectionTitle)
        body.addView(text(caption, 13f, R.color.srt_secondary).apply {
            setPadding(0, dp(4), 0, 0)
        })
        body.content()
        card.addView(body)
        return card
    }

    private fun routePreview(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(R.color.srt_navy))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        body.addView(text("좌석 대기표", 12f, R.color.srt_on_navy_muted, true).apply {
            letterSpacing = 0.08f
        })
        val routeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }
        routeFromLabel = text("출발역", 22f, R.color.srt_on_navy, true)
        routeFromLabel.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        routeRow.addView(routeFromLabel)
        routeRow.addView(text("→", 22f, R.color.srt_on_navy, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(42), -2)
        })
        routeToLabel = text("도착역", 22f, R.color.srt_on_navy, true).apply {
            gravity = Gravity.END
        }
        routeToLabel.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        routeRow.addView(routeToLabel)
        body.addView(routeRow)
        routeMeta = text("탑승 날짜 · 조회 시간을 설정해", 13f, R.color.srt_on_navy_muted).apply {
            setPadding(0, dp(12), 0, 0)
        }
        body.addView(routeMeta)
        card.addView(body)
        return card
    }

    private fun bindRoutePreview(input: EditText) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateRoutePreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun updateRoutePreview() {
        if (!::routeFromLabel.isInitialized) return
        val departure = dep.text.toString().trim()
        val arrival = arr.text.toString().trim()
        routeFromLabel.text = departure.ifEmpty { "출발역" }
        routeToLabel.text = arrival.ifEmpty { "도착역" }
        val dateValue = date.text.toString()
        val dateText = if (dateValue.matches(Regex("[0-9]{8}"))) {
            "${dateValue.substring(0, 4)}.${dateValue.substring(4, 6)}.${dateValue.substring(6)}"
        } else {
            "탑승 날짜"
        }
        val fromValue = timeFrom.text.toString()
        val toValue = timeTo.text.toString()
        val timeText = if (fromValue.length >= 4 && toValue.length >= 4) {
            "조회 ${fromValue.substring(0, 2)}:${fromValue.substring(2, 4)} - ${toValue.substring(0, 2)}:${toValue.substring(2, 4)}"
        } else {
            "조회 시간"
        }
        routeMeta.text = "$dateText  ·  $timeText"
    }

    private fun pickerField(label: String, onClick: (EditText) -> Unit): Pair<TextInputLayout, TextInputEditText> {
        val pair = field(label)
        pair.second.apply {
            isFocusable = false
            isClickable = true
            setOnClickListener { onClick(this) }
        }
        return pair
    }

    private fun buildUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "RAIL"
            textSize = 18f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(color(R.color.srt_primary))
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(dp(52), -2)
        })
        val headerCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        headerCopy.addView(text("Watch", 24f, R.color.srt_navy, true))
        headerCopy.addView(text("SRT·KORAIL 좌석 알림 도우미", 13f, R.color.srt_secondary).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(headerCopy)
        content.addView(header)
        content.addView(routePreview())

        content.addView(sectionCard("철도 선택", "SRT 또는 KORAIL을 선택해") {
            val railwayField = choiceField("철도 운영사", railwayOptions)
            railway = railwayField.second
            railway.setText("SRT", false)
            railway.setOnItemClickListener { _, _, _, _ -> updateRailwayCapabilities() }
            addView(railwayField.first)
        })

        val savedProfiles = store.listProfiles()
        val profileCaption = if (savedProfiles.isEmpty()) {
            "처음 한 번 입력해 저장해"
        } else {
            "저장된 프로필을 목록에서 선택해"
        }
        content.addView(sectionCard("프로필", profileCaption) {
            val profile = profileField(savedProfiles)
            profileName = profile.second
            addView(profile.first)
        })

        content.addView(sectionCard("철도 계정", "계정 정보는 기기 안에서 암호화해 저장") {
            val id = field("회원번호·이메일·전화번호")
            srtId = id.second
            addView(id.first)
            val password = field("비밀번호", password = true)
            srtPassword = password.second
            addView(password.first)
        })

        content.addView(sectionCard("여행 조건", "역 이름을 정확히 입력해") {
            val departure = stationField("출발역")
            dep = departure.second
            bindRoutePreview(dep)
            addView(departure.first)
            val arrival = stationField("도착역")
            arr = arrival.second
            bindRoutePreview(arr)
            addView(arrival.first)

            val dateField = pickerField("탑승 날짜") { pickDate() }
            date = dateField.second
            bindRoutePreview(date)
            addView(dateField.first)

            val timeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            val fromField = pickerField("조회 시작") { pickTime(timeFrom) }
            timeFrom = fromField.second
            bindRoutePreview(timeFrom)
            fromField.first.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) }
            timeRow.addView(fromField.first)
            val toField = pickerField("조회 종료") { pickTime(timeTo) }
            timeTo = toField.second
            bindRoutePreview(timeTo)
            toField.first.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) }
            timeRow.addView(toField.first)
            addView(timeRow)

            val count = field("성인 인원", number = true)
            passengers = count.second
            addView(count.first)

            val options = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            }
            special = CheckBox(this@MainActivity).apply { text = "특실"; setTextColor(color(R.color.srt_on_surface)) }
            windowSeat = CheckBox(this@MainActivity).apply { text = "창측 선호"; setTextColor(color(R.color.srt_on_surface)) }
            options.addView(special, LinearLayout.LayoutParams(0, -2, 1f))
            options.addView(windowSeat, LinearLayout.LayoutParams(0, -2, 1f))
            addView(options)
        })

        content.addView(sectionCard("자동결제", "좌석을 확보한 뒤 결제까지 자동으로 진행") {
            autoPay = MaterialSwitch(this@MainActivity).apply {
                text = "좌석 발견 시 자동결제"
                textSize = 15f
                setTextColor(color(R.color.srt_on_surface))
                setOnCheckedChangeListener { _, _ -> updatePaymentVisibility() }
            }
            addView(autoPay)
            addView(text("SRT에서만 지원. KORAIL 자동결제는 검증 전 차단돼.", 12f, R.color.srt_secondary).apply {
                setPadding(0, dp(3), 0, 0)
            })

            paymentFields = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val number = field("카드번호", password = true, number = true)
            cardNumber = number.second
            paymentFields.addView(number.first)
            val password = field("카드 비밀번호 앞 2자리", password = true, number = true)
            cardPassword = password.second
            paymentFields.addView(password.first)
            val expire = field("유효기간 YYMM", password = true, number = true)
            cardExpire = expire.second
            paymentFields.addView(expire.first)
            val validation = field("개인 생년월일 YYMMDD 또는 법인번호", password = true, number = true)
            cardValidation = validation.second
            paymentFields.addView(validation.first)
            addView(paymentFields)
        })

        statusCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            setStrokeColor(color(R.color.srt_border))
        }
        val statusBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        statusBody.addView(text("현재 상태", 12f, R.color.srt_secondary, true))
        status = text("대기 중", 15f, R.color.srt_on_surface, true).apply {
            setPadding(0, dp(5), 0, 0)
        }
        statusBody.addView(status)
        statusCard.addView(statusBody)
        content.addView(statusCard)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        }
        val save = secondaryButton("프로필 저장") { saveProfile() }
        actions.addView(save, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(5) })
        val start = primaryButton("모니터링 시작") { startMonitor() }
        actions.addView(start, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(5) })
        content.addView(actions)

        val stop = secondaryButton("중지") {
            stopService(Intent(this@MainActivity, MonitorService::class.java))
            renderStatus("중지됨")
        }
        stop.layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) }
        content.addView(stop)

        renderStatus("대기 중")
        updateRailwayCapabilities()
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(color(R.color.srt_background))
            clipToPadding = false
            addView(content)
        })
    }

    private fun primaryButton(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        cornerRadius = dp(11)
        minHeight = dp(54)
        insetTop = 0
        insetBottom = 0
        setTextColor(color(R.color.srt_on_primary))
        backgroundTintList = ColorStateList.valueOf(color(R.color.srt_primary))
        setOnClickListener { action() }
    }

    private fun secondaryButton(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        cornerRadius = dp(11)
        minHeight = dp(50)
        insetTop = 0
        insetBottom = 0
        setTextColor(color(R.color.srt_on_surface))
        backgroundTintList = ColorStateList.valueOf(color(R.color.srt_surface))
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(color(R.color.srt_border))
        setOnClickListener { action() }
    }

    private fun updatePaymentVisibility() {
        if (::paymentFields.isInitialized) paymentFields.visibility = if (autoPay.isChecked) View.VISIBLE else View.GONE
    }

    private fun renderStatus(message: String) {
        if (!::status.isInitialized) return
        status.text = message
        val surface = when {
            message.contains("실패") || message.contains("오류") || message.contains("입력 확인") -> R.color.srt_error_surface
            message.contains("다음 조회 대기") || message.contains("확인해") || message.contains("결제 필요") -> R.color.srt_warning_surface
            message.contains("완료") || message.contains("성공") || message.contains("예약 발견") -> R.color.srt_success_surface
            else -> R.color.srt_surface_soft
        }
        statusCard.setCardBackgroundColor(color(surface))
    }

    private fun loadSavedProfile() {
        store.activeProfile()?.let { loadProfile(it) }
    }

    private fun loadProfile(name: String) {
        val c = store.load(name) ?: return
        store.setActiveProfile(name)
        profileName.setText(name)
        railway.setText(c.operator, false)
        srtId.setText(c.srtId); srtPassword.setText(c.srtPassword); dep.setText(c.dep); arr.setText(c.arr)
        date.setText(c.date); timeFrom.setText(c.timeFrom); timeTo.setText(c.timeTo); passengers.setText(c.passengers.toString())
        special.isChecked = c.special; windowSeat.isChecked = c.windowSeat; autoPay.isChecked = c.autoPay
        cardNumber.setText(c.cardNumber); cardPassword.setText(c.cardPassword); cardExpire.setText(c.cardExpire); cardValidation.setText(c.cardValidation)
        updateRailwayCapabilities()
        updatePaymentVisibility()
        updateRoutePreview()
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
        val operatorValue = railway.text.toString().trim()
        val passengerCount = passengers.text.toString().toIntOrNull()
        val normalizedCard = cardNumber.text.toString().filter { it.isDigit() }

        require(name.length <= 40) { "프로필 이름은 40자 이내로 입력해" }
        require(operatorValue in railwayOptions) { "철도 운영사를 선택해" }
        require(id.isNotEmpty() && password.isNotEmpty()) { "철도 계정과 비밀번호를 입력해" }
        require(departure.isNotEmpty() && arrival.isNotEmpty() && departure != arrival) { "출발역과 도착역을 확인해" }
        require(dateValue.matches(Regex("[0-9]{8}"))) { "탑승 날짜를 선택해" }
        require(fromValue.matches(Regex("[0-9]{6}")) && toValue.matches(Regex("[0-9]{6}"))) { "조회 시간을 선택해" }
        require(fromValue <= toValue) { "종료 시각은 시작 시각 이후여야 해" }
        require(passengerCount != null && passengerCount > 0) { "성인 인원은 1명 이상 입력해" }
        require(!windowSeat.isChecked || operatorValue == "SRT") { "KORAIL 창가 우선은 아직 지원하지 않아" }
        if (autoPay.isChecked) {
            require(operatorValue == "SRT") { "KORAIL 자동결제는 검증 전 지원하지 않아" }
            require(normalizedCard.length in 12..19) { "카드번호를 확인해" }
            require(cardPassword.text.toString().matches(Regex("[0-9]{2}"))) { "카드 비밀번호 앞 2자리를 입력해" }
            require(cardExpire.text.toString().matches(Regex("[0-9]{4}"))) { "카드 유효기간을 YYMM으로 입력해" }
            require(cardValidation.text.toString().matches(Regex("[0-9]{6}|[0-9]{10}"))) { "카드 인증번호를 확인해" }
        }

        return MonitorConfig(
            id, password, departure, arrival, dateValue, fromValue, toValue,
            passengerCount, special.isChecked, windowSeat.isChecked, 30, 60,
            autoPay.isChecked, normalizedCard, cardPassword.text.toString(),
            cardExpire.text.toString(), cardValidation.text.toString(), operator = operatorValue
        )
    }

    private fun validatedConfig(): MonitorConfig? = try {
        config()
    } catch (error: IllegalArgumentException) {
        val message = error.message ?: "입력값을 확인해"
        renderStatus("입력 확인: $message")
        AlertDialog.Builder(this).setTitle("입력 확인").setMessage(message).setPositiveButton("확인", null).show()
        null
    }

    private fun saveProfile() {
        val config = validatedConfig() ?: return
        store.save(profileName.text.toString().trim().ifEmpty { "기본" }, config)
        renderStatus("프로필을 암호화해 저장했어")
    }

    private fun startMonitor() {
        val config = validatedConfig() ?: return
        store.save(profileName.text.toString().trim().ifEmpty { "기본" }, config)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        renderStatus("로그인 준비 중")
    }

    private fun pickDate() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d -> date.setText("%04d%02d%02d".format(y, m + 1, d)) }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime(target: EditText) {
        val c = Calendar.getInstance()
        TimePickerDialog(this, { _, h, m -> target.setText("%02d%02d00".format(h, m)) }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("srt_monitor", "SRT 모니터링", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }
}

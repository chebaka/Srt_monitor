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
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var store: ProfileStore
    private lateinit var stationRepository: StationRepository
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
    private lateinit var special: CheckBox
    private lateinit var windowSeat: CheckBox
    private lateinit var autoPay: MaterialSwitch
    private lateinit var profileName: EditText
    private lateinit var routeFromLabel: TextView
    private lateinit var routeToLabel: TextView
    private lateinit var routeMeta: TextView
    private lateinit var railway: MaterialAutoCompleteTextView
    private lateinit var accountInput: MaterialAutoCompleteTextView
    private lateinit var dashboard: LinearLayout
    private lateinit var activeCount: TextView
    private var editingMonitorId: String = ""
    private var migratingFromId: String = ""
    private var selectedAccountId: String = ProfileStore.NEW_ACCOUNT_ID
    private var receiverRegistered = false

    private val railwayOptions = listOf("KORAIL+")

    private val srtStationNames = listOf(
        "수서", "동탄", "평택지제", "곡성", "공주", "광주송정", "구례구", "김천(구미)",
        "나주", "남원", "대전", "동대구", "마산", "목포", "밀양", "부산", "서대구", "순천",
        "신경주", "경주", "여수EXPO", "여천", "오송", "울산(통도사)", "익산", "전주",
        "정읍", "진영", "진주", "창원", "창원중앙", "천안아산", "포항"
    )

    @Volatile
    private var selectedRailway = ProfileStore.KORAIL_OPERATOR

    @Volatile
    private var korailStationNames = StationRepository.fallbackStations.map { it.name }

    private val srtStationRegions = linkedMapOf(
        "수도권" to listOf("수서", "동탄", "평택지제"),
        "충청권" to listOf("천안아산", "오송", "공주", "대전"),
        "전라권" to listOf("익산", "전주", "정읍", "광주송정", "나주", "목포", "곡성", "구례구", "남원", "순천", "여수EXPO", "여천"),
        "경상권" to listOf("김천(구미)", "동대구", "서대구", "신경주", "경주", "울산(통도사)", "포항", "밀양", "마산", "진영", "진주", "창원", "창원중앙", "부산")
    )

    private val korailStationRegions = linkedMapOf(
        "수도권" to listOf(
            "가남", "가평", "감곡장호원", "광명", "동탄", "마석", "문산", "부발", "사릉", "상봉", "서울", "서정리", "서화성", "성환",
            "송추", "수서", "수원", "안양", "안중", "양평", "영등포", "왕십리", "용산", "운천", "원릉", "의정부", "인천공항T1", "인천공항T2",
            "일영", "임진강", "청량리", "청평", "퇴계원", "평내호평", "평택", "평택지제", "행신", "향남", "옥수", "덕소", "용문", "지평"
        ),
        "강원권" to listOf(
            "강릉", "강촌", "고한", "나전", "남춘천", "동백산", "동해", "둔내", "묵호", "민둥산", "백양리", "사북", "삼척", "삼척해변",
            "서원주", "석포", "승부", "아우라지", "양동", "양원", "영월", "예미", "원주", "정동진", "정선", "제천", "진부(오대산)",
            "춘양", "춘천", "충주", "태백", "평창", "횡성", "만종", "도계", "쌍룡", "분천", "비동", "철암", "근덕", "신기", "추암"
        ),
        "충청권" to listOf(
            "각계", "강경", "계룡", "공주", "논산", "대전", "대천", "도고온천", "부강", "삼탄", "삽교", "서대전", "서천", "신례원", "신탄진",
            "심천", "아산", "연산", "영동", "오근장", "오송", "옥산", "옥천", "온양온천", "전의", "조치원", "증평", "지탄", "천안", "천안아산",
            "청주", "청주공항", "충주", "추풍령", "홍성", "황간", "성환", "청도"
        ),
        "전라권" to listOf(
            "강진", "광주", "광주송정", "극락강", "군산", "김제", "곡성", "광양", "구례구", "나주", "능주", "다시", "대야", "득량", "목포",
            "몽탄", "무안", "문평", "벌교", "보성", "백양사", "서광주", "선평", "삼례", "순천", "신보성", "신태인", "여수EXPO", "여천", "영암",
            "오수", "익산", "임성리", "임실", "장성", "장항", "장흥", "전남장흥", "전주", "정읍", "조성", "진상", "함열", "함평", "효천", "화순"
        ),
        "경상권" to listOf(
            "강구", "경산", "경주", "고래불", "구미", "구포", "군위", "기장", "김천", "남성현", "남창", "대구", "대곡", "동대구", "마산", "매화",
            "밀양", "물금", "부산", "부전", "북영천", "북울산", "삼랑진", "사상", "상동", "상주", "서경주", "센텀", "신동", "신해운대", "안강",
            "아화", "약목", "영덕", "영천", "영해", "옥산", "왜관", "용궁", "울산(통도사)", "울진", "웅천", "원동", "의성", "예천", "영주", "봉화",
            "점촌", "진례", "진영", "진주", "창원", "창원중앙", "청도", "청리", "태화강", "포항", "풍기", "하양", "한림정", "함안", "함창", "화명", "후포"
        )
    )

    private val regionHeaderPrefix = "\u0000"
    private val stationAdapters = mutableListOf<ArrayAdapter<String>>()
    private val stationInputs = mutableListOf<MaterialAutoCompleteTextView>()
    private val favoriteButtons = mutableListOf<MaterialButton>()
    private val stationPreferences by lazy { getSharedPreferences("station_preferences", Context.MODE_PRIVATE) }
    private val recentStationLimit = 5

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderStatus(intent?.getStringExtra(MonitorService.EXTRA_STATUS) ?: "")
            refreshDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = ProfileStore(this)
        stationRepository = StationRepository(this)
        createChannel()
        requestNotificationPermission()
        try {
            buildUi()
            loadSavedProfile()
            showMigrationNotice()
        } catch (error: SecureStoreException) {
            setContentView(text(error.message.orEmpty(), 16f, R.color.srt_on_surface).apply {
                setPadding(dp(24), dp(32), dp(24), dp(24)); setBackgroundColor(color(R.color.srt_error_surface))
            })
            return
        }
        stationRepository.refresh { stations ->
            korailStationNames = stations.map { it.name }
            refreshStationOptions()
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, IntentFilter(MonitorService.ACTION_STATUS), RECEIVER_NOT_EXPORTED)
        else registerReceiver(statusReceiver, IntentFilter(MonitorService.ACTION_STATUS))
        receiverRegistered = true
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(statusReceiver)
        stationRepository.close()
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

    private fun stationRegionsForRailway(excluded: Set<String> = emptySet()): List<Pair<String, List<String>>> {
        val allStations = stationNamesForRailway().filterNot { it in excluded }.toSet()
        val source = if (selectedRailway == "KORAIL") {
            korailStationRegions.toList()
        } else {
            srtStationRegions.toList()
        }
        val grouped = source.map { (region, stations) ->
            region to stations.filter { it in allStations }
        }.filter { it.second.isNotEmpty() }
        val groupedNames = grouped.flatMap { it.second }.toSet()
        val remaining = stationNamesForRailway().filter { it !in groupedNames && it !in excluded }
        return if (remaining.isEmpty()) grouped else grouped + ("기타" to remaining)
    }

    private fun isRegionHeader(value: String): Boolean = value.startsWith(regionHeaderPrefix)

    private fun railwayStationKey(): String = if (selectedRailway == "KORAIL") "korail" else "srt"

    private fun stationPreferenceKey(kind: String): String = "${railwayStationKey()}_$kind"

    private fun readStationList(kind: String): List<String> {
        val raw = stationPreferences.getString(stationPreferenceKey(kind), null) ?: return emptyList()
        return try {
            val values = JSONArray(raw)
            buildList(values.length()) {
                for (index in 0 until values.length()) {
                    values.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeStationList(kind: String, names: List<String>) {
        val values = JSONArray()
        names.distinct().forEach(values::put)
        stationPreferences.edit().putString(stationPreferenceKey(kind), values.toString()).apply()
    }

    private fun rememberStation(name: String) {
        if (name !in stationNamesForRailway()) return
        val updated = listOf(name) + readStationList("recent").filterNot { it == name }
        writeStationList("recent", updated.take(recentStationLimit))
    }

    private fun isFavoriteStation(name: String): Boolean = name in readStationList("favorites")

    private fun toggleFavoriteStation(name: String) {
        if (name !in stationNamesForRailway()) return
        val favorites = readStationList("favorites")
        writeStationList("favorites", if (name in favorites) favorites.filterNot { it == name } else listOf(name) + favorites)
    }

    private fun stationDropdownItems(query: String = ""): List<String> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val matches: (String) -> Boolean = { station ->
            normalized.isEmpty() || station.lowercase(Locale.ROOT).contains(normalized)
        }
        val allStations = stationNamesForRailway().toSet()
        val favorites = readStationList("favorites").filter { it in allStations && matches(it) }
        val recent = readStationList("recent").filter { it in allStations && it !in favorites && matches(it) }
        val priorityNames = (favorites + recent).distinct()
        val priorityItems = buildList {
            if (favorites.isNotEmpty()) add(regionHeaderPrefix + "즐겨찾기")
            addAll(favorites)
            if (recent.isNotEmpty()) add(regionHeaderPrefix + "최근 선택")
            addAll(recent)
        }
        val regionItems = stationRegionsForRailway(priorityNames.toSet()).flatMap { (region, stations) ->
            val matches = if (normalized.isEmpty()) {
                stations
            } else {
                stations.filter { it.lowercase(Locale.ROOT).contains(normalized) }
            }
            if (matches.isEmpty()) emptyList() else listOf(regionHeaderPrefix + region) + matches
        }
        return priorityItems + regionItems
    }

    private fun stationField(label: String): Pair<View, MaterialAutoCompleteTextView> {
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_dropdown_item_1line,
            stationDropdownItems().toMutableList()
        ) {
            private val stationFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    return FilterResults().apply {
                        values = stationDropdownItems(constraint?.toString().orEmpty())
                        count = (values as List<*>).size
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

            override fun isEnabled(position: Int): Boolean = !isRegionHeader(getItem(position).orEmpty())

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    val item = getItem(position).orEmpty()
                    if (isRegionHeader(item)) {
                        setPadding(dp(16), dp(14), dp(16), dp(6))
                        (this as? TextView)?.apply {
                            text = item.removePrefix(regionHeaderPrefix)
                            textSize = 13f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            setTextColor(color(R.color.srt_primary))
                        }
                    } else {
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        (this as? TextView)?.apply {
                            textSize = 15f
                            typeface = Typeface.DEFAULT
                            setTextColor(color(R.color.srt_on_surface))
                        }
                    }
                }
        }
        stationAdapters += adapter
        var favoriteButton: MaterialButton? = null
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
                    favoriteButton?.text = if (isFavoriteStation(query)) "★" else "☆"
                    post {
                        if (hasFocus() && query.isNotEmpty() && query !in stationNamesForRailway() && adapter.count > 0) {
                            showDropDown()
                        }
                    }
                }
            })
            setOnFocusChangeListener { _, focused -> if (focused) showDropDown() }
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, position, _ ->
                val selected = adapter.getItem(position).orEmpty()
                if (!isRegionHeader(selected)) {
                    rememberStation(selected)
                    favoriteButton?.text = if (isFavoriteStation(selected)) "★" else "☆"
                    refreshStationOptions()
                }
                dismissDropDown()
            }
        }
        val wrapper = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            setBoxCornerRadii(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setEndIconOnClickListener { input.showDropDown() }
            addView(input)
        }
        stationInputs += input
        favoriteButton = MaterialButton(this).apply {
            text = if (isFavoriteStation(input.text.toString())) "★" else "☆"
            textSize = 20f
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            contentDescription = "$label 즐겨찾기"
            layoutParams = LinearLayout.LayoutParams(dp(48), -2).apply { marginStart = dp(6) }
            setOnClickListener {
                val selected = input.text.toString().trim()
                if (selected in stationNamesForRailway()) {
                    toggleFavoriteStation(selected)
                    text = if (isFavoriteStation(selected)) "★" else "☆"
                    refreshStationOptions()
                }
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            addView(wrapper)
            addView(favoriteButton)
        }
        favoriteButtons += favoriteButton
        return row to input
    }

    private fun stationNamesForRailway(): List<String> =
        if (selectedRailway == "KORAIL") korailStationNames else srtStationNames

    private fun stationCode(name: String): String =
        if (selectedRailway == "KORAIL") {
            stationRepository.find(name)?.code.orEmpty()
        } else {
            ""
        }

    private fun refreshStationOptions() {
        val stations = stationNamesForRailway()
        stationAdapters.forEach { adapter ->
            adapter.clear()
            adapter.addAll(stationDropdownItems())
            adapter.notifyDataSetChanged()
        }
        stationInputs.zip(favoriteButtons).forEach { (input, button) ->
            button.text = if (isFavoriteStation(input.text.toString().trim())) "★" else "☆"
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
        selectedRailway = ProfileStore.KORAIL_OPERATOR
        refreshStationOptions()
        if (::windowSeat.isInitialized) {
            windowSeat.isEnabled = false
            windowSeat.isChecked = false
        }
        if (::autoPay.isInitialized) {
            autoPay.isChecked = false
            autoPay.isEnabled = false
            autoPay.text = "자동결제 준비 중"
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
        headerCopy.addView(text("KORAIL+ 좌석 알림 도우미", 13f, R.color.srt_secondary).apply {
            setPadding(0, dp(3), 0, 0)
        })
        header.addView(headerCopy)
        content.addView(header)
        content.addView(buildDashboard())
        content.addView(routePreview())

        content.addView(sectionCard("철도", "2026년 9월부터 KORAIL+만 지원") {
            val railwayField = choiceField("철도 서비스", railwayOptions)
            railway = railwayField.second
            railway.setText("KORAIL+", false)
            selectedRailway = ProfileStore.KORAIL_OPERATOR
            railway.setOnItemClickListener { _, _, _, _ ->
                selectedRailway = ProfileStore.KORAIL_OPERATOR
                updateRailwayCapabilities()
            }
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
            val accounts = activeAccounts()
            val labels = accounts.map(::accountLabel) + "새 계정 입력"
            val accountField = choiceField("저장된 계정", labels)
            accountInput = accountField.second
            accountInput.setOnItemClickListener { _, _, position, _ ->
                if (position < accounts.size) applyAccount(accounts[position]) else selectNewAccount()
            }
            addView(accountField.first)
            val id = field("회원번호·이메일·전화번호")
            srtId = id.second
            addView(id.first)
            val password = field("비밀번호", password = true)
            srtPassword = password.second
            addView(password.first)
            if (accounts.isNotEmpty()) applyAccount(accounts.first()) else selectNewAccount()
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
            windowSeat = CheckBox(this@MainActivity).apply { text = "창측 선호 (준비 중)"; setTextColor(color(R.color.srt_secondary)); isEnabled = false }
            options.addView(special, LinearLayout.LayoutParams(0, -2, 1f))
            options.addView(windowSeat, LinearLayout.LayoutParams(0, -2, 1f))
            addView(options)
        })

        content.addView(sectionCard("결제 방식", "0.2.0에서는 예약 후 KORAIL+ 공식 앱에서 결제") {
            autoPay = MaterialSwitch(this@MainActivity).apply {
                text = "자동결제 준비 중"
                textSize = 15f
                setTextColor(color(R.color.srt_on_surface))
                isChecked = false
                isEnabled = false
            }
            addView(autoPay)
            addView(text("카드정보를 저장하지 않아. 예약 알림에서 공식 결제 화면을 열어 결제해.", 12f, R.color.srt_secondary).apply {
                setPadding(0, dp(3), 0, 0)
            })

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
        val save = secondaryButton("감시 저장") { saveProfile() }
        actions.addView(save, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(5) })
        val start = primaryButton("모니터링 시작") { startMonitor() }
        actions.addView(start, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(5) })
        content.addView(actions)

        val stop = secondaryButton("선택 감시 중지") {
            if (editingMonitorId.isNotBlank()) sendServiceAction(MonitorService.ACTION_STOP_MONITOR, editingMonitorId)
            renderStatus("중지 요청됨")
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
        refreshDashboard()
    }

    private fun buildDashboard(): MaterialCardView = sectionCard("감시 대시보드", "저장 수 무제한 · 동시 최대 5개") {
        activeCount = text("활성 0/${ProfileStore.MAX_ACTIVE}", 15f, R.color.srt_navy, true)
        addView(activeCount)
        val bulk = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        bulk.addView(secondaryButton("전체 시작") { confirmStartAll() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        bulk.addView(secondaryButton("전체 중지") { sendServiceAction(MonitorService.ACTION_STOP_ALL) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        addView(bulk)
        val manage = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        manage.addView(secondaryButton("새 감시") { clearMonitorForm() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(4) })
        manage.addView(secondaryButton("계정 관리") { showAccounts() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(4) })
        addView(manage)
        dashboard = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        addView(dashboard)
    }

    private fun refreshDashboard() {
        if (!::dashboard.isInitialized) return
        val monitors = store.monitors()
        activeCount.text = "활성 ${monitors.count { it.active }}/${ProfileStore.MAX_ACTIVE}"
        dashboard.removeAllViews()
        if (monitors.isEmpty()) {
            dashboard.addView(text("저장된 감시 없음", 13f, R.color.srt_secondary))
            return
        }
        val accounts = store.accounts().associateBy { it.id }
        monitors.sortedBy { it.name }.forEach { monitor ->
            val account = accounts[monitor.accountId]
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(color(R.color.srt_surface_soft), 10)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }
            }
            val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            heading.addView(text(monitor.name, 15f, R.color.srt_on_surface, true), LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = MaterialSwitch(this).apply {
                isChecked = monitor.active
                contentDescription = "${monitor.name} 활성화"
                isEnabled = !monitor.legacyReadOnly && store.activationError(monitor) == null
                setOnCheckedChangeListener { _, checked ->
                    if (checked) confirmStart(monitor) else sendServiceAction(MonitorService.ACTION_STOP_MONITOR, monitor.id)
                }
            }
            heading.addView(toggle)
            box.addView(heading)
            val statusText = when {
                monitor.legacyReadOnly -> "읽기 전용 · KORAIL+ 전환 필요"
                account?.needsReauth == true -> "KORAIL+ 비밀번호 재입력 필요"
                else -> store.lastStatus(monitor.id)?.second ?: if (monitor.active) "시작 대기" else "중지됨"
            }
            val operatorLabel = if (monitor.legacyReadOnly) "SRT 레거시" else "KORAIL+"
            box.addView(text("$operatorLabel · ${monitor.dep} → ${monitor.arr} · ${monitor.date}\n$statusText", 12f, R.color.srt_secondary))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val uncertain = store.lastStatus(monitor.id)?.first in setOf("RESERVE_IN_FLIGHT", "UNCERTAIN")
            val editLabel = when {
                monitor.legacyReadOnly && monitors.any { it.migratedFromId == monitor.id } -> "전환 완료"
                monitor.legacyReadOnly -> "KORAIL+로 복사"
                uncertain -> "공식 확인 완료"
                else -> "편집"
            }
            actions.addView(secondaryButton(editLabel) {
                when {
                    monitor.legacyReadOnly && monitors.none { it.migratedFromId == monitor.id } -> prepareMigration(monitor)
                    uncertain -> AlertDialog.Builder(this@MainActivity)
                        .setTitle("예약내역 확인")
                        .setMessage("KORAIL+ 공식 앱에서 중복 예약이나 발권이 없는지 확인했어?")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("확인했어") { _, _ ->
                            store.clearReservationUncertainty(monitor.id)
                            refreshDashboard()
                        }.show()
                    !monitor.legacyReadOnly -> loadProfile(monitor.name)
                }
            }.apply { isEnabled = editLabel != "전환 완료" }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
            actions.addView(secondaryButton("삭제") {
                if (store.deleteMonitor(monitor.id)) refreshDashboard()
                else AlertDialog.Builder(this@MainActivity).setMessage("활성 감시는 먼저 중지해").setPositiveButton("확인", null).show()
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })
            box.addView(actions)
            dashboard.addView(box)
        }
    }

    private fun confirmStart(monitor: MonitorDefinition) {
        store.activationError(monitor)?.let {
            AlertDialog.Builder(this).setTitle("시작 불가").setMessage(it).setPositiveButton("확인", null).show()
            refreshDashboard()
            return
        }
        if (store.overlaps(monitor)) {
            AlertDialog.Builder(this).setTitle("중복 예약 가능성")
                .setMessage("같은 계정의 날짜·노선·시간이 겹치는 감시가 실행 중이야. 계속할까?")
                .setNegativeButton("취소") { _, _ -> refreshDashboard() }
                .setPositiveButton("계속") { _, _ -> sendServiceAction(MonitorService.ACTION_START_MONITOR, monitor.id) }.show()
        } else sendServiceAction(MonitorService.ACTION_START_MONITOR, monitor.id)
    }

    private fun confirmStartAll() {
        val all = store.monitors().filter { store.activationError(it) == null }
        val overlaps = all.indices.any { i -> (i + 1 until all.size).any { j ->
            val a = all[i]; val b = all[j]
            a.accountId == b.accountId && a.date == b.date && a.dep == b.dep && a.arr == b.arr &&
                a.timeFrom <= b.timeTo && b.timeFrom <= a.timeTo
        } }
        if (overlaps) AlertDialog.Builder(this).setTitle("중복 예약 가능성")
            .setMessage("겹치는 감시가 있어. 최대 5개를 계속 시작할까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("계속") { _, _ -> sendServiceAction(MonitorService.ACTION_START_ALL) }.show()
        else sendServiceAction(MonitorService.ACTION_START_ALL)
    }

    private fun sendServiceAction(action: String, monitorId: String = "") {
        val intent = Intent(this, MonitorService::class.java).apply {
            this.action = action
            if (monitorId.isNotBlank()) putExtra(MonitorService.EXTRA_MONITOR_ID, monitorId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun prepareMigration(source: MonitorDefinition) {
        if (!source.legacyReadOnly) return
        if (store.monitors().any { it.migratedFromId == source.id }) {
            renderStatus("이미 KORAIL+로 전환했어")
            return
        }
        editingMonitorId = ""
        migratingFromId = source.id
        val existingNames = store.monitors().map { it.name }.toSet()
        val base = "${source.name} KORAIL+"
        var targetName = base
        var suffix = 2
        while (targetName in existingNames) targetName = "$base $suffix".also { suffix += 1 }
        profileName.setText(targetName)
        activeAccounts().firstOrNull()?.let(::applyAccount) ?: selectNewAccount()
        dep.setText(source.dep); arr.setText(source.arr); date.setText(source.date)
        timeFrom.setText(source.timeFrom); timeTo.setText(source.timeTo)
        passengers.setText(source.passengers.toString()); special.isChecked = source.special
        windowSeat.isChecked = false; autoPay.isChecked = false
        updateRoutePreview()
        renderStatus("조건 복사 완료 · KORAIL+ 계정과 역을 확인해")
    }

    private fun showMigrationNotice() {
        if (!store.migrationNoticePending()) return
        val legacy = store.monitors().count { it.legacyReadOnly }
        val reauth = activeAccounts().count { it.needsReauth }
        AlertDialog.Builder(this)
            .setTitle("KORAIL+ 전환 완료")
            .setMessage("SRT 읽기 전용 감시 ${legacy}개 · 비밀번호 재입력 계정 ${reauth}개\n모든 감시와 자동결제를 중지했고 카드정보를 삭제했어.")
            .setPositiveButton("확인") { _, _ -> store.dismissMigrationNotice() }
            .setCancelable(false)
            .show()
    }

    private fun clearMonitorForm() {
        editingMonitorId = ""
        migratingFromId = ""
        profileName.setText("")
        activeAccounts().firstOrNull()?.let(::applyAccount) ?: selectNewAccount()
        dep.setText(""); arr.setText(""); date.setText(""); timeFrom.setText(""); timeTo.setText("")
        passengers.setText("1"); special.isChecked = false; windowSeat.isChecked = false; autoPay.isChecked = false
        renderStatus("새 감시 입력 중")
    }

    private fun accountLabel(account: RailAccount): String =
        "${account.name} · KORAIL+ · ${account.loginId.takeLast(4)}"

    private fun activeAccounts(): List<RailAccount> = store.accounts().filter { it.operator == ProfileStore.KORAIL_OPERATOR }

    private fun applyAccount(account: RailAccount) {
        selectedAccountId = account.id
        if (::accountInput.isInitialized) accountInput.setText(accountLabel(account), false)
        railway.setText("KORAIL+", false); selectedRailway = ProfileStore.KORAIL_OPERATOR
        srtId.setText(account.loginId); srtPassword.setText(if (account.needsReauth) "" else account.password)
        updateRailwayCapabilities()
    }

    private fun selectNewAccount() {
        selectedAccountId = ProfileStore.NEW_ACCOUNT_ID
        if (::accountInput.isInitialized) accountInput.setText("새 계정 입력", false)
        if (::srtId.isInitialized) { srtId.setText(""); srtPassword.setText("") }
    }

    private fun showAccounts() {
        val accounts = store.accounts()
        if (accounts.isEmpty()) {
            AlertDialog.Builder(this).setTitle("계정 관리").setMessage("저장된 계정 없음. 감시를 저장하면 계정도 암호화 저장돼.").setPositiveButton("확인", null).show()
            return
        }
        val labels = accounts.map { accountLabel(it).replace("KORAIL+", if (it.operator == ProfileStore.KORAIL_OPERATOR) "KORAIL+" else "SRT 레거시") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("계정 관리")
            .setItems(labels) { _, index ->
                val account = accounts[index]
                val used = store.monitors().count { it.accountId == account.id }
                AlertDialog.Builder(this).setTitle(account.name)
                    .setMessage("연결된 감시 ${used}개\n${if (account.operator == ProfileStore.KORAIL_OPERATOR) "계정 수정은 연결된 감시를 편집해 저장하면 반영돼." else "SRT 계정정보는 삭제됐고 읽기 전용이야."}")
                    .setNegativeButton("닫기", null)
                    .setPositiveButton("미사용 계정 삭제") { _, _ ->
                        if (!store.deleteAccount(account.id)) AlertDialog.Builder(this).setMessage("연결된 감시가 있어 삭제할 수 없어").setPositiveButton("확인", null).show()
                    }.show()
            }.setNegativeButton("닫기", null).show()
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
        val monitor = store.monitors().firstOrNull { it.name == name } ?: return
        if (monitor.legacyReadOnly) {
            prepareMigration(monitor)
            return
        }
        val c = store.configFor(monitor) ?: run {
            val account = store.accounts().firstOrNull { it.id == monitor.accountId } ?: return
            editingMonitorId = monitor.id
            migratingFromId = monitor.migratedFromId
            profileName.setText(monitor.name)
            applyAccount(account)
            dep.setText(monitor.dep); arr.setText(monitor.arr); date.setText(monitor.date)
            timeFrom.setText(monitor.timeFrom); timeTo.setText(monitor.timeTo); passengers.setText(monitor.passengers.toString())
            special.isChecked = monitor.special
            renderStatus("KORAIL+ 비밀번호를 다시 입력해")
            updateRoutePreview()
            return
        }
        editingMonitorId = c.monitorId
        migratingFromId = c.migratedFromId
        store.setActiveProfile(name)
        profileName.setText(name)
        railway.setText("KORAIL+", false)
        selectedRailway = ProfileStore.KORAIL_OPERATOR
        store.accounts().firstOrNull { it.id == c.accountId }?.let(::applyAccount)
        srtId.setText(c.srtId); srtPassword.setText(c.srtPassword); dep.setText(c.dep); arr.setText(c.arr)
        date.setText(c.date); timeFrom.setText(c.timeFrom); timeTo.setText(c.timeTo); passengers.setText(c.passengers.toString())
        special.isChecked = c.special; windowSeat.isChecked = false; autoPay.isChecked = false
        rememberStation(c.dep); rememberStation(c.arr)
        updateRailwayCapabilities()
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
        val operatorValue = ProfileStore.KORAIL_OPERATOR
        val departureCode = stationCode(departure)
        val arrivalCode = stationCode(arrival)
        val passengerCount = passengers.text.toString().toIntOrNull()

        require(name.length <= 40) { "프로필 이름은 40자 이내로 입력해" }
        require(railway.text.toString().trim() in railwayOptions) { "KORAIL+를 선택해" }
        require(departureCode.matches(Regex("[0-9]{4}")) && arrivalCode.matches(Regex("[0-9]{4}"))) { "KORAIL+ 역 목록에서 출발역과 도착역을 선택해" }
        require(id.isNotEmpty() && password.isNotEmpty()) { "철도 계정과 비밀번호를 입력해" }
        require(departure.isNotEmpty() && arrival.isNotEmpty() && departure != arrival) { "출발역과 도착역을 확인해" }
        require(dateValue.matches(Regex("[0-9]{8}"))) { "탑승 날짜를 선택해" }
        require(fromValue.matches(Regex("[0-9]{6}")) && toValue.matches(Regex("[0-9]{6}"))) { "조회 시간을 선택해" }
        require(fromValue <= toValue) { "종료 시각은 시작 시각 이후여야 해" }
        require(passengerCount != null && passengerCount > 0) { "성인 인원은 1명 이상 입력해" }

        return MonitorConfig(
            id, password, departure, arrival, dateValue, fromValue, toValue,
            passengerCount, special.isChecked, false, 30, 60,
            false, "", "", "", "", operator = operatorValue,
            depCode = departureCode, arrCode = arrivalCode, monitorId = editingMonitorId,
            accountId = selectedAccountId, migratedFromId = migratingFromId
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
        if (store.monitors().firstOrNull { it.id == editingMonitorId }?.active == true) {
            renderStatus("활성 감시는 중지한 뒤 편집해")
            return
        }
        val config = validatedConfig() ?: return
        val saved = try { store.save(profileName.text.toString().trim().ifEmpty { "기본" }, config) }
        catch (error: IllegalArgumentException) { renderStatus(error.message.orEmpty()); return }
        catch (error: SecureStoreException) { renderStatus(error.message.orEmpty()); return }
        editingMonitorId = saved.id
        renderStatus("프로필을 암호화해 저장했어")
        refreshDashboard()
    }

    private fun startMonitor() {
        val config = validatedConfig() ?: return
        if (store.monitors().firstOrNull { it.id == editingMonitorId }?.active == true) {
            renderStatus("이미 실행 중이야")
            return
        }
        val monitor = try { store.save(profileName.text.toString().trim().ifEmpty { "기본" }, config) }
        catch (error: IllegalArgumentException) { renderStatus(error.message.orEmpty()); return }
        catch (error: SecureStoreException) { renderStatus(error.message.orEmpty()); return }
        editingMonitorId = monitor.id
        confirmStart(monitor)
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
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("srt_monitor", "KORAIL+ 모니터링", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
    }
}

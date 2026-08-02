package com.chebaka.srtmonitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class RailStation(
    val name: String,
    val code: String
)

class StationRepository(context: Context) {
    private val preferences = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var closed = false

    @Volatile
    private var stations: List<RailStation> = loadCachedStations() ?: fallbackStations

    fun find(name: String): RailStation? = stations.firstOrNull { it.name == name }

    fun refresh(onUpdated: (List<RailStation>) -> Unit) {
        executor.execute {
            var latest = stations
            try {
                val fetchedAt = preferences.getLong(CACHE_TIME_KEY, 0L)
                if (System.currentTimeMillis() - fetchedAt >= CACHE_TTL_MS) {
                    val raw = fetchStationData()
                    latest = parseStations(raw)
                    stations = latest
                    preferences.edit()
                        .putString(CACHE_DATA_KEY, raw)
                        .putLong(CACHE_TIME_KEY, System.currentTimeMillis())
                        .apply()
                }
            } catch (_: Exception) {
                // Cached or bundled stations remain the safe fallback.
            }
            mainHandler.post {
                if (!closed) onUpdated(latest)
            }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun loadCachedStations(): List<RailStation>? {
        val raw = preferences.getString(CACHE_DATA_KEY, null) ?: return null
        return try { parseStations(raw) } catch (_: Exception) { null }
    }

    private fun fetchStationData(): String {
        val connection = (URL(STATION_DATA_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RailWatch/0.1 Android")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                throw IOException("station data HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_RESPONSE_BYTES) throw IOException("station data too large")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseStations(raw: String): List<RailStation> {
        val array = JSONObject(raw).getJSONObject("stns").getJSONArray("stn")
        val result = ArrayList<RailStation>(array.length())
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val name = item.optString("stn_nm").trim()
            val code = item.optString("stn_cd").trim()
            if (name.isNotEmpty() && code.matches(STATION_CODE_REGEX)) {
                result += RailStation(name, code)
            }
        }
        return result.distinctBy { it.code }.ifEmpty { throw IOException("empty station data") }
    }

    companion object {
        private const val CACHE_NAME = "korail_station_cache"
        private const val CACHE_DATA_KEY = "station_data"
        private const val CACHE_TIME_KEY = "station_data_fetched_at"
        private const val STATION_DATA_URL = "https://smart.letskorail.com/classes/com.korail.mobile.common.stationdata"
        private const val NETWORK_TIMEOUT_MS = 10_000
        private const val MAX_RESPONSE_BYTES = 1_000_000
        private const val BUFFER_SIZE = 8_192
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private val STATION_CODE_REGEX = Regex("[0-9]{4}")

        // Official stationdata snapshot used when the device is offline.
        val fallbackStations: List<RailStation> = listOf(
            RailStation("가남", "0530"),
            RailStation("가평", "0150"),
            RailStation("각계", "0309"),
            RailStation("감곡장호원", "0531"),
            RailStation("강경", "0028"),
            RailStation("강구", "0522"),
            RailStation("강릉", "0115"),
            RailStation("강진", "0566"),
            RailStation("강촌", "0151"),
            RailStation("개포", "0160"),
            RailStation("경산", "0024"),
            RailStation("경주", "0508"),
            RailStation("계룡", "0218"),
            RailStation("고래불", "0610"),
            RailStation("고한", "0122"),
            RailStation("곡성", "0049"),
            RailStation("공주", "0514"),
            RailStation("광명", "0501"),
            RailStation("광양", "0068"),
            RailStation("광주", "0042"),
            RailStation("광주송정", "0036"),
            RailStation("광천", "0082"),
            RailStation("구례구", "0050"),
            RailStation("구미", "0013"),
            RailStation("구포", "0019"),
            RailStation("군북", "0061"),
            RailStation("군산", "0505"),
            RailStation("군위", "0548"),
            RailStation("극락강", "0043"),
            RailStation("근덕", "0600"),
            RailStation("기성", "0607"),
            RailStation("기장", "0187"),
            RailStation("김제", "0031"),
            RailStation("김천", "0012"),
            RailStation("김천구미", "0507"),
            RailStation("나전", "0201"),
            RailStation("나주", "0037"),
            RailStation("남성현", "0317"),
            RailStation("남원", "0048"),
            RailStation("남창", "0186"),
            RailStation("남춘천", "0152"),
            RailStation("논산", "0027"),
            RailStation("능주", "0132"),
            RailStation("다시", "0266"),
            RailStation("단양", "0096"),
            RailStation("대곡", "0392"),
            RailStation("대구", "0023"),
            RailStation("대야", "0528"),
            RailStation("대전", "0010"),
            RailStation("대천", "0083"),
            RailStation("덕소", "0168"),
            RailStation("도계", "0111"),
            RailStation("도고온천", "0077"),
            RailStation("도라산", "0403"),
            RailStation("동대구", "0015"),
            RailStation("동백산", "0450"),
            RailStation("동탄", "0552"),
            RailStation("동해", "0113"),
            RailStation("둔내", "0517"),
            RailStation("득량", "0205"),
            RailStation("마산", "0059"),
            RailStation("마석", "0147"),
            RailStation("만종", "0326"),
            RailStation("매곡", "0249"),
            RailStation("매화", "0606"),
            RailStation("명봉", "0235"),
            RailStation("목포", "0041"),
            RailStation("몽탄", "0229"),
            RailStation("무안", "0236"),
            RailStation("묵호", "0114"),
            RailStation("문경", "0547"),
            RailStation("문산", "0401"),
            RailStation("물금", "0224"),
            RailStation("민둥산", "0120"),
            RailStation("밀양", "0017"),
            RailStation("반성", "0062"),
            RailStation("백양리", "0258"),
            RailStation("백양사", "0034"),
            RailStation("벌교", "0089"),
            RailStation("별어곡", "0198"),
            RailStation("보성", "0069"),
            RailStation("봉양", "0175"),
            RailStation("봉화", "0105"),
            RailStation("부강", "0008"),
            RailStation("부발", "0529"),
            RailStation("부산", "0020"),
            RailStation("부전", "0190"),
            RailStation("북영천", "0222"),
            RailStation("북울산", "0535"),
            RailStation("북천", "0064"),
            RailStation("분천", "0166"),
            RailStation("비동", "0636"),
            RailStation("사릉", "0255"),
            RailStation("사북", "0121"),
            RailStation("사상", "0143"),
            RailStation("살미", "0544"),
            RailStation("삼랑진", "0018"),
            RailStation("삼례", "0044"),
            RailStation("삼산", "0250"),
            RailStation("삼척", "0445"),
            RailStation("삼척해변", "0299"),
            RailStation("삼탄", "0213"),
            RailStation("삽교", "0080"),
            RailStation("상동", "0272"),
            RailStation("상봉", "0635"),
            RailStation("상주", "0156"),
            RailStation("서경주", "0533"),
            RailStation("서광주", "0275"),
            RailStation("서대구", "0506"),
            RailStation("서대전", "0025"),
            RailStation("서울", "0001"),
            RailStation("서원주", "0524"),
            RailStation("서정리", "0243"),
            RailStation("서천", "0086"),
            RailStation("서화성", "0537"),
            RailStation("석불", "0325"),
            RailStation("석포", "0108"),
            RailStation("선평", "0199"),
            RailStation("성환", "0248"),
            RailStation("센텀", "0455"),
            RailStation("송추", "0425"),
            RailStation("수서", "0551"),
            RailStation("수안보온천", "0545"),
            RailStation("수원", "0003"),
            RailStation("순천", "0051"),
            RailStation("승부", "0161"),
            RailStation("신기", "0263"),
            RailStation("신동", "0223"),
            RailStation("신례원", "0078"),
            RailStation("신보성", "0569"),
            RailStation("신창", "0281"),
            RailStation("신탄진", "0009"),
            RailStation("신태인", "0032"),
            RailStation("신해운대", "0127"),
            RailStation("심천", "0245"),
            RailStation("쌍룡", "0116"),
            RailStation("아산", "0503"),
            RailStation("아우라지", "0202"),
            RailStation("아화", "0339"),
            RailStation("안강", "0534"),
            RailStation("안동", "0526"),
            RailStation("안양", "0135"),
            RailStation("안중", "0540"),
            RailStation("앙성온천", "0532"),
            RailStation("약목", "0230"),
            RailStation("양동", "0171"),
            RailStation("양원", "0731"),
            RailStation("양평", "0091"),
            RailStation("여수EXPO", "0053"),
            RailStation("여천", "0139"),
            RailStation("연산", "0026"),
            RailStation("연풍", "0546"),
            RailStation("영덕", "0523"),
            RailStation("영동", "0011"),
            RailStation("영등포", "0002"),
            RailStation("영암", "0564"),
            RailStation("영월", "0117"),
            RailStation("영주", "0098"),
            RailStation("영천", "0103"),
            RailStation("영해", "0611"),
            RailStation("예당", "0075"),
            RailStation("예미", "0119"),
            RailStation("예산", "0079"),
            RailStation("예천", "0162"),
            RailStation("오근장", "0134"),
            RailStation("오산", "0141"),
            RailStation("오송", "0297"),
            RailStation("오수", "0047"),
            RailStation("옥산", "0154"),
            RailStation("옥수", "0892"),
            RailStation("옥원", "0602"),
            RailStation("옥천", "0022"),
            RailStation("온양온천", "0076"),
            RailStation("완사", "0484"),
            RailStation("왕십리", "0836"),
            RailStation("왜관", "0014"),
            RailStation("용궁", "0159"),
            RailStation("용문", "0169"),
            RailStation("용산", "0104"),
            RailStation("운천", "0733"),
            RailStation("울산(통도사)", "0509"),
            RailStation("울진", "0605"),
            RailStation("웅천", "0527"),
            RailStation("원동", "0215"),
            RailStation("원릉", "0419"),
            RailStation("원주", "0525"),
            RailStation("월포", "0520"),
            RailStation("음성", "0072"),
            RailStation("의성", "0101"),
            RailStation("의정부", "0264"),
            RailStation("이양", "0055"),
            RailStation("이원", "0300"),
            RailStation("익산", "0030"),
            RailStation("인주", "0541"),
            RailStation("인천공항T1", "0921"),
            RailStation("인천공항T2", "0923"),
            RailStation("일로", "0040"),
            RailStation("일신", "0204"),
            RailStation("일영", "0422"),
            RailStation("임기", "0165"),
            RailStation("임성리", "0362"),
            RailStation("임실", "0046"),
            RailStation("임원", "0601"),
            RailStation("임진강", "0402"),
            RailStation("장동", "0568"),
            RailStation("장사", "0521"),
            RailStation("장성", "0035"),
            RailStation("장항", "0504"),
            RailStation("장흥", "0423"),
            RailStation("전남장흥", "0567"),
            RailStation("전의", "0006"),
            RailStation("전주", "0045"),
            RailStation("점촌", "0158"),
            RailStation("정동진", "0262"),
            RailStation("정선", "0200"),
            RailStation("정읍", "0033"),
            RailStation("제천", "0093"),
            RailStation("조성", "0088"),
            RailStation("조치원", "0007"),
            RailStation("주덕", "0138"),
            RailStation("죽변", "0604"),
            RailStation("중리", "0234"),
            RailStation("증평", "0071"),
            RailStation("지탄", "0308"),
            RailStation("지평", "0170"),
            RailStation("진례", "0511"),
            RailStation("진부(오대산)", "0519"),
            RailStation("진상", "0066"),
            RailStation("진영", "0056"),
            RailStation("진주", "0063"),
            RailStation("창원", "0057"),
            RailStation("창원중앙", "0512"),
            RailStation("천안", "0005"),
            RailStation("천안아산", "0502"),
            RailStation("철암", "0109"),
            RailStation("청도", "0016"),
            RailStation("청량리", "0090"),
            RailStation("청리", "0155"),
            RailStation("청소", "0253"),
            RailStation("청주", "0070"),
            RailStation("청주공항", "0276"),
            RailStation("청평", "0149"),
            RailStation("추암", "0270"),
            RailStation("추풍령", "0133"),
            RailStation("춘양", "0106"),
            RailStation("춘천", "0153"),
            RailStation("충주", "0073"),
            RailStation("태백", "0123"),
            RailStation("태화강", "0125"),
            RailStation("퇴계원", "0146"),
            RailStation("판교(경기)", "0536"),
            RailStation("판교(충남)", "0085"),
            RailStation("평내호평", "0256"),
            RailStation("평창", "0518"),
            RailStation("평택", "0004"),
            RailStation("평택지제", "0553"),
            RailStation("평해", "0608"),
            RailStation("포항", "0515"),
            RailStation("풍기", "0097"),
            RailStation("하동", "0065"),
            RailStation("하양", "0238"),
            RailStation("한림정", "0129"),
            RailStation("함안", "0060"),
            RailStation("함열", "0029"),
            RailStation("함창", "0157"),
            RailStation("함평", "0039"),
            RailStation("합덕", "0542"),
            RailStation("해남", "0565"),
            RailStation("행신", "0390"),
            RailStation("향남", "0539"),
            RailStation("현동", "0107"),
            RailStation("홍성", "0081"),
            RailStation("화명", "0210"),
            RailStation("화성시청", "0538"),
            RailStation("화순", "0054"),
            RailStation("황간", "0128"),
            RailStation("횡성", "0516"),
            RailStation("횡천", "0136"),
            RailStation("효천", "0274"),
            RailStation("후포", "0609"),
            RailStation("흥부", "0603")
        )
    }
}

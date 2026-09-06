package com.chebaka.srtmonitor

import android.Manifest
import android.widget.DatePicker
import android.widget.EditText
import android.widget.TimePicker
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.PickerActions
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.instanceOf
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorFlowTest {

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private fun withLayoutHint(hint: String): Matcher<android.view.View> =
        object : TypeSafeMatcher<android.view.View>() {
            override fun describeTo(description: Description) {
                description.appendText("with TextInputLayout hint: $hint")
            }

            override fun matchesSafely(view: android.view.View): Boolean =
                (view as? TextInputLayout)?.hint?.toString() == hint
        }

    private fun fieldByHint(hint: String) = onView(
        allOf(
            isDescendantOfA(withLayoutHint(hint)),
            instanceOf(EditText::class.java)
        )
    )

    private fun stationByHint(hint: String) = onView(
        allOf(
            isDescendantOfA(withLayoutHint(hint)),
            instanceOf(MaterialAutoCompleteTextView::class.java)
        )
    )

    private fun tapMonitor(testName: String) {
        onView(withContentDescription("$testName 활성화")).perform(scrollTo(), object : androidx.test.espresso.ViewAction {
            override fun getConstraints(): Matcher<android.view.View> = allOf(
                isDisplayed(), androidx.test.espresso.matcher.ViewMatchers.isEnabled(),
                isAssignableFrom(android.widget.CompoundButton::class.java)
            )
            override fun getDescription() = "tap visible monitor switch"
            override fun perform(controller: androidx.test.espresso.UiController, view: android.view.View) {
                assertFalse((view as android.widget.CompoundButton).isChecked)
                val bounds = android.graphics.Rect()
                assertTrue(view.getGlobalVisibleRect(bounds))
                androidx.test.espresso.action.GeneralClickAction(
                    androidx.test.espresso.action.Tap.SINGLE,
                    { floatArrayOf(bounds.exactCenterX(), bounds.exactCenterY()) },
                    androidx.test.espresso.action.Press.FINGER,
                    android.view.InputDevice.SOURCE_TOUCHSCREEN, android.view.MotionEvent.BUTTON_PRIMARY
                ).perform(controller, view)
                controller.loopMainThreadUntilIdle()
                assertTrue("visible switch tap did not toggle", view.isChecked)
            }
        })
    }

    @Test
    fun autoPayMonitor_saves_and_cancelNeverStartsPayment() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val setupStore = ProfileStore(context)
        val testName = "offline-${System.currentTimeMillis()}"
        val previousProfile = setupStore.activeProfile()

        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("새 감시")).perform(scrollTo(), click())
            stationByHint("저장된 계정").perform(scrollTo(), click())
            onView(withText("새 계정 입력")).inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup()).perform(click())
            fieldByHint("프로필 이름  예: 아내").perform(scrollTo(), replaceText(testName), closeSoftKeyboard())
            fieldByHint("회원번호·이메일·전화번호").perform(scrollTo(), replaceText("$testName@example.invalid"), closeSoftKeyboard())
            fieldByHint("비밀번호").perform(scrollTo(), replaceText("fakepassword12"), closeSoftKeyboard())
            stationByHint("출발역").perform(scrollTo(), replaceText("수서"), closeSoftKeyboard())
            stationByHint("도착역").perform(scrollTo(), replaceText("평택지제"), closeSoftKeyboard())
            fieldByHint("탑승 날짜").perform(scrollTo(), click())
            onView(isAssignableFrom(DatePicker::class.java)).inRoot(isDialog())
                .perform(PickerActions.setDate(2026, 9, 21))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            fieldByHint("조회 시작").perform(scrollTo(), click())
            onView(isAssignableFrom(TimePicker::class.java)).inRoot(isDialog())
                .perform(PickerActions.setTime(16, 0))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            fieldByHint("조회 종료").perform(scrollTo(), click())
            onView(isAssignableFrom(TimePicker::class.java)).inRoot(isDialog())
                .perform(PickerActions.setTime(17, 59))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            fieldByHint("성인 인원").perform(scrollTo(), replaceText("1"), closeSoftKeyboard())
            onView(withText("자동 예약 후 카드결제")).perform(scrollTo(), click())
            fieldByHint("최대 결제금액 (원)").perform(scrollTo(), replaceText("10000"), closeSoftKeyboard())
            fieldByHint("카드번호 (숫자만)").perform(scrollTo(), replaceText("4111111111111111"), closeSoftKeyboard())
            fieldByHint("카드 비밀번호 앞 2자리").perform(scrollTo(), replaceText("12"), closeSoftKeyboard())
            fieldByHint("카드 유효기간 YYMM").perform(scrollTo(), replaceText("2912"), closeSoftKeyboard())
            fieldByHint("생년월일 YYMMDD / 사업자번호").perform(scrollTo(), replaceText("900101"), closeSoftKeyboard())
            fieldByHint("열차번호 고정 (예: 345, 비우면 시간대 전체)").perform(scrollTo(), replaceText("345"), closeSoftKeyboard())
            onView(withText("고속열차(KTX-산천 등) 자동 처리 허용")).perform(scrollTo(), click())
            onView(withText("감시 저장")).perform(scrollTo(), click())
            scenario.onActivity { activity ->
                val statusField = MainActivity::class.java.getDeclaredField("status").apply { isAccessible = true }
                val message = (statusField.get(activity) as android.widget.TextView).text.toString()
                assertTrue("save result: $message", setupStore.monitors().any { it.name == testName })
            }
            onView(withText(containsString("345편 고정"))).perform(scrollTo()).check(matches(isDisplayed()))
            tapMonitor(testName)
            onView(withText("예약·결제 1회 승인")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("취소")).inRoot(isDialog()).perform(click())

            val store = ProfileStore(context)
            val monitor = store.monitors().first { it.name == testName }
            assertFalse(monitor.active)
            assertTrue(monitor.autoPay)
            assertEquals("345", monitor.trainNo)
            assertNotEquals("PAID", store.lastStatus(monitor.id)?.first)
        } } finally {
            setupStore.monitors().filter { it.name == testName }.forEach {
                setupStore.deleteMonitor(it.id)
                setupStore.deleteAccount(it.accountId)
            }
            previousProfile?.let(setupStore::setActiveProfile)
        }
    }

    @Test
    fun livePayment_fromPrivateInput() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue("Explicit live-payment approval required",
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("livePaymentApproval") == "20260921-one-ticket")
        val context = instrumentation.targetContext
        var store: ProfileStore? = null
        var monitorId: String? = null
        var lastCode = "NOT_STARTED"
        // Espresso's default failure handler dumps the hierarchy, including credential fields.
        androidx.test.espresso.Espresso.setFailureHandler { error, _ ->
            throw AssertionError("Live UI failure: ${error.javaClass.simpleName}")
        }
        try {
            val inputFile = java.io.File(context.filesDir, "live-payment-input.json")
            val started = java.io.File(context.filesDir, "live-payment-started-20260921")
            check(!started.exists()) { "Prior live attempt must be reconciled first" }
            check(inputFile.isFile && inputFile.length() in 1..16384)
            val input = JSONObject(inputFile.readText(Charsets.UTF_8))
            val trainNo = input.getString("trainNo")
            check(trainNo.matches(Regex("[0-9]{1,5}")) && trainNo.toInt() !in setOf(345, 633))
            val maxFare = input.getInt("maxFareWon")
            check(maxFare in 1..10000)
            check(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")) <= java.time.LocalDate.of(2026, 9, 21))
            val config = MonitorConfig(
                srtId = input.getString("srtId"), srtPassword = input.getString("srtPassword"),
                dep = "수서", arr = "평택지제", date = "20260921", timeFrom = "220000", timeTo = "235000",
                passengers = 1, special = false, windowSeat = false, pollMin = 30, pollMax = 30,
                autoPay = true, cardNumber = input.getString("cardNumber"),
                cardPassword = input.getString("cardPassword"), cardExpire = input.getString("cardExpire"),
                cardValidation = input.getString("cardValidation"), depCode = "0551", arrCode = "0553",
                maxFareWon = maxFare, trainNo = trainNo, allowHighSpeedAuto = true,
            )
            val liveStore = ProfileStore(context)
            store = liveStore
            check(liveStore.monitors().none { it.active })
            val monitor = liveStore.save("실결제 검증 20260921", config)
            monitorId = monitor.id
            check(liveStore.activationError(monitor) == null)
            check(inputFile.delete()) { "Private plaintext input cleanup failed" }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                tapMonitor(monitor.name)
                onView(withText("예약·결제 1회 승인")).inRoot(isDialog()).check(matches(isDisplayed()))
                // This marker intentionally survives errors, timeouts and test reruns.
                check(started.createNewFile())
                java.io.FileOutputStream(started).use { it.fd.sync() }
                onView(withText("예약·결제 1회 승인")).inRoot(isDialog()).perform(click())
                val terminal = setOf("PAID", "ERROR", "EXPIRED", "STOPPED", "UNCERTAIN", "API_INCOMPATIBLE",
                    "AUTH_REJECTED", "AUTH_REQUIRED", "AUTH_UNVERIFIED", "COMPLETED", "SEAT_FOUND", "LEGACY_DISABLED")
                val deadline = android.os.SystemClock.elapsedRealtime() + 240_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    lastCode = liveStore.lastStatus(monitor.id)?.first.orEmpty()
                    if (lastCode in terminal) break
                    Thread.sleep(1000)
                }
                check(lastCode == "PAID") { "Live result needs reconciliation" }
                val receipt = liveStore.lastStatusDetail(monitor.id)!!
                check(receipt.optString("pnr").isNotBlank() && receipt.optString("attemptId").isNotBlank())
                check(receipt.optInt("amount") in 1..maxFare)
                val trip = receipt.getJSONObject("trip")
                check(trip.getString("trainNo") == trainNo && trip.getString("date") == config.date)
                check(trip.getString("depCode") == config.depCode && trip.getString("arrCode") == config.arrCode)
                check(trip.getInt("passengers") == 1 && !trip.getBoolean("special"))
                check(trip.getString("departureTime") in config.timeFrom..config.timeTo)
                val notificationId = 10_000 + monitor.id.hashCode().ushr(1) % 500_000
                check(context.getSystemService(android.app.NotificationManager::class.java)
                    .activeNotifications.any { it.id == notificationId })
                scenario.recreate()
                check(ProfileStore(context).lastStatusDetail(monitor.id).toString() == receipt.toString())
                onView(withText(containsString("결제 ${receipt.optInt("amount")}원")))
                    .perform(scrollTo()).check(matches(isDisplayed()))
            }
        } catch (error: Throwable) {
            // Do not attach the original exception: parsers/UI diagnostics may contain secrets.
            throw AssertionError("Live APK test stopped: ${error.javaClass.simpleName}; status=$lastCode. Reconcile saved receipt before any retry.")
        } finally {
            try {
                val id = monitorId
                if (id != null && store?.monitors()?.any { it.id == id && it.active } == true) {
                    androidx.core.content.ContextCompat.startForegroundService(context,
                        android.content.Intent(context, MonitorService::class.java)
                            .setAction(MonitorService.ACTION_STOP_MONITOR)
                            .putExtra(MonitorService.EXTRA_MONITOR_ID, id))
                }
            } finally {
                androidx.test.espresso.Espresso.setFailureHandler(androidx.test.espresso.base.DefaultFailureHandler(context))
            }
        }
    }

    @Test
    fun paymentRecheck_servicePersistsReceipt_withoutNetworkOrMutation() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProfileStore(context)
        check(store.monitors().none { it.active })
        val previousProfile = store.activeProfile()
        val name = "recheck-${java.util.UUID.randomUUID()}"
        val config = MonitorConfig(
            srtId = "$name@example.invalid", srtPassword = "offline-only", dep = "수서", arr = "평택지제",
            date = "20260921", timeFrom = "220000", timeTo = "235000", passengers = 1, special = false,
            windowSeat = false, pollMin = 30, pollMax = 60, autoPay = true,
            cardNumber = "4111111111111111", cardPassword = "12", cardExpire = "2912", cardValidation = "900101",
            depCode = "0551", arrCode = "0553", maxFareWon = 10000, trainNo = "991",
        )
        val monitor = store.save(name, config)
        val trip = JSONObject().put("trainNo", "991").put("departureTime", "220000")
            .put("date", config.date).put("dep", config.dep).put("arr", config.arr)
            .put("depCode", config.depCode).put("arrCode", config.arrCode)
            .put("passengers", 1).put("special", false).put("maxFareWon", 10000)
        store.updateLastStatus(monitor.id, "UNCERTAIN", "offline test", "TEST-PNR", 7700, name, trip)
        if (!com.chaquo.python.Python.isStarted())
            com.chaquo.python.Python.start(com.chaquo.python.android.AndroidPlatform(context))
        val builtins = com.chaquo.python.Python.getInstance().getModule("builtins")
        val scope = builtins.callAttr("dict")
        builtins.callAttr("exec", """
            import korail_engine
            from types import SimpleNamespace
            original_login = korail_engine._login
            closed = False
            mutations = 0
            paid = {
                'h_pnr_no': 'TEST-PNR', 'h_trn_no': '991', 'h_run_dt': '20260921', 'h_dpt_tm': '220000',
                'h_dpt_rs_stn_nm': '수서', 'h_arv_rs_stn_nm': '평택지제',
                'tickets': [{'h_seat_no': '1A', 'h_psrm_cl_cd': '1', 'h_rcvd_amt': '7700'}],
            }
            class Client:
                def get_ticket_list(self, mode='1'):
                    assert mode == '1'
                    return SimpleNamespace(raw=paid)
                def close(self):
                    global closed
                    closed = True
                def forbidden(self, *args, **kwargs):
                    global mutations
                    mutations += 1
                    raise AssertionError('mutation forbidden in read-only test')
                reserve = pay_with_card = cancel_unpaid_hold = forbidden
            def fake_login(config):
                assert config.get('monitorId') == ${JSONObject.quote(monitor.id)}
                return Client()
            korail_engine._login = fake_login
        """.trimIndent(), scope)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                androidx.core.content.ContextCompat.startForegroundService(context,
                    android.content.Intent(context, MonitorService::class.java)
                        .setAction(MonitorService.ACTION_VERIFY_KORAIL_PAYMENT)
                        .putExtra(MonitorService.EXTRA_MONITOR_ID, monitor.id))
                val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                while (store.lastStatus(monitor.id)?.first != "PAID" && android.os.SystemClock.elapsedRealtime() < deadline)
                    Thread.sleep(100)
                val receipt = store.lastStatusDetail(monitor.id)!!
                assertEquals("PAID", receipt.optString("code"))
                assertEquals("TEST-PNR", receipt.optString("pnr"))
                assertEquals(7700, receipt.optInt("amount"))
                assertEquals(name, receipt.optString("attemptId"))
                assertEquals(trip.toString(), receipt.getJSONObject("trip").toString())
                assertTrue(builtins.callAttr("eval", "closed and mutations == 0", scope).toBoolean())
            }
        } finally {
            builtins.callAttr("exec", "korail_engine._login = original_login", scope)
            store.updateLastStatus(monitor.id, "STOPPED", "offline test complete")
            store.deleteMonitor(monitor.id)
            store.deleteAccount(monitor.accountId)
            context.getSystemService(android.app.NotificationManager::class.java)
                .cancel(10_000 + monitor.id.hashCode().ushr(1) % 500_000)
            previousProfile?.let(store::setActiveProfile)
        }
    }

    @Test
    fun receiptSurvivesRecheckFailureAndNewStoreButNotNewAttempt() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ProfileStore(context)
        val id = "receipt-test-${java.util.UUID.randomUUID()}"
        val trip = JSONObject().put("trainNo", "301").put("dep", "수서").put("arr", "평택지제")
        store.updateLastStatus(id, "HOLD_CREATED", "test", "TEST-PNR", 7700, "attempt-1", trip)
        store.updateLastStatus(id, "PAYMENT_IN_FLIGHT", "test", attemptId = "attempt-1")
        assertEquals("PAYMENT_IN_FLIGHT", ProfileStore(context).lastStatus(id)?.first)
        store.updateLastStatus(id, "UNCERTAIN", "test", trip = JSONObject())
        val saved = ProfileStore(context).lastStatusDetail(id)!!
        assertEquals("TEST-PNR", saved.optString("pnr"))
        assertEquals(7700, saved.optInt("amount"))
        assertEquals("301", saved.getJSONObject("trip").getString("trainNo"))
        store.updateLastStatus(id, "PAID", "test")
        assertEquals("attempt-1", ProfileStore(context).lastStatusDetail(id)?.optString("attemptId"))
        store.updateLastStatus(id, "RESERVE_IN_FLIGHT", "test", attemptId = "attempt-2")
        val fresh = store.lastStatusDetail(id)!!
        assertFalse(fresh.has("pnr"))
        assertFalse(fresh.has("amount"))
        assertFalse(fresh.has("trip"))
        store.updateLastStatus(id, "STOPPED", "test complete")
    }
}

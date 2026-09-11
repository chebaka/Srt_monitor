import json
import sys
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))


class _Value:
    def __init__(self, *args, **kwargs):
        self.args = args
        self.kwargs = kwargs
        self.__dict__.update(kwargs)


class _KorailError(Exception):
    pass


api = types.ModuleType("korail_mobile_api")
for name in (
    "KorailAppUpdateRequiredError",
    "KorailAuthContinuationRequired",
    "KorailAuthError",
    "KorailDynaPathError",
    "KorailDynaPathRequiredError",
    "KorailInvalidRequestError",
    "KorailNoDirectTrainError",
    "KorailNoResultsError",
    "KorailProtocolError",
):
    setattr(api, name, type(name, (_KorailError,), {}))
for name in (
    "CardPayment",
    "DynapathConfig",
    "DynapathTokenSettings",
    "KorailClient",
    "KorailConfig",
    "KorailPassengerCounts",
    "MutationConsent",
    "TicketReservationDetailRequest",
    "TrainSearchQuery",
):
    setattr(api, name, _Value)
api.KorailSeatClass = SimpleNamespace(GENERAL="1", SPECIAL="2")
constants = types.ModuleType("korail_mobile_api.constants")
constants.build_dalvik_user_agent = lambda **kwargs: "test-agent"
dynapath = types.ModuleType("korail_mobile_api.dynapath")
dynapath.KORAIL_DYNAPATH_AS_VALUE = "test"
dynapath.build_dynapath_prefix = lambda **kwargs: "prefix"
dynapath.encode_normal_be = lambda value, table, **kwargs: value
dynapath.make_dynapath_key = lambda value: 1
dynapath.make_encode_table = lambda value, size, table: table
sys.modules.update({
    "korail_mobile_api": api,
    "korail_mobile_api.constants": constants,
    "korail_mobile_api.dynapath": dynapath,
})

import korail_engine
import multi_engine


def config(**changes):
    value = {
        "operator": "KORAIL",
        "srtId": "1234567890",
        "srtPassword": "secret",
        "dep": "수서",
        "arr": "부산",
        "date": "20260902",
        "timeFrom": "080000",
        "timeTo": "100000",
        "depCode": "0551",
        "arrCode": "0020",
        "passengers": 1,
        "special": False,
        "windowSeat": False,
        "autoPay": False,
        "maxFareWon": 100000,
        "cardNumber": "4111111111111111",
        "cardPassword": "12",
        "cardExpire": "2912",
        "cardValidation": "900101",
        "deviceId": "0123456789abcdef",
        "osVersion": "15",
        "deviceModel": "TEST",
        "deviceWidth": 1080,
        "deviceHeight": 2400,
        "androidSdkInt": 35,
        "monitorId": "m1",
    }
    value.update(changes)
    return value


class Result:
    def __init__(self, trains):
        self.trains = trains

    def next_page(self):
        return None


class Callback:
    def __init__(self):
        self.events = []

    def onStatus(self, raw):
        self.events.append(json.loads(raw))


def train(code="11", departure_time="083000"):
    return SimpleNamespace(
        departure_time=departure_time,
        general_reservation_code=code,
        special_reservation_code="13",
        train_no="301",
    )


class KorailRecoveryTest(unittest.TestCase):
    def test_login_id_normalization_and_flags(self):
        self.assertEqual(("01012345678", "4"), korail_engine._normalize_login_id("010-1234-5678"))
        self.assertEqual(("user@example.com", "5"), korail_engine._normalize_login_id(" user@example.com "))
        self.assertEqual(("1234567890", "2"), korail_engine._normalize_login_id("123 456 7890"))
        self.assertEqual(("１２３", "2"), korail_engine._normalize_login_id("１２３"))

    def test_dynapath_uses_recent_request_deltas(self):
        settings = SimpleNamespace(
            app_start_ts="900",
            app_id="com.korail.talk",
            device_id="0123456789abcdef",
            as_value="[signature]",
            secure_user=False,
            debug=False,
            emulator=False,
            hooked=False,
            os_version="15",
            device_model="TEST",
            os_type="Android",
            sdk_version="v1.0.3",
            table="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
            i8=161,
            i9=30,
            i10=2,
            table_index=1,
        )
        provider = korail_engine._KorailPlusDynapathTokenProvider(settings)
        with patch.object(korail_engine.time, "time", side_effect=[1.0, 1.05]):
            first = provider()
            second = provider()
        self.assertIn("rt=100", first)
        self.assertIn("rt=100&rt=50", second)

    def test_login_requires_server_identity_fields(self):
        fake = SimpleNamespace(
            login=lambda *args, **kwargs: SimpleNamespace(
                member_card_no="123", customer_no=None
            ),
            close=lambda: None,
        )
        with patch.object(korail_engine, "KorailClient", return_value=fake):
            with self.assertRaises(korail_engine.KorailIdentityUnverifiedError):
                korail_engine._login(config())

    def test_only_confirmed_seat_code_is_available(self):
        client = SimpleNamespace(
            search_trains=lambda query, continuation=None: Result([train("11")])
        )
        status, candidate = korail_engine._find_candidate(client, config())
        self.assertEqual("SEAT_FOUND", status)
        self.assertEqual("301", candidate.train_no)

        for unavailable, expected in (
            ("00", "CABIN_UNAVAILABLE"),
            ("12", "SOLD_OUT"),
            ("13", "SOLD_OUT"),
        ):
            client = SimpleNamespace(
                search_trains=lambda query, continuation=None, code=unavailable: Result([train(code)])
            )
            self.assertEqual(
                (expected, None),
                korail_engine._find_candidate(client, config()),
            )

    def test_unknown_seat_code_fails_closed(self):
        client = SimpleNamespace(
            search_trains=lambda query, continuation=None: Result([train("99")])
        )
        with self.assertRaisesRegex(korail_engine.KorailProtocolError, "API_CHANGED"):
            korail_engine._find_candidate(client, config())

    def test_invalid_departure_time_fails_closed(self):
        client = SimpleNamespace(
            search_trains=lambda query, continuation=None: Result(
                [train("11", departure_time="83000")]
            )
        )
        with self.assertRaisesRegex(korail_engine.KorailProtocolError, "departure time"):
            korail_engine._find_candidate(client, config())

    def test_auto_payment_reserves_and_charges_exactly_once(self):
        candidate = train()
        candidate.departure_date = "20260902"
        candidate.departure_station_code = "0551"
        candidate.arrival_station_code = "0020"
        journey = SimpleNamespace(
            departure_date="20260902", departure_time="083000", train_no="301",
            departure_station_code="0551", arrival_station_code="0020",
        )
        hold = SimpleNamespace(
            str_result="SUCC", pnr_no="PNR1", journey_count="1",
            journeys=(journey,), received_amount="50000",
        )
        history_empty = SimpleNamespace(trains=())
        history_hold = SimpleNamespace(trains=(SimpleNamespace(
            train_no="301", run_date="20260902", departure_time="083000",
            departure_station="수서", arrival_station="부산", pnr_no="PNR1",
        ),))
        detail = SimpleNamespace(
            pnr_no="PNR1", total_received_amount="50000",
            journeys=(SimpleNamespace(
                departure_date="20260902", departure_time="083000", train_no="301",
                departure_station_name="수서", arrival_station_name="부산",
                seats=(SimpleNamespace(room_class_code="1", received_amount="50000"),),
            ),),
        )
        paid_record = {
            "h_pnr_no": "PNR1", "h_trn_no": "301", "h_run_dt": "20260902",
            "h_dpt_tm": "083000", "h_dpt_rs_stn_nm": "수서", "h_arv_rs_stn_nm": "부산",
            "tickets": [{"h_seat_no": "1A", "h_psrm_cl_cd": "1", "h_rcvd_amt": "50000"}],
        }
        client = SimpleNamespace(
            get_reservation_history=unittest.mock.Mock(side_effect=[history_empty, history_hold]),
            get_ticket_reservation_detail=unittest.mock.Mock(return_value=detail),
            get_ticket_list=unittest.mock.Mock(side_effect=[SimpleNamespace(raw={}), SimpleNamespace(raw=paid_record)]),
            reserve=unittest.mock.Mock(return_value=hold),
            pay_with_card=unittest.mock.Mock(return_value=SimpleNamespace(str_result="SUCC")),
            cancel_unpaid_hold=unittest.mock.Mock(),
        )
        events = []
        receipt = korail_engine._reserve_and_pay(
            client, candidate, config(autoPay=True), lambda code, message, **_: events.append(code)
        )
        self.assertEqual(50000, receipt["amount"])
        self.assertEqual("PNR1", receipt["pnr"])
        self.assertTrue(receipt["attemptId"])
        self.assertEqual("301", receipt["trip"]["trainNo"])
        self.assertEqual(["RESERVE_IN_FLIGHT", "HOLD_CREATED", "PAYMENT_IN_FLIGHT"], events)
        client.reserve.assert_called_once()
        client.pay_with_card.assert_called_once()
        client.cancel_unpaid_hold.assert_not_called()

    def test_status_persistence_failure_blocks_reservation(self):
        client = SimpleNamespace(
            get_reservation_history=lambda: SimpleNamespace(trains=()),
            get_ticket_list=lambda **_: SimpleNamespace(raw={}),
            reserve=unittest.mock.Mock(),
        )
        with self.assertRaises(RuntimeError):
            korail_engine._reserve_and_pay(
                client, train(), config(autoPay=True),
                lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("store failed")),
            )
        client.reserve.assert_not_called()

    def test_single_journey_accepts_zero_padded_count(self):
        for good in ("1", "01", "0001", " 1 "):
            self.assertTrue(korail_engine._is_single_journey(good), good)
        for bad in ("0", "2", "", "11", "01a", None):
            self.assertFalse(korail_engine._is_single_journey(bad), repr(bad))

    def test_pinned_train_no_skips_other_trains(self):
        def pinned(code, number, departure_time="083000"):
            item = train(code, departure_time)
            item.train_no = number
            return item

        client = SimpleNamespace(
            search_trains=lambda query, continuation=None: Result(
                [pinned("11", "301"), pinned("11", "345")]
            )
        )
        status, candidate = korail_engine._find_candidate(client, config(trainNo="345"))
        self.assertEqual("SEAT_FOUND", status)
        self.assertEqual("345", candidate.train_no)

        status, candidate = korail_engine._find_candidate(client, config(trainNo="999"))
        self.assertEqual(("NO_MATCHING_TRAIN", None), (status, candidate))

    def test_high_speed_auto_opt_in_passes_class_guard(self):
        candidate = train()
        candidate.train_class_code = "0A"
        candidate.train_no = "345"
        candidate.departure_time = "083000"
        duplicate = SimpleNamespace(
            train_no="345", run_date="20260902", departure_time="083000",
            departure_station="수서", arrival_station="부산", pnr_no="PNR9",
        )
        client = SimpleNamespace(
            get_reservation_history=unittest.mock.Mock(
                return_value=SimpleNamespace(trains=(duplicate,))
            ),
            reserve=unittest.mock.Mock(),
        )
        with self.assertRaisesRegex(korail_engine.KorailPaymentBlockedError, "이미 있어"):
            korail_engine._reserve_and_pay(
                client, candidate, config(autoPay=True, allowHighSpeedAuto=True),
                lambda *_args, **_kwargs: None,
            )
        client.reserve.assert_not_called()

    def test_train_pin_and_high_speed_flag_validated(self):
        with self.assertRaisesRegex(ValueError, "열차번호"):
            korail_engine._validate_config(config(trainNo="KTX"))
        with self.assertRaisesRegex(ValueError, "자동결제 감시"):
            korail_engine._validate_config(config(allowHighSpeedAuto=True))
        checked = config(trainNo="345", autoPay=True, allowHighSpeedAuto=True)
        korail_engine._validate_config(checked)
        self.assertEqual("345", checked["trainNo"])

    def test_unverified_high_speed_train_never_enters_generic_reservation(self):
        candidate = train()
        candidate.train_class_code = "0A"
        client = SimpleNamespace(
            get_reservation_history=unittest.mock.Mock(),
            reserve=unittest.mock.Mock(),
        )
        with self.assertRaisesRegex(korail_engine.KorailPaymentBlockedError, "최신 예약 형식"):
            korail_engine._reserve_and_pay(
                client, candidate, config(autoPay=True), lambda *_args, **_kwargs: None
            )
        client.get_reservation_history.assert_not_called()
        client.reserve.assert_not_called()

    def test_duplicate_requires_same_pnr_journey_unit(self):
        value = config()
        raw = {
            "reservations": [
                {
                    "h_pnr_no": "P1",
                    "header": {"h_run_dt": value["date"]},
                    "journeys": [{
                        "h_trn_no": "301", "h_dpt_tm": "083000",
                        "h_dpt_rs_stn_nm": value["dep"],
                        "h_arv_rs_stn_nm": value["arr"],
                    }],
                },
                {
                    "h_pnr_no": "P2",
                    "header": {"h_run_dt": value["date"]},
                    "journeys": [{
                        "h_trn_no": "999", "h_dpt_tm": "083000",
                        "h_dpt_rs_stn_nm": value["dep"],
                        "h_arv_rs_stn_nm": "other",
                    }],
                },
            ]
        }
        client = SimpleNamespace(
            get_reservation_history=lambda: SimpleNamespace(trains=()),
            get_ticket_list=lambda **_: SimpleNamespace(raw=raw),
        )
        self.assertTrue(korail_engine._has_duplicate(client, train(), value))

        raw["reservations"][0]["journeys"][0]["h_arv_rs_stn_nm"] = "other"
        raw["reservations"][1]["journeys"][0]["h_trn_no"] = "301"
        self.assertFalse(korail_engine._has_duplicate(client, train(), value))

    def test_duplicate_and_paid_matching_keep_direction_and_pnr_scope(self):
        value = config()
        journey = {
            "h_trn_no": "301", "h_dpt_tm": "083000",
            "h_dpt_rs_stn_nm": value["dep"], "h_arv_rs_stn_nm": value["arr"],
        }
        raw = {"reservations": [
            {"h_pnr_no": "P1", "header": {"h_run_dt": "20990101"}, "journeys": [journey]},
            {"h_pnr_no": "P2", "header": {"h_run_dt": value["date"]},
             "journeys": [{**journey, "h_trn_no": "999"}]},
        ]}
        client = SimpleNamespace(
            get_reservation_history=lambda: SimpleNamespace(trains=()),
            get_ticket_list=lambda **_: SimpleNamespace(raw=raw),
        )
        self.assertFalse(korail_engine._has_duplicate(client, train(), value))
        raw = {
            **journey, "h_pnr_no": "P1", "h_run_dt": value["date"],
            "h_dpt_rs_stn_nm": value["arr"], "h_arv_rs_stn_nm": value["dep"],
            "tickets": [{"h_seat_no": "1A", "h_psrm_cl_cd": "1", "h_rcvd_amt": "50000"}],
        }
        self.assertFalse(korail_engine._has_duplicate(client, train(), value))
        self.assertFalse(korail_engine._paid_ticket_matches(raw, "P1", train(), value, 50000))
        raw = {"h_pnr_no": "P1", "header": {"h_run_dt": value["date"]}, "journeys": [journey]}
        self.assertTrue(korail_engine._has_duplicate(client, train(), value))

    def test_paid_matching_ignores_foreign_pnr_and_trip_seats(self):
        value = config()
        own_seat = {"h_seat_no": "9B", "h_psrm_cl_cd": "1", "h_rcvd_amt": "50000"}
        foreign_seat = {"h_seat_no": "1A", "h_psrm_cl_cd": "1", "h_rcvd_amt": "50000"}
        base = {
            "h_pnr_no": "P1", "h_trn_no": "301", "h_run_dt": value["date"],
            "h_dpt_tm": "083000", "h_dpt_rs_stn_nm": value["dep"],
            "h_arv_rs_stn_nm": value["arr"],
        }
        # Foreign PNR nested inside our record must not count as paid.
        raw = dict(base, nested={"h_pnr_no": "P2", "tickets": [foreign_seat]})
        self.assertFalse(korail_engine._paid_ticket_matches(raw, "P1", train(), value, 50000))
        # Same PNR but a different trip nested inside must not count either.
        raw = dict(base, nested={
            "h_pnr_no": "P1", "h_trn_no": "999", "h_dpt_tm": "083000",
            "tickets": [foreign_seat],
        })
        self.assertFalse(korail_engine._paid_ticket_matches(raw, "P1", train(), value, 50000))
        # Our own seats still confirm payment.
        raw = dict(base, tickets=[own_seat])
        self.assertTrue(korail_engine._paid_ticket_matches(raw, "P1", train(), value, 50000))

    def test_check_payment_json_is_read_only_and_bounded(self):
        value = config(autoPay=True, trainNo="301")
        candidate = train()
        attempt = {
            "pnr": "PNR1", "amount": 50000, "attemptId": "attempt-1",
            "trip": korail_engine._trip_snapshot(candidate, value),
        }
        paid_record = {
            "h_pnr_no": "PNR1", "h_trn_no": "301", "h_run_dt": value["date"],
            "h_dpt_tm": "083000", "h_dpt_rs_stn_nm": value["dep"],
            "h_arv_rs_stn_nm": value["arr"],
            "tickets": [{"h_seat_no": "1A", "h_psrm_cl_cd": "1", "h_rcvd_amt": "50000"}],
        }
        client = SimpleNamespace(
            get_ticket_list=unittest.mock.Mock(return_value=SimpleNamespace(raw=paid_record)),
            close=unittest.mock.Mock(),
            reserve=unittest.mock.Mock(), pay_with_card=unittest.mock.Mock(),
            cancel_unpaid_hold=unittest.mock.Mock(),
        )
        with patch.object(korail_engine, "_login", return_value=client):
            result = json.loads(korail_engine.check_payment_json(json.dumps(value), json.dumps(attempt)))
        self.assertEqual("PAID", result["statusCode"])
        client.get_ticket_list.assert_called_once_with(mode="1")
        client.reserve.assert_not_called()
        client.pay_with_card.assert_not_called()
        client.cancel_unpaid_hold.assert_not_called()

        client.get_ticket_list.reset_mock()
        client.get_ticket_list.return_value = SimpleNamespace(raw={})
        with patch.object(korail_engine, "_login", return_value=client):
            result = json.loads(korail_engine.check_payment_json(json.dumps(value), json.dumps(attempt)))
        self.assertEqual("UNCERTAIN", result["statusCode"])
        self.assertEqual(3, client.get_ticket_list.call_count)

    def test_check_payment_json_rejects_trip_mismatch_before_login(self):
        value = config(autoPay=True, trainNo="301")
        attempt = {
            "pnr": "PNR1", "amount": 50000, "attemptId": "attempt-1",
            "trip": korail_engine._trip_snapshot(train(), value),
        }
        attempt["trip"] = dict(attempt["trip"], arrCode="9999")
        with patch.object(korail_engine, "_login") as login:
            result = json.loads(korail_engine.check_payment_json(json.dumps(value), json.dumps(attempt)))
        self.assertEqual("UNCERTAIN", result["statusCode"])
        login.assert_not_called()

    def test_paid_finish_failure_falls_back_to_uncertain(self):
        events = []

        class FailingPaidCallback:
            def onStatus(self, raw):
                event = json.loads(raw)
                events.append(event)
                if event["statusCode"] == "PAID":
                    raise RuntimeError("status write failed")

        states = {"m1": {"receipt": {
            "pnr": "PNR1", "amount": 50000, "attemptId": "attempt-1", "trip": {},
        }}}
        multi_engine._finish(
            states, "m1", FailingPaidCallback(), "PAID", "paid", "301"
        )
        self.assertEqual({}, states)
        self.assertEqual(["PAID", "UNCERTAIN"], [event["statusCode"] for event in events])
        self.assertEqual("PNR1", events[-1]["pnr"])

    def test_srt_dispatch_is_terminal(self):
        callback = Callback()
        multi_engine.run_account_json(json.dumps([config(operator="SRT")]), callback)
        self.assertEqual("LEGACY_DISABLED", callback.events[-1]["statusCode"])
        self.assertTrue(callback.events[-1]["terminal"])

    def test_mixed_accounts_stop_before_login(self):
        callback = Callback()
        configs = [
            config(monitorId="m1"),
            config(monitorId="m2", srtId="other"),
        ]
        with patch.object(korail_engine, "_login") as login:
            multi_engine.run_account_json(json.dumps(configs), callback)
        login.assert_not_called()
        self.assertEqual({"ERROR"}, {event["statusCode"] for event in callback.events[-2:]})

    def test_verified_login_and_seat_found_only_notifies_without_autopay(self):
        callback = Callback()
        client = SimpleNamespace(close=lambda: None)
        with (
            patch.object(korail_engine, "_login", return_value=client),
            patch.object(korail_engine, "_find_candidate", return_value=("SEAT_FOUND", train())),
            patch.object(korail_engine, "_reserve_and_pay") as reserve,
        ):
            multi_engine.run_account_json(json.dumps([config()]), callback)
        reserve.assert_not_called()
        codes = [event["statusCode"] for event in callback.events]
        self.assertIn("AUTH_VERIFIED", codes)
        self.assertEqual("SEAT_FOUND", codes[-1])
        self.assertTrue(callback.events[-1]["terminal"])

    def test_protocol_error_stops_all_shared_account_monitors(self):
        callback = Callback()
        client = SimpleNamespace(close=lambda: None)
        configs = [config(monitorId="m1"), config(monitorId="m2")]
        with (
            patch.object(korail_engine, "_login", return_value=client),
            patch.object(
                korail_engine,
                "_find_candidate",
                side_effect=korail_engine.KorailProtocolError("API_CHANGED"),
            ),
        ):
            multi_engine.run_account_json(json.dumps(configs), callback)
        terminal = [event for event in callback.events if event["terminal"]]
        self.assertEqual(2, len(terminal))
        self.assertEqual({"API_INCOMPATIBLE"}, {event["statusCode"] for event in terminal})


if __name__ == "__main__":
    unittest.main()

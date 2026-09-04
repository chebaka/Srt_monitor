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
    "DynapathConfig",
    "DynapathTokenSettings",
    "KorailClient",
    "KorailConfig",
    "TrainSearchQuery",
):
    setattr(api, name, _Value)
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

        for unavailable, expected in (("00", "CABIN_UNAVAILABLE"), ("13", "SOLD_OUT")):
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

    def test_reservation_is_blocked_before_client_call(self):
        client = SimpleNamespace(reserve=lambda *args, **kwargs: self.fail("reserve called"))
        with self.assertRaisesRegex(korail_engine.KorailProtocolError, "disabled"):
            korail_engine._reserve(client, train(), config())

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

    def test_verified_login_and_seat_found_never_reserve(self):
        callback = Callback()
        client = SimpleNamespace(close=lambda: None)
        with (
            patch.object(korail_engine, "_login", return_value=client),
            patch.object(korail_engine, "_find_candidate", return_value=("SEAT_FOUND", train())),
            patch.object(korail_engine, "_reserve") as reserve,
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

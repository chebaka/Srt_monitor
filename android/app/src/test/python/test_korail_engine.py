import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace


PYTHON_DIR = Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_DIR))

import korail_engine
import multi_engine


def config(**changes):
    value = {
        "operator": "KORAIL",
        "srtId": "member",
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


class KorailMigrationTest(unittest.TestCase):
    def test_srt_dispatch_is_terminal_and_never_calls_an_engine(self):
        callback = Callback()
        multi_engine.run_account_json(json.dumps([config(operator="SRT")]), callback)
        self.assertEqual("LEGACY_DISABLED", callback.events[-1]["statusCode"])
        self.assertTrue(callback.events[-1]["terminal"])

    def test_auto_payment_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "자동결제"):
            korail_engine._validate_config(config(autoPay=True))

    def test_integrated_train_is_not_filtered_by_train_name(self):
        former_srt = SimpleNamespace(
            departure_time="083000",
            general_reservation_code="11",
            special_reservation_code="13",
            train_no="301",
            train_class_name="통합 KTX",
        )
        client = SimpleNamespace(search_trains=lambda query, continuation=None: Result([former_srt]))
        self.assertIs(former_srt, korail_engine._find_candidate(client, config()))

    def test_reserve_uses_only_explicit_reservation_consent(self):
        captured = {}

        def reserve(train, **kwargs):
            captured.update(kwargs)
            return SimpleNamespace(pnr_no="PNR", journeys=())

        hold = korail_engine._reserve(SimpleNamespace(reserve=reserve), SimpleNamespace(), config())
        self.assertEqual("PNR", hold.pnr_no)
        self.assertTrue(captured["consent"].allow_reserve)
        self.assertFalse(captured["consent"].dry_run)
        self.assertFalse(captured["consent"].allow_payment)


if __name__ == "__main__":
    unittest.main()

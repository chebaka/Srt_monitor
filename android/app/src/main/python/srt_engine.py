"""SRT monitoring engine for the Android app.

Credentials are supplied at runtime by the encrypted Android profile store.
Nothing is written to disk by this module and Telegram is intentionally absent.
"""
import json
import random
import threading
import time
from datetime import datetime
from functools import wraps

from SRT import SRT
from SRT.passenger import Adult
from SRT.seat_type import SeatType

_stop = threading.Event()


def stop_monitor():
    _stop.set()


def _emit(callback, message):
    try:
        callback.onStatus(message)
    except Exception:
        pass


def _configure_session(session, netfunnel=False):
    original = session.request

    @wraps(original)
    def request(method, url, **kwargs):
        kwargs.setdefault("timeout", (10, 20))
        if netfunnel and kwargs.get("params"):
            params = dict(kwargs["params"])
            if str(params.get("opcode")) == "5004":
                params.setdefault("sid", "service_1")
                params.setdefault("aid", "act_10")
                kwargs["params"] = params
        return original(method, url, **kwargs)

    session.request = request


def _login(config):
    client = SRT(config["srtId"], config["srtPassword"], auto_login=False)
    _configure_session(client._session)
    _configure_session(client.netfunnel_helper.session, netfunnel=True)
    client.login()
    return client


def _existing_reservation(client, config):
    try:
        reservations = client.get_reservations()
    except Exception:
        return None
    for reservation in reservations:
        if reservation.dep_date != config["date"]:
            continue
        if (reservation.dep_station_name == config["dep"] and
                reservation.arr_station_name == config["arr"] and
                config["timeFrom"] <= reservation.dep_time <= config["timeTo"]):
            return reservation
    return None


def _find_candidate(client, config):
    trains = client.search_train(
        config["dep"], config["arr"], config["date"], config["timeFrom"],
        time_limit=config["timeTo"],
    )
    for train in trains:
        if not (config["timeFrom"] <= train.dep_time <= config["timeTo"]):
            continue
        available = (train.special_seat_available() if config["special"]
                     else train.general_seat_available())
        if available:
            return train
    return None


def _reserve(client, train, config):
    passengers = [Adult(count=config["passengers"])]
    seat_type = SeatType.SPECIAL_ONLY if config["special"] else SeatType.GENERAL_FIRST
    window = True if config["windowSeat"] else None
    return client.reserve(train, passengers, special_seat=seat_type, window_seat=window)


def _pay(client, reservation, config):
    card = config
    return client.pay_with_card(
        reservation,
        number=card["cardNumber"].replace("-", "").replace(" ", ""),
        password=card["cardPassword"],
        validation_number=card["cardValidation"],
        expire_date=card["cardExpire"],
        installment=0,
        card_type="J",
    )


def _wait(seconds):
    return _stop.wait(seconds)


def run_monitor_json(config_json, callback):
    """Monitor, reserve, and optionally pay once, then return."""
    config = json.loads(config_json)
    required = ("srtId", "srtPassword", "dep", "arr", "date", "timeFrom", "timeTo")
    if any(not str(config.get(key, "")).strip() for key in required):
        raise ValueError("필수 SRT 조건이 비어 있어")
    if config.get("autoPay"):
        card_fields = ("cardNumber", "cardPassword", "cardExpire", "cardValidation")
        if any(not str(config.get(key, "")).strip() for key in card_fields):
            raise ValueError("자동결제 카드정보가 비어 있어")

    _stop.clear()
    consecutive_errors = 0
    poll_min = max(30, int(config.get("pollMin", 30)))
    poll_max = max(poll_min, int(config.get("pollMax", 60)))
    client = None

    while not _stop.is_set():
        try:
            if client is None:
                _emit(callback, "🔐 SRT 로그인 중")
                client = _login(config)
                _emit(callback, "✅ SRT 로그인 완료")
                existing = _existing_reservation(client, config)
                if existing:
                    _emit(callback, "⚠️ 조건에 맞는 기존 예약이 있어 중지했어")
                    return

            _emit(callback, "🔎 열차 조회 중")
            candidate = _find_candidate(client, config)
            consecutive_errors = 0
            if candidate is None:
                _emit(callback, "🟡 좌석 없음 · 다음 조회 대기")
                if _wait(random.uniform(poll_min, poll_max)):
                    return
                continue

            _emit(callback, f"🎫 좌석 발견: {candidate.train_number} {candidate.dep_time} · 예약 시도")
            reservation = _reserve(client, candidate, config)
            message = f"✅ 예약 성공: {candidate.train_number} {candidate.dep_time}"
            _emit(callback, message)

            if config.get("autoPay"):
                _emit(callback, "💳 결제 시도 중")
                _pay(client, reservation, config)
                paid = False
                for item in client.get_reservations():
                    if item.reservation_number == reservation.reservation_number:
                        paid = bool(item.paid)
                        break
                if not paid:
                    raise RuntimeError("결제 응답 후 예약내역에서 결제 완료를 확인하지 못했어")
                _emit(callback, f"✅ 예약·결제 완료: {candidate.train_number} {candidate.dep_time}")
            return
        except Exception as error:
            consecutive_errors += 1
            client = None
            if consecutive_errors >= 5:
                _emit(callback, f"🔴 연속 오류 5회로 중지: {str(error)[:180]}")
                return
            _emit(callback, f"⚠️ 처리 오류 · 재시도 {consecutive_errors}/5: {str(error)[:180]}")
            if _wait(random.uniform(poll_min, poll_max)):
                return

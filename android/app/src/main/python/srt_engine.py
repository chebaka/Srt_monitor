"""SRT monitoring engine for the Android app.

Credentials are supplied at runtime by the encrypted Android profile store.
Nothing is written to disk by this module and Telegram is intentionally absent.
"""
import json
import random
import re
import threading
import time
from datetime import datetime
from functools import wraps

from SRT import SRT
from SRT.constants import STATION_CODE
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


def _safe_error(error, config):
    message = str(error)
    for key in ("srtPassword", "cardNumber", "cardPassword", "cardExpire", "cardValidation"):
        secret = str(config.get(key, "")).strip()
        if secret:
            message = message.replace(secret, "[redacted]")
    return message[:180]


def _validate_config(config):
    required = ("srtId", "srtPassword", "dep", "arr", "date", "timeFrom", "timeTo")
    if any(not str(config.get(key, "")).strip() for key in required):
        raise ValueError("필수 SRT 조건이 비어 있어")

    config["date"] = str(config["date"]).strip()
    config["timeFrom"] = str(config["timeFrom"]).strip()
    config["timeTo"] = str(config["timeTo"]).strip()
    try:
        datetime.strptime(config["date"], "%Y%m%d")
        datetime.strptime(config["timeFrom"], "%H%M%S")
        datetime.strptime(config["timeTo"], "%H%M%S")
    except ValueError as error:
        raise ValueError("날짜 또는 시간 형식이 잘못됐어") from error
    if config["timeFrom"] > config["timeTo"]:
        raise ValueError("종료 시각은 시작 시각 이후여야 해")
    if config["dep"] not in STATION_CODE:
        raise ValueError(f'출발역을 SRT 역명으로 입력해 (예: 수서): {config["dep"]}')
    if config["arr"] not in STATION_CODE:
        raise ValueError(f'도착역을 SRT 역명으로 입력해 (예: 부산): {config["arr"]}')

    try:
        passengers = int(config.get("passengers", 0))
    except (TypeError, ValueError) as error:
        raise ValueError("성인 인원이 잘못됐어") from error
    if passengers < 1:
        raise ValueError("성인 인원은 1명 이상이어야 해")
    config["passengers"] = passengers

    if config.get("autoPay"):
        card_number = re.sub(r"[\s-]", "", str(config.get("cardNumber", "")))
        card_password = str(config.get("cardPassword", "")).strip()
        card_expire = str(config.get("cardExpire", "")).strip()
        card_validation = str(config.get("cardValidation", "")).strip()
        if not re.fullmatch(r"[0-9]{12,19}", card_number):
            raise ValueError("카드번호를 확인해")
        if not re.fullmatch(r"[0-9]{2}", card_password):
            raise ValueError("카드 비밀번호 앞 2자리를 확인해")
        if not re.fullmatch(r"[0-9]{4}", card_expire):
            raise ValueError("카드 유효기간을 확인해")
        if not re.fullmatch(r"[0-9]{6}|[0-9]{10}", card_validation):
            raise ValueError("카드 인증번호를 확인해")
        config["cardNumber"] = card_number
        config["cardPassword"] = card_password
        config["cardExpire"] = card_expire
        config["cardValidation"] = card_validation


def run_monitor_json(config_json, callback):
    """Monitor, reserve, and optionally pay once, then return."""
    config = json.loads(config_json)
    if not isinstance(config, dict):
        raise ValueError("설정 형식이 잘못됐어")
    try:
        _validate_config(config)
    except Exception as error:
        _emit(callback, f"🔴 입력 확인 실패: {_safe_error(error, config)}")
        return

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

            if config.get("autoPay"):
                _emit(callback, f"🎫 예약 확보: {candidate.train_number} {candidate.dep_time} · 결제 전환 중")
                _emit(callback, "💳 결제 시도 중")
                try:
                    _pay(client, reservation, config)
                    paid = False
                    for item in client.get_reservations():
                        if item.reservation_number == reservation.reservation_number:
                            paid = bool(item.paid)
                            break
                    if not paid:
                        raise RuntimeError("예약내역에서 결제 완료를 확인하지 못했어")
                except Exception as error:
                    _emit(callback, f"🔴 예약 완료·결제 검증 실패: {_safe_error(error, config)}")
                    return
                _emit(callback, f"✅ 예약·결제 완료: {candidate.train_number} {candidate.dep_time}")
            else:
                _emit(callback, f"✅ 예약 성공: {candidate.train_number} {candidate.dep_time}")
            return
        except Exception as error:
            consecutive_errors += 1
            client = None
            detail = _safe_error(error, config)
            if consecutive_errors >= 5:
                _emit(callback, f"🔴 연속 오류 5회로 중지: {detail}")
                return
            _emit(callback, f"⚠️ 처리 오류 · 재시도 {consecutive_errors}/5: {detail}")
            if _wait(random.uniform(poll_min, poll_max)):
                return

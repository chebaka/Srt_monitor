"""KORAIL monitoring and reservation engine.

This module intentionally stops after reservation. KORAIL payment is not
implemented by the selected client and must not be guessed or retried.
"""
import json
import random
import re
import threading
from datetime import datetime

from korail2 import AdultPassenger, Korail, NoResultsError, ReserveOption


_stop = threading.Event()

def stop_monitor():
    _stop.set()


def _emit(callback, message):
    try:
        callback.onStatus(message)
    except Exception:
        pass


def _configure_session(session):
    original = session.request

    def request(method, url, **kwargs):
        kwargs.setdefault("timeout", (10, 20))
        return original(method, url, **kwargs)

    session.request = request


def _login(config):
    client = Korail(config["srtId"], config["srtPassword"], auto_login=False)
    _configure_session(client._session)
    if not client.login():
        raise RuntimeError("KORAIL login failed")
    return client


def _existing_reservation(client, config):
    for reservation in client.reservations():
        if reservation.dep_date != config["date"]:
            continue
        if reservation.dep_name != config["dep"] or reservation.arr_name != config["arr"]:
            continue
        if config.get("depCode") and getattr(reservation, "dep_code", "") != config["depCode"]:
            continue
        if config.get("arrCode") and getattr(reservation, "arr_code", "") != config["arrCode"]:
            continue
        if not (config["timeFrom"] <= reservation.dep_time <= config["timeTo"]):
            continue
        return reservation
    return None


def _find_candidate(client, config):
    passengers = [AdultPassenger(count=config["passengers"])]
    try:
        trains = client.search_train(
            config["dep"],
            config["arr"],
            config["date"],
            config["timeFrom"],
            passengers=passengers,
            include_no_seats=True,
        )
    except NoResultsError:
        return None

    for train in trains:
        if not (config["timeFrom"] <= train.dep_time <= config["timeTo"]):
            continue
        available = train.has_special_seat() if config["special"] else train.has_general_seat()
        if available:
            return train
    return None


def _reserve(client, train, config):
    passengers = [AdultPassenger(count=config["passengers"])]
    option = ReserveOption.SPECIAL_ONLY if config["special"] else ReserveOption.GENERAL_FIRST
    return client.reserve(train, passengers=passengers, option=option)


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
    if config.get("operator", "SRT") != "KORAIL":
        raise ValueError("KORAIL engine received a non-KORAIL profile")

    required = ("srtId", "srtPassword", "dep", "arr", "date", "timeFrom", "timeTo", "depCode", "arrCode")
    if any(not str(config.get(key, "")).strip() for key in required):
        raise ValueError("필수 KORAIL 조건이 비어 있어")

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

    for key in ("dep", "arr"):
        station = str(config[key]).strip()
        if not re.fullmatch(r"[가-힣A-Za-z0-9()·\-\s]{1,30}", station):
            raise ValueError("역 이름에 허용되지 않는 문자가 있어")
        config[key] = station
    if config["dep"] == config["arr"]:
        raise ValueError("출발역과 도착역은 달라야 해")
    for key in ("depCode", "arrCode"):
        if not re.fullmatch(r"[0-9]{4}", str(config[key]).strip()):
            raise ValueError("코레일 역 코드가 잘못됐어")
        config[key] = str(config[key]).strip()

    try:
        passengers = int(config.get("passengers", 0))
    except (TypeError, ValueError) as error:
        raise ValueError("승객 수가 잘못됐어") from error
    if passengers < 1:
        raise ValueError("승객 수는 1명 이상이어야 해")
    config["passengers"] = passengers

    if config.get("windowSeat"):
        raise ValueError("KORAIL 창가 우선은 아직 지원하지 않아")
    if config.get("autoPay"):
        raise ValueError("KORAIL 자동결제는 검증 전 지원하지 않아")


def run_monitor_json(config_json, callback):
    """Monitor and reserve one KORAIL train, then return."""
    try:
        config = json.loads(config_json)
        if not isinstance(config, dict):
            raise ValueError("설정 형식이 잘못됐어")
        _validate_config(config)
    except Exception as error:
        _emit(callback, f"KORAIL|입력 확인 실패|{_safe_error(error, config if 'config' in locals() and isinstance(config, dict) else {})}")
        return

    _stop.clear()
    consecutive_errors = 0
    poll_min = max(30, int(config.get("pollMin", 30)))
    poll_max = max(poll_min, int(config.get("pollMax", 60)))
    client = None

    while not _stop.is_set():
        try:
            if client is None:
                _emit(callback, "KORAIL|로그인 중")
                client = _login(config)
                _emit(callback, "KORAIL|로그인 완료")
                existing = _existing_reservation(client, config)
                if existing:
                    _emit(callback, f"KORAIL|기존 예약 발견|{existing.rsv_id}")
                    return

            _emit(callback, "KORAIL|열차 조회 중")
            candidate = _find_candidate(client, config)
            consecutive_errors = 0
            if candidate is None:
                _emit(callback, "KORAIL|좌석 없음|다음 조회 대기")
                if _wait(random.uniform(poll_min, poll_max)):
                    return
                continue

            _emit(callback, f"KORAIL|좌석 발견|{candidate.train_type_name} {candidate.train_no} {candidate.dep_time}")
            reservation = _reserve(client, candidate, config)
            _emit(
                callback,
                f"KORAIL|예약 완료|결제 필요|예약번호 {reservation.rsv_id}|결제기한 {reservation.buy_limit_date} {reservation.buy_limit_time}",
            )
            return
        except Exception as error:
            consecutive_errors += 1
            client = None
            detail = _safe_error(error, config)
            if consecutive_errors >= 5:
                _emit(callback, f"KORAIL|연속 오류 5회|{detail}")
                return
            _emit(callback, f"KORAIL|처리 오류|재시도 {consecutive_errors}/5|{detail}")
            if _wait(random.uniform(poll_min, poll_max)):
                return

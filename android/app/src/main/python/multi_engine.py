"""Shared-account multi-monitor scheduler.

One invocation owns one authenticated rail account. It checks that account's
monitors sequentially while separate invocations may run for other accounts.
"""
import json
import random
import threading
import time
from datetime import datetime

import korail_engine
import srt_engine


_lock = threading.Lock()
_stopped = set()
_payment_checks = set()
_wake = threading.Event()


def stop_monitors(ids_json):
    ids = json.loads(ids_json)
    with _lock:
        _stopped.update(str(value) for value in ids)
    _wake.set()


def request_payment_check(monitor_id):
    with _lock:
        _payment_checks.add(str(monitor_id))
    _wake.set()


def _is_stopped(monitor_id):
    with _lock:
        return monitor_id in _stopped


def _consume_payment_check(monitor_id):
    with _lock:
        if monitor_id not in _payment_checks:
            return False
        _payment_checks.remove(monitor_id)
        return True


def _emit(callback, monitor_id, code, message, train_no="", deadline=""):
    payload = json.dumps({
        "monitorId": monitor_id,
        "statusCode": code,
        "terminal": code in ("COMPLETED", "ERROR", "EXPIRED", "STOPPED"),
        "message": str(message)[:300],
        "trainNo": str(train_no),
        "deadline": str(deadline),
    }, ensure_ascii=False)
    try:
        callback.onStatus(payload)
    except Exception:
        pass


def _finish(states, monitor_id, callback, code, message, train_no="", deadline=""):
    _emit(callback, monitor_id, code, message, train_no, deadline)
    states.pop(monitor_id, None)


def _safe(module, error, config):
    return module._safe_error(error, config)


def _korail_deadline(reservation):
    text = f"{reservation.buy_limit_date} {reservation.buy_limit_time}"
    return korail_engine._payment_deadline(reservation), text


def _check_korail_payment(client, state, callback):
    config = state["config"]
    monitor_id = config["monitorId"]
    reservation = state["reservation"]
    train_no = state["trainNo"]
    deadline, deadline_text = _korail_deadline(reservation)
    if datetime.now() >= deadline:
        return "EXPIRED", "결제 기한 만료", deadline_text
    try:
        tickets = client.tickets() or []
        if any(korail_engine._ticket_matches_config(ticket, config, train_no) for ticket in tickets):
            return "COMPLETED", "결제 확인 완료", deadline_text
        state["errors"] = 0
        _emit(callback, monitor_id, "PAYMENT_PENDING", "결제 대기", train_no, deadline_text)
    except Exception as error:
        state["errors"] += 1
        if state["errors"] >= 5:
            return "ERROR", f"결제 확인 연속 오류 5회: {_safe(korail_engine, error, config)}", deadline_text
        _emit(callback, monitor_id, "PAYMENT_PENDING", f"결제 확인 오류 {state['errors']}/5", train_no, deadline_text)
    state["next"] = time.monotonic() + 60
    return None


def _process_srt(client, state, states, callback):
    config = state["config"]
    monitor_id = config["monitorId"]
    if not state["existingChecked"]:
        state["existingChecked"] = True
        if srt_engine._existing_reservation(client, config):
            _finish(states, monitor_id, callback, "COMPLETED", "조건에 맞는 기존 예약이 있어")
            return
    _emit(callback, monitor_id, "SEARCHING", "SRT 열차 조회 중")
    candidate = srt_engine._find_candidate(client, config)
    if _is_stopped(monitor_id):
        _finish(states, monitor_id, callback, "STOPPED", "감시 중지")
        return
    state["errors"] = 0
    if candidate is None:
        _emit(callback, monitor_id, "WAITING", "좌석 없음 · 다음 조회 대기")
        state["next"] = time.monotonic() + random.uniform(state["pollMin"], state["pollMax"])
        return
    train_no = candidate.train_number
    _emit(callback, monitor_id, "RESERVING", "좌석 발견 · 예약 시도", train_no)
    if _is_stopped(monitor_id):
        _finish(states, monitor_id, callback, "STOPPED", "감시 중지")
        return
    reservation = srt_engine._reserve(client, candidate, config)
    if config.get("autoPay"):
        if _is_stopped(monitor_id):
            _finish(states, monitor_id, callback, "COMPLETED", "예약 완료 · 중지 요청으로 결제 미시도", train_no)
            return
        try:
            srt_engine._pay(client, reservation, config)  # one attempt only
            paid = any(
                item.reservation_number == reservation.reservation_number and bool(item.paid)
                for item in client.get_reservations()
            )
            if not paid:
                raise RuntimeError("예약내역에서 결제 완료를 확인하지 못했어")
        except Exception as error:
            _finish(states, monitor_id, callback, "ERROR", f"예약 완료·결제 검증 실패: {_safe(srt_engine, error, config)}", train_no)
            return
        _finish(states, monitor_id, callback, "COMPLETED", "예약·결제 완료", train_no)
    else:
        _finish(states, monitor_id, callback, "COMPLETED", "예약 성공", train_no)


def _set_korail_waiting(state, reservation, train_no, callback, existing=False):
    state["reservation"] = reservation
    state["trainNo"] = str(train_no)
    state["next"] = time.monotonic()
    _, deadline_text = _korail_deadline(reservation)
    label = "기존 예약 결제 대기" if existing else "예약 완료 · 결제 필요"
    _emit(callback, state["config"]["monitorId"], "PAYMENT_PENDING", label, train_no, deadline_text)


def _process_korail(client, state, states, callback):
    config = state["config"]
    monitor_id = config["monitorId"]
    if state.get("reservation") is not None:
        result = _check_korail_payment(client, state, callback)
        if result:
            code, message, deadline = result
            _finish(states, monitor_id, callback, code, message, state["trainNo"], deadline)
        return
    if not state["existingChecked"]:
        state["existingChecked"] = True
        tickets = client.tickets() or []
        if any(korail_engine._ticket_matches_config(ticket, config) for ticket in tickets):
            _finish(states, monitor_id, callback, "COMPLETED", "조건에 맞는 기존 발권이 있어")
            return
        existing = korail_engine._existing_reservation(client, config)
        if existing:
            _set_korail_waiting(state, existing, korail_engine._reservation_train_no(existing), callback, True)
            return
    _emit(callback, monitor_id, "SEARCHING", "KORAIL 열차 조회 중")
    candidate = korail_engine._find_candidate(client, config)
    if _is_stopped(monitor_id):
        _finish(states, monitor_id, callback, "STOPPED", "감시 중지")
        return
    state["errors"] = 0
    if candidate is None:
        _emit(callback, monitor_id, "WAITING", "좌석 없음 · 다음 조회 대기")
        state["next"] = time.monotonic() + random.uniform(state["pollMin"], state["pollMax"])
        return
    train_no = candidate.train_no
    _emit(callback, monitor_id, "RESERVING", "좌석 발견 · 예약 시도", train_no)
    if _is_stopped(monitor_id):
        _finish(states, monitor_id, callback, "STOPPED", "감시 중지")
        return
    reservation = korail_engine._reserve(client, candidate, config)
    _set_korail_waiting(state, reservation, train_no, callback)
    if config.get("autoPay"):
        if _is_stopped(monitor_id):
            _finish(states, monitor_id, callback, "COMPLETED", "예약 완료 · 중지 요청으로 결제 미시도", train_no)
            return
        try:
            _emit(callback, monitor_id, "PAYMENT_PENDING", "자동결제 1회 시도", train_no)
            if _is_stopped(monitor_id):
                _finish(states, monitor_id, callback, "COMPLETED", "예약 완료 · 중지 요청으로 결제 미시도", train_no)
                return
            korail_engine._pay_with_card(client, reservation, config)  # never retry
        except Exception as error:
            _emit(callback, monitor_id, "PAYMENT_PENDING", f"자동결제 실패: {_safe(korail_engine, error, config)}", train_no)


def run_account_json(configs_json, callback):
    configs = json.loads(configs_json)
    if not isinstance(configs, list) or not configs:
        return
    operator = configs[0].get("operator", "SRT")
    module = korail_engine if operator == "KORAIL" else srt_engine
    states = {}
    with _lock:
        for config in configs:
            _stopped.discard(str(config.get("monitorId", "")))
    for config in configs:
        monitor_id = str(config.get("monitorId", ""))
        try:
            if not monitor_id or config.get("operator", "SRT") != operator:
                raise ValueError("계정 감시 설정이 일치하지 않아")
            module._validate_config(config)
            poll_min = max(30, int(config.get("pollMin", 30)))
            states[monitor_id] = {
                "config": config, "errors": 0, "existingChecked": False,
                "pollMin": poll_min, "pollMax": max(poll_min, int(config.get("pollMax", 60))),
                "next": time.monotonic(), "reservation": None, "trainNo": "",
            }
            _emit(callback, monitor_id, "STARTING", f"{operator} 감시 시작")
        except Exception as error:
            _emit(callback, monitor_id, "ERROR", f"입력 확인 실패: {_safe(module, error, config)}")
    client = None
    while states:
        for monitor_id in list(states):
            if _is_stopped(monitor_id):
                _finish(states, monitor_id, callback, "STOPPED", "감시 중지")
        if not states:
            break
        now = time.monotonic()
        due = [state for state in states.values() if state["next"] <= now or _consume_payment_check(state["config"]["monitorId"])]
        if not due:
            timeout = min(state["next"] for state in states.values()) - now
            _wake.wait(max(0.05, min(timeout, 60)))
            _wake.clear()
            continue
        if client is None:
            try:
                _emit(callback, due[0]["config"]["monitorId"], "STARTING", f"{operator} 로그인 중")
                client = module._login(due[0]["config"])
            except Exception as error:
                for state in due:
                    state["errors"] += 1
                    monitor_id = state["config"]["monitorId"]
                    if state["errors"] >= 5:
                        _finish(states, monitor_id, callback, "ERROR", f"로그인 연속 오류 5회: {_safe(module, error, state['config'])}")
                    else:
                        _emit(callback, monitor_id, "WAITING", f"로그인 오류 · 재시도 {state['errors']}/5")
                        state["next"] = time.monotonic() + random.uniform(state["pollMin"], state["pollMax"])
                continue
        for state in due:
            monitor_id = state["config"]["monitorId"]
            if monitor_id not in states or _is_stopped(monitor_id):
                continue
            try:
                if operator == "KORAIL":
                    _process_korail(client, state, states, callback)
                else:
                    _process_srt(client, state, states, callback)
            except Exception as error:
                state["errors"] += 1
                client = None
                if state["errors"] >= 5:
                    _finish(states, monitor_id, callback, "ERROR", f"연속 오류 5회: {_safe(module, error, state['config'])}")
                else:
                    _emit(callback, monitor_id, "WAITING", f"처리 오류 · 재시도 {state['errors']}/5")
                    state["next"] = time.monotonic() + random.uniform(state["pollMin"], state["pollMax"])

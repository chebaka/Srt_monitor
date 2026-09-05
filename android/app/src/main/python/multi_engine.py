"""Shared-account monitor with a guarded one-shot reservation/payment path."""

import json
import random
import threading
import time

import korail_engine


_lock = threading.Lock()
_stopped = set()
_wake = threading.Event()
_TERMINAL_CODES = frozenset({
    "API_INCOMPATIBLE",
    "AUTH_REJECTED",
    "AUTH_REQUIRED",
    "AUTH_UNVERIFIED",
    "ERROR",
    "LEGACY_DISABLED",
    "SEAT_FOUND",
    "PAID",
    "UNCERTAIN",
    "STOPPED",
})


def stop_monitors(ids_json):
    ids = json.loads(ids_json)
    with _lock:
        _stopped.update(str(value) for value in ids)
    _wake.set()


def request_payment_check(monitor_id):
    # Kept for Android service compatibility. Monitoring mode has no payment state.
    return None


def _is_stopped(monitor_id):
    with _lock:
        return monitor_id in _stopped


def _emit(
    callback, monitor_id, code, message, train_no="", required=False,
    pnr="", amount=0, attempt_id="",
):
    payload = json.dumps({
        "monitorId": monitor_id,
        "statusCode": code,
        "terminal": code in _TERMINAL_CODES,
        "message": str(message)[:300],
        "trainNo": str(train_no),
        "deadline": "",
        "pnr": str(pnr),
        "amount": int(amount or 0),
        "attemptId": str(attempt_id),
    }, ensure_ascii=False)
    try:
        callback.onStatus(payload)
    except Exception:
        if required:
            raise


def _finish(states, monitor_id, callback, code, message, train_no=""):
    _emit(callback, monitor_id, code, message, train_no)
    states.pop(monitor_id, None)


def _finish_all(states, callback, code, message):
    for monitor_id in list(states):
        _finish(states, monitor_id, callback, code, message)


def _safe(error, config):
    return korail_engine._safe_error(error, config)


def _next_check(state):
    state["next"] = time.monotonic() + random.uniform(
        state["pollMin"], state["pollMax"]
    )


def _process_korail(client, state, states, callback):
    config = state["config"]
    monitor_id = config["monitorId"]
    _emit(callback, monitor_id, "SEARCHING", "KORAIL+ 열차 조회 중")
    result_code, candidate = korail_engine._find_candidate(client, config)
    if _is_stopped(monitor_id):
        _finish(states, monitor_id, callback, "STOPPED", "감시 중지")
        return
    state["errors"] = 0
    if result_code == "SEAT_FOUND":
        train_no = str(candidate.train_no or "")
        if config.get("autoPay"):
            def mutation_status(code, message, **metadata):
                try:
                    _emit(
                        callback, monitor_id, code, message, train_no,
                        required=True, **metadata,
                    )
                except Exception as error:
                    raise korail_engine.KorailPaymentBlockedError("상태 저장에 실패해 결제를 중단했어") from error
            try:
                amount = korail_engine._reserve_and_pay(
                    client, candidate, config, mutation_status,
                    lambda: _is_stopped(monitor_id),
                )
            except korail_engine.KorailMutationStoppedError as error:
                _finish(states, monitor_id, callback, "STOPPED", _safe(error, config), train_no)
                return
            except korail_engine.KorailMutationUncertainError as error:
                _finish(states, monitor_id, callback, "UNCERTAIN", _safe(error, config), train_no)
                return
            except korail_engine.KorailPaymentBlockedError as error:
                _finish(states, monitor_id, callback, "ERROR", _safe(error, config), train_no)
                return
            _finish(
                states, monitor_id, callback, "PAID",
                f"자동 예약·결제 완료 · {amount:,}원", train_no,
            )
            return
        _finish(
            states,
            monitor_id,
            callback,
            "SEAT_FOUND",
            "예약 가능한 좌석 발견 · KORAIL+에서 예매해",
            train_no,
        )
        return
    if result_code == "SOLD_OUT":
        _emit(callback, monitor_id, "SOLD_OUT", "조건 내 열차 매진 · 다음 조회 대기")
    elif result_code == "CABIN_UNAVAILABLE":
        _emit(
            callback,
            monitor_id,
            "CABIN_UNAVAILABLE",
            "선택 객실이 없는 열차뿐이야 · 다음 조회 대기",
        )
    else:
        _emit(
            callback,
            monitor_id,
            "NO_MATCHING_TRAIN",
            "조건에 맞는 열차 없음 · 다음 조회 대기",
        )
    _next_check(state)


def _same_account(configs):
    first_id, _ = korail_engine._normalize_login_id(configs[0].get("srtId"))
    first_password = str(configs[0].get("srtPassword", ""))
    return all(
        korail_engine._normalize_login_id(item.get("srtId"))[0] == first_id
        and str(item.get("srtPassword", "")) == first_password
        for item in configs
    )


def run_account_json(configs_json, callback):
    configs = json.loads(configs_json)
    if not isinstance(configs, list) or not configs:
        return
    operator = configs[0].get("operator", "")
    if operator != "KORAIL":
        code = "LEGACY_DISABLED" if operator == "SRT" else "ERROR"
        message = (
            "기존 SRT 감시는 실행할 수 없어"
            if operator == "SRT"
            else "지원하지 않는 철도 운영사야"
        )
        for config in configs:
            _emit(callback, str(config.get("monitorId", "")), code, message)
        return

    states = {}
    with _lock:
        for config in configs:
            _stopped.discard(str(config.get("monitorId", "")))
    for config in configs:
        monitor_id = str(config.get("monitorId", ""))
        try:
            if not monitor_id or config.get("operator") != operator:
                raise ValueError("계정 감시 설정이 일치하지 않아")
            korail_engine._validate_config(config)
            poll_min = max(30, int(config.get("pollMin", 30)))
            states[monitor_id] = {
                "config": config,
                "errors": 0,
                "pollMin": poll_min,
                "pollMax": max(poll_min, int(config.get("pollMax", 60))),
                "next": time.monotonic(),
            }
            _emit(callback, monitor_id, "STARTING", "KORAIL+ 좌석 감시 준비")
        except Exception as error:
            _emit(
                callback,
                monitor_id,
                "ERROR",
                f"입력 확인 실패: {_safe(error, config)}",
            )
    if not states:
        return
    if not _same_account([state["config"] for state in states.values()]):
        _finish_all(
            states,
            callback,
            "ERROR",
            "한 작업에 서로 다른 KORAIL+ 계정을 사용할 수 없어",
        )
        return
    if sum(bool(state["config"].get("autoPay")) for state in states.values()) > 1:
        _finish_all(states, callback, "ERROR", "계정당 자동결제 감시는 하나만 실행할 수 있어")
        return

    client = None
    login_errors = 0
    while states:
        for monitor_id in list(states):
            if _is_stopped(monitor_id):
                _finish(states, monitor_id, callback, "STOPPED", "감시 중지")
        if not states:
            break
        now = time.monotonic()
        due = [state for state in states.values() if state["next"] <= now]
        if not due:
            timeout = min(state["next"] for state in states.values()) - now
            _wake.wait(max(0.05, min(timeout, 60)))
            _wake.clear()
            continue
        if client is None:
            first = next(iter(states.values()))
            try:
                for state in states.values():
                    _emit(
                        callback,
                        state["config"]["monitorId"],
                        "AUTHENTICATING",
                        "KORAIL+ 로그인 확인 중",
                    )
                client = korail_engine._login(first["config"])
                login_errors = 0
                for state in states.values():
                    _emit(
                        callback,
                        state["config"]["monitorId"],
                        "AUTH_VERIFIED",
                        "KORAIL+ 로그인 확인 완료",
                    )
            except Exception as error:
                code = korail_engine._error_code(error)
                if korail_engine._is_terminal_error(error):
                    _finish_all(
                        states,
                        callback,
                        code,
                        f"로그인 중단: {_safe(error, first['config'])}",
                    )
                    break
                login_errors += 1
                if login_errors >= 5:
                    _finish_all(
                        states,
                        callback,
                        "ERROR",
                        f"로그인 연속 오류 5회: {_safe(error, first['config'])}",
                    )
                    break
                for state in states.values():
                    _emit(
                        callback,
                        state["config"]["monitorId"],
                        "LOGIN_RETRY",
                        f"로그인 오류 · 재시도 {login_errors}/5",
                    )
                    _next_check(state)
                continue
        for state in due:
            monitor_id = state["config"]["monitorId"]
            if monitor_id not in states or _is_stopped(monitor_id):
                continue
            try:
                _process_korail(client, state, states, callback)
            except Exception as error:
                if client is not None:
                    client.close()
                client = None
                code = korail_engine._error_code(error)
                if korail_engine._is_terminal_error(error):
                    _finish_all(
                        states,
                        callback,
                        code,
                        f"조회 중단: {_safe(error, state['config'])}",
                    )
                    break
                state["errors"] += 1
                if state["errors"] >= 5:
                    _finish(
                        states,
                        monitor_id,
                        callback,
                        "ERROR",
                        f"조회 연속 오류 5회: {_safe(error, state['config'])}",
                    )
                else:
                    _emit(
                        callback,
                        monitor_id,
                        "SEARCH_RETRY",
                        f"조회 오류 · 재시도 {state['errors']}/5",
                    )
                    _next_check(state)
                break
    if client is not None:
        client.close()

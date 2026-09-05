"""Fail-closed KORAIL+ search, reservation, and one-shot card payment."""

import re
import random
import string
import threading
import time
import uuid
from collections import deque
from urllib.parse import quote_plus

from korail_mobile_api import (
    CardPayment,
    DynapathConfig,
    DynapathTokenSettings,
    KorailAppUpdateRequiredError,
    KorailAuthContinuationRequired,
    KorailAuthError,
    KorailClient,
    KorailConfig,
    KorailPassengerCounts,
    KorailDynaPathError,
    KorailDynaPathRequiredError,
    KorailInvalidRequestError,
    KorailNoDirectTrainError,
    KorailNoResultsError,
    KorailProtocolError,
    KorailSeatClass,
    MutationConsent,
    TrainSearchQuery,
    TicketReservationDetailRequest,
)
from korail_mobile_api.constants import build_dalvik_user_agent
from korail_mobile_api.dynapath import (
    KORAIL_DYNAPATH_AS_VALUE,
    build_dynapath_prefix,
    encode_normal_be,
    make_dynapath_key,
    make_encode_table,
)


KORAIL_PLUS_API_VERSION = "250722001"
SEAT_AVAILABLE = "11"
SEAT_UNAVAILABLE = frozenset({"00", "13"})

_API_INCOMPATIBLE_ERRORS = (
    KorailAppUpdateRequiredError,
    KorailDynaPathError,
    KorailDynaPathRequiredError,
    KorailProtocolError,
)
_TERMINAL_ERRORS = _API_INCOMPATIBLE_ERRORS + (
    KorailAuthContinuationRequired,
    KorailAuthError,
    KorailInvalidRequestError,
)


class KorailIdentityUnverifiedError(KorailAuthError):
    pass


class KorailPaymentBlockedError(RuntimeError):
    """A known-safe failure where no charge was made or the hold was cancelled."""


class KorailMutationUncertainError(RuntimeError):
    """A mutation may have reached the server; it must never be retried."""


class KorailMutationStoppedError(KorailPaymentBlockedError):
    pass


def _form_encode(fields):
    def encode(value):
        return quote_plus(str(value), safe="*-._").replace("~", "%7E")

    return "&".join(f"{encode(key)}={encode(value)}" for key, value in fields)


class _KorailPlusDynapathTokenProvider:
    """7.0.5 SDK token provider with its stateful recent-request timings."""

    def __init__(self, settings):
        self.settings = settings
        self.last_timestamp = int(settings.app_start_ts)
        self.recent_deltas = deque(maxlen=5)
        self.lock = threading.Lock()

    def __call__(self, _context=None):
        with self.lock:
            timestamp = int(time.time() * 1000)
            self.recent_deltas.append(timestamp - self.last_timestamp)
            self.last_timestamp = timestamp
            settings = self.settings
            fields = [
                ("ai", settings.app_id),
                ("di", settings.device_id),
                ("as", settings.as_value),
                ("su", str(settings.secure_user).lower()),
                ("dbg", str(settings.debug).lower()),
                ("emu", str(settings.emulator).lower()),
                ("hk", str(settings.hooked).lower()),
                ("it", settings.app_start_ts),
                ("ts", timestamp),
                *((("rt", value) for value in self.recent_deltas)),
                ("os", settings.os_version),
                ("dm", settings.device_model),
                ("st", settings.os_type),
                ("sv", settings.sdk_version),
            ]
            payload = _form_encode(fields)
            random_text = "".join(random.choices(
                string.ascii_lowercase + string.ascii_uppercase + string.digits,
                k=4,
            ))
            dyn_key = f"{settings.sdk_version}+{random_text}+{timestamp}"
            encoded_key = encode_normal_be(
                dyn_key,
                settings.table,
                i8=settings.i8,
                i9=settings.i9,
                i10=settings.i10,
            )
            custom_table = make_encode_table(
                make_dynapath_key(dyn_key),
                settings.i9,
                settings.table,
            )
            encoded_body = encode_normal_be(
                payload,
                custom_table,
                i8=settings.i8,
                i9=settings.i9,
                i10=settings.i10,
            )
            prefix = build_dynapath_prefix(
                table=settings.table,
                table_index=settings.table_index,
                i11=settings.i10,
                i12=settings.i9,
            )
            return (
                f"{prefix}{settings.table[len(encoded_key)]}"
                f"{encoded_key}{encoded_body}"
            )


def _normalize_login_id(value):
    login_id = str(value or "").strip()
    if "@" in login_id:
        return login_id, "5"
    compact = re.sub(r"[\s-]", "", login_id)
    if compact.isascii() and compact.isdigit():
        if compact.startswith("01") and len(compact) in (10, 11):
            return compact, "4"
        return compact, "2"
    return login_id, "2"


def _client_config(config):
    settings = DynapathTokenSettings(
        device_id=config["deviceId"],
        as_value=KORAIL_DYNAPATH_AS_VALUE,
        app_start_ts=str(int(time.time() * 1000)),
        os_version=config["osVersion"],
        device_model=config["deviceModel"],
    )
    dynapath = DynapathConfig(
        enabled=True,
        token_provider=_KorailPlusDynapathTokenProvider(settings),
        device_name=config["deviceModel"],
        os_version=config["osVersion"],
    )
    return KorailConfig(
        version=KORAIL_PLUS_API_VERSION,
        dynapath=dynapath,
        user_agent=build_dalvik_user_agent(
            os_release=config["osVersion"],
            device_model=config["deviceModel"],
        ),
        device_width=int(config["deviceWidth"]),
        device_height=int(config["deviceHeight"]),
        android_sdk_int=int(config["androidSdkInt"]),
    )


def _login(config):
    login_id, input_flag = _normalize_login_id(config["srtId"])
    client = KorailClient(_client_config(config))
    try:
        session = client.login(login_id, config["srtPassword"], input_flag=input_flag)
        if not (
            str(getattr(session, "member_card_no", "") or "").strip()
            and str(getattr(session, "customer_no", "") or "").strip()
        ):
            raise KorailIdentityUnverifiedError(
                "KORAIL+ login response did not prove account identity"
            )
        return client
    except Exception:
        client.close()
        raise


def _find_candidate(client, config):
    """Return a status and train pair; reject unknown inventory codes."""
    query = TrainSearchQuery(
        config["depCode"],
        config["arrCode"],
        config["date"],
        departure_time=config["timeFrom"],
        passengers=config["passengers"],
        include_srt=True,
    )
    continuation = None
    found_sold_out = False
    found_cabin_unavailable = False

    def unavailable_status():
        if found_sold_out:
            return "SOLD_OUT"
        if found_cabin_unavailable:
            return "CABIN_UNAVAILABLE"
        return "NO_MATCHING_TRAIN"

    for _ in range(10):
        try:
            result = client.search_trains(query, continuation=continuation)
        except (KorailNoDirectTrainError, KorailNoResultsError):
            return unavailable_status(), None
        for train in result.trains:
            departure_time = str(train.departure_time or "")
            if not re.fullmatch(r"[0-9]{6}", departure_time):
                raise KorailProtocolError(
                    f"API_CHANGED: invalid KORAIL+ departure time {departure_time!r}"
                )
            if departure_time > config["timeTo"]:
                return unavailable_status(), None
            if not config["timeFrom"] <= departure_time <= config["timeTo"]:
                continue
            raw_code = (
                train.special_reservation_code
                if config["special"]
                else train.general_reservation_code
            )
            code = str(raw_code or "").strip()
            if code == SEAT_AVAILABLE:
                return "SEAT_FOUND", train
            if code == "13":
                found_sold_out = True
            elif code == "00":
                found_cabin_unavailable = True
            else:
                raise KorailProtocolError(
                    f"API_CHANGED: unknown KORAIL+ seat code {code!r}"
                )
        continuation = result.next_page()
        if continuation is None:
            return unavailable_status(), None
    raise KorailProtocolError(
        "API_CHANGED: KORAIL+ search pagination exceeded 10 pages"
    )


def _response_succeeded(response):
    return str(getattr(response, "str_result", "") or "").upper() == "SUCC"


def _raw_contains_value(value, expected):
    if isinstance(value, dict):
        return any(_raw_contains_value(item, expected) for item in value.values())
    if isinstance(value, (list, tuple)):
        return any(_raw_contains_value(item, expected) for item in value)
    return str(value or "").strip() == expected


def _dicts(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from _dicts(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            yield from _dicts(item)


def _paid_ticket_matches(raw, pnr, train, config, amount):
    records = [
        item for item in _dicts(raw)
        if str(item.get("h_pnr_no", "") or "").strip() == pnr
    ]
    expected_room = "2" if config["special"] else "1"
    for record in records:
        required = (
            str(train.train_no), config["date"], str(train.departure_time),
            config["dep"], config["arr"],
        )
        if not all(_raw_contains_value(record, value) for value in required):
            continue
        seat_rows = [item for item in _dicts(record) if str(item.get("h_seat_no", "") or "").strip()]
        if len(seat_rows) != config["passengers"]:
            continue
        if any(str(item.get("h_psrm_cl_cd", "") or "") != expected_room for item in seat_rows):
            continue
        amounts = [str(item.get("h_rcvd_amt", "") or "") for item in seat_rows]
        if not all(re.fullmatch(r"[0-9]+", value) for value in amounts):
            continue
        if sum(int(value) for value in amounts) != amount:
            continue
        return True
    return False


def _matches_trip(item, train, config):
    return (
        str(getattr(item, "train_no", "") or "") == str(train.train_no)
        and str(getattr(item, "run_date", "") or "") == config["date"]
        and str(getattr(item, "departure_time", "") or "") == str(train.departure_time)
        and str(getattr(item, "departure_station", "") or "") == config["dep"]
        and str(getattr(item, "arrival_station", "") or "") == config["arr"]
    )


def _has_duplicate(client, train, config):
    history = client.get_reservation_history()
    if any(_matches_trip(item, train, config) for item in history.trains):
        return True
    tickets = client.get_ticket_list(mode="1")
    raw = getattr(tickets, "raw", {})
    values = (
        str(train.train_no), config["date"], str(train.departure_time),
        config["dep"], config["arr"],
    )
    return all(_raw_contains_value(raw, value) for value in values)


def _cancel_hold(client, hold):
    try:
        response = client.cancel_unpaid_hold(
            hold,
            consent=MutationConsent(allow_cancel=True, dry_run=False),
        )
    except Exception as error:
        raise KorailMutationUncertainError("미결제 예약 취소 결과가 불명확해") from error
    if not _response_succeeded(response):
        raise KorailMutationUncertainError("미결제 예약 취소 결과가 불명확해")


def _validate_hold(client, hold, train, config):
    pnr = str(getattr(hold, "pnr_no", "") or "").strip()
    if not _response_succeeded(hold) or not pnr:
        raise KorailMutationUncertainError("예약 응답에 확정 식별자가 없어")
    if str(getattr(hold, "journey_count", "") or "") != "1":
        raise KorailPaymentBlockedError("예약 구간 수가 승인 조건과 달라")
    journeys = tuple(getattr(hold, "journeys", ()) or ())
    if len(journeys) != 1:
        raise KorailPaymentBlockedError("예약 상세를 검증할 수 없어")
    journey = journeys[0]
    expected = (
        (journey.departure_date, config["date"]),
        (journey.departure_time, str(train.departure_time)),
        (journey.train_no, str(train.train_no)),
        (journey.departure_station_code, config["depCode"]),
        (journey.arrival_station_code, config["arrCode"]),
    )
    if any(str(actual or "") != wanted for actual, wanted in expected):
        raise KorailPaymentBlockedError("예약 열차가 승인 조건과 달라")
    amount_text = str(getattr(hold, "received_amount", "") or "")
    if not re.fullmatch(r"[0-9]+", amount_text):
        raise KorailPaymentBlockedError("결제 금액을 검증할 수 없어")
    amount = int(amount_text)
    if amount <= 0 or amount > config["maxFareWon"]:
        raise KorailPaymentBlockedError("결제 금액이 승인 상한을 벗어났어")
    detail = client.get_ticket_reservation_detail(
        TicketReservationDetailRequest(pnr_no=pnr)
    )
    detail_amount = str(getattr(detail, "total_received_amount", "") or "")
    if not re.fullmatch(r"[0-9]+", detail_amount) or int(detail_amount) != amount:
        raise KorailPaymentBlockedError("예약 상세 금액이 예약 응답과 달라")
    detail_journeys = tuple(getattr(detail, "journeys", ()) or ())
    if str(getattr(detail, "pnr_no", "") or "") != pnr or len(detail_journeys) != 1:
        raise KorailPaymentBlockedError("예약 상세의 구간을 검증할 수 없어")
    detail_journey = detail_journeys[0]
    if any(str(actual or "") != wanted for actual, wanted in (
        (detail_journey.departure_date, config["date"]),
        (detail_journey.departure_time, str(train.departure_time)),
        (detail_journey.train_no, str(train.train_no)),
        (detail_journey.departure_station_name, config["dep"]),
        (detail_journey.arrival_station_name, config["arr"]),
    )):
        raise KorailPaymentBlockedError("예약 상세 열차가 승인 조건과 달라")
    seats = tuple(getattr(detail_journey, "seats", ()) or ())
    expected_room = "2" if config["special"] else "1"
    if len(seats) != config["passengers"] or any(
        str(getattr(seat, "room_class_code", "") or "") != expected_room
        for seat in seats
    ):
        raise KorailPaymentBlockedError("예약 상세의 인원 또는 객실이 승인 조건과 달라")
    seat_amounts = [str(getattr(seat, "received_amount", "") or "") for seat in seats]
    if not all(re.fullmatch(r"[0-9]+", value) for value in seat_amounts) or sum(map(int, seat_amounts)) != amount:
        raise KorailPaymentBlockedError("예약 상세의 좌석 금액 합계가 달라")
    history = client.get_reservation_history()
    if not any(
        str(getattr(item, "pnr_no", "") or "") == pnr
        and _matches_trip(item, train, config)
        for item in history.trains
    ):
        raise KorailPaymentBlockedError("예약내역 교차 확인에 실패했어")
    return amount


def _reserve_and_pay(client, train, config, emit, should_stop=lambda: False):
    attempt_id = uuid.uuid4().hex
    if _has_duplicate(client, train, config):
        raise KorailPaymentBlockedError("같은 열차의 예약 또는 승차권이 이미 있어")
    emit("RESERVE_IN_FLIGHT", "예약 요청 전송 중", attempt_id=attempt_id)
    try:
        hold = client.reserve(
            train,
            passengers=KorailPassengerCounts(adult=config["passengers"]),
            seat_class=KorailSeatClass.SPECIAL if config["special"] else KorailSeatClass.GENERAL,
            consent=MutationConsent(allow_reserve=True, dry_run=False),
        )
    except Exception as error:
        raise KorailMutationUncertainError("예약 결과가 불명확해") from error
    try:
        pnr = str(getattr(hold, "pnr_no", "") or "").strip()
        emit("HOLD_CREATED", "미결제 예약 생성 · 조건 검증 중", pnr=pnr, attempt_id=attempt_id)
        amount = _validate_hold(client, hold, train, config)
        if should_stop():
            raise KorailMutationStoppedError("사용자가 중지해 미결제 예약을 취소했어")
        emit(
            "PAYMENT_IN_FLIGHT", f"카드 결제 1회 전송 중 · {amount:,}원",
            pnr=pnr, amount=amount, attempt_id=attempt_id,
        )
    except KorailMutationUncertainError:
        raise
    except Exception as error:
        _cancel_hold(client, hold)
        if isinstance(error, KorailPaymentBlockedError):
            raise
        raise KorailPaymentBlockedError("결제 전 검증에 실패했어") from error
    card = CardPayment(
        card_number=config["cardNumber"],
        card_password=config["cardPassword"],
        card_expire=config["cardExpire"],
        birthday=config["cardValidation"],
    )
    try:
        payment = client.pay_with_card(
            hold,
            card,
            consent=MutationConsent(
                allow_payment=True,
                dry_run=False,
                fake_card_only=False,
                real_card_acknowledged=True,
            ),
        )
    except Exception as error:
        raise KorailMutationUncertainError("결제 결과가 불명확해") from error
    if not _response_succeeded(payment):
        try:
            tickets = client.get_ticket_list(mode="1")
            charged = _paid_ticket_matches(
                getattr(tickets, "raw", {}), pnr, train, config, amount
            )
        except Exception as error:
            raise KorailMutationUncertainError("결제 실패 응답의 실제 발권 여부가 불명확해") from error
        if charged:
            return amount
        _cancel_hold(client, hold)
        raise KorailPaymentBlockedError("카드 결제가 승인되지 않았어")
    try:
        tickets = client.get_ticket_list(mode="1")
        verified = _paid_ticket_matches(
            getattr(tickets, "raw", {}), pnr, train, config, amount
        )
    except Exception as error:
        raise KorailMutationUncertainError("결제 후 승차권을 확인하지 못했어") from error
    if not verified:
        raise KorailMutationUncertainError("결제 후 승차권을 확인하지 못했어")
    return amount


def _safe_error(error, config):
    message = str(error)
    for key in (
        "srtId",
        "srtPassword",
        "cardNumber",
        "cardPassword",
        "cardExpire",
        "cardValidation",
    ):
        secret = str(config.get(key, "")).strip()
        if secret:
            message = message.replace(secret, "[redacted]")
    return message[:180]


def _error_code(error):
    if isinstance(error, KorailMutationUncertainError):
        return "UNCERTAIN"
    if isinstance(error, _API_INCOMPATIBLE_ERRORS):
        return "API_INCOMPATIBLE"
    if isinstance(error, KorailAuthContinuationRequired):
        return "AUTH_REQUIRED"
    if isinstance(error, KorailIdentityUnverifiedError):
        return "AUTH_UNVERIFIED"
    if isinstance(error, KorailAuthError):
        return "AUTH_REJECTED"
    return "ERROR"


def _is_terminal_error(error):
    return isinstance(error, _TERMINAL_ERRORS + (KorailPaymentBlockedError, KorailMutationUncertainError))


def _validate_config(config):
    if config.get("operator") != "KORAIL":
        raise ValueError("KORAIL+ 엔진에 KORAIL 외 설정이 전달됐어")
    required = (
        "srtId", "srtPassword", "dep", "arr", "date", "timeFrom", "timeTo",
        "depCode", "arrCode", "deviceId", "osVersion", "deviceModel",
        "deviceWidth", "deviceHeight", "androidSdkInt",
    )
    if any(not str(config.get(key, "")).strip() for key in required):
        raise ValueError("필수 KORAIL+ 조건이 비어 있어")
    config["srtId"], config["loginInputFlag"] = _normalize_login_id(config["srtId"])
    if config["loginInputFlag"] == "5" and (
        config["srtId"].count("@") != 1
        or any(character.isspace() for character in config["srtId"])
    ):
        raise ValueError("이메일 형식의 KORAIL+ 계정을 확인해")
    if not re.fullmatch(r"[0-9a-f]{16}", str(config["deviceId"]).lower()):
        raise ValueError("Android 기기 식별자를 확인하지 못했어")
    for key, pattern in (
        ("date", r"[0-9]{8}"),
        ("timeFrom", r"[0-9]{6}"),
        ("timeTo", r"[0-9]{6}"),
    ):
        if not re.fullmatch(pattern, str(config[key])):
            raise ValueError("날짜 또는 시간 형식이 잘못됐어")
    if config["timeFrom"] > config["timeTo"]:
        raise ValueError("종료 시각은 시작 시각 이후여야 해")
    for key in ("dep", "arr"):
        if not re.fullmatch(r"[가-힣A-Za-z0-9()·\-\s]{1,30}", str(config[key]).strip()):
            raise ValueError("역 이름에 허용되지 않는 문자가 있어")
    if config["dep"] == config["arr"]:
        raise ValueError("출발역과 도착역은 달라야 해")
    if any(
        not re.fullmatch(r"[0-9]{4}", str(config[key]))
        for key in ("depCode", "arrCode")
    ):
        raise ValueError("KORAIL+ 역 코드가 잘못됐어")
    try:
        passengers = int(config["passengers"])
    except (TypeError, ValueError) as error:
        raise ValueError("승객 수가 잘못됐어") from error
    if passengers not in range(1, 10):
        raise ValueError("승객 수는 1~9명이어야 해")
    config["passengers"] = passengers
    if config.get("windowSeat"):
        raise ValueError("KORAIL+ 창가 우선은 아직 지원하지 않아")
    if config.get("autoPay"):
        for key, pattern in (
            ("cardNumber", r"[0-9]{14,19}"),
            ("cardPassword", r"[0-9]{2}"),
            ("cardExpire", r"[0-9]{4}"),
            ("cardValidation", r"[0-9]{6,10}"),
        ):
            if not re.fullmatch(pattern, str(config.get(key, ""))):
                raise ValueError("자동결제 카드정보 형식이 올바르지 않아")
        try:
            max_fare = int(config.get("maxFareWon", 0))
        except (TypeError, ValueError) as error:
            raise ValueError("최대 결제금액이 올바르지 않아") from error
        if max_fare <= 0:
            raise ValueError("최대 결제금액은 1원 이상이어야 해")
        config["maxFareWon"] = max_fare

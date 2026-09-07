"""Fail-closed KORAIL+ search, reservation, and one-shot card payment."""

import json
import re
import random
import string
import threading
import time
import uuid
from collections import deque
from types import SimpleNamespace
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
    wanted_train = str(config.get("trainNo") or "").strip()

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
            if wanted_train and str(getattr(train, "train_no", "") or "").strip() != wanted_train:
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


def _is_single_journey(count):
    return re.fullmatch(r"0*1", str(count or "").strip()) is not None


def _dicts(value):
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from _dicts(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            yield from _dicts(item)


_TRIP_FIELD_ALIASES = {
    "train_no": ("h_trn_no", "train_no", "trainNo"),
    "date": ("h_run_dt", "run_date", "runDate", "departure_date", "departureDate", "date"),
    "departure_time": ("h_dpt_tm", "departure_time", "departureTime"),
    "departure_station": ("h_dpt_rs_stn_nm", "departure_station", "departureStation", "dep"),
    "arrival_station": ("h_arv_rs_stn_nm", "arrival_station", "arrivalStation", "arr"),
}
_JOURNEY_KEYS = frozenset(
    key for field, aliases in _TRIP_FIELD_ALIASES.items() if field != "date" for key in aliases
)


def _raw_trip_fields(value):
    if not isinstance(value, dict):
        return {}
    fields = {}
    for field, aliases in _TRIP_FIELD_ALIASES.items():
        for key in aliases:
            if key in value and str(value[key] or "").strip():
                fields[field] = str(value[key]).strip()
                break
    return fields


def _raw_ticket_units(value, inherited_pnr="", inherited_fields=None):
    inherited_fields = inherited_fields or {}
    if isinstance(value, dict):
        own_pnr = str(value.get("h_pnr_no") or value.get("pnr_no") or "").strip()
        pnr = own_pnr or inherited_pnr
        scoped_fields = {} if own_pnr and own_pnr != inherited_pnr else dict(inherited_fields)
        header = value.get("header")
        if pnr and isinstance(header, dict) and not _JOURNEY_KEYS.intersection(header):
            header_pnr = str(header.get("h_pnr_no") or header.get("pnr_no") or pnr).strip()
            if header_pnr == pnr:
                scoped_fields.update(_raw_trip_fields(header))
        fields = _raw_trip_fields(value)
        if _JOURNEY_KEYS.intersection(value):
            unit = dict(value)
            for field, actual in scoped_fields.items():
                if field not in fields:
                    unit[_TRIP_FIELD_ALIASES[field][0]] = actual
            if pnr:
                yield pnr, unit
            child_fields = scoped_fields
        else:
            child_fields = dict(scoped_fields)
            child_fields.update(fields)
        for child in value.values():
            yield from _raw_ticket_units(child, pnr, child_fields)
    elif isinstance(value, (list, tuple)):
        for child in value:
            yield from _raw_ticket_units(child, inherited_pnr, inherited_fields)


def _raw_trip_matches(record, train, config):
    expected = {
        "train_no": str(train.train_no).strip(), "date": str(config["date"]).strip(),
        "departure_time": str(train.departure_time).strip(),
        "departure_station": str(config["dep"]).strip(),
        "arrival_station": str(config["arr"]).strip(),
    }
    actual = _raw_trip_fields(record)
    return all(actual.get(field) == value for field, value in expected.items())


def _scoped_seat_rows(record, pnr, train, config):
    """Seat rows that belong to this PNR and do not contradict this trip.

    A matching journey unit can nest foreign content (another PNR's seats
    or a different trip's journey). Counting those as ours would confirm a
    payment that never happened, so any nested dict with a different PNR or
    contradictory trip fields prunes its whole subtree.
    """
    expected = {
        "train_no": str(train.train_no).strip(),
        "date": str(config["date"]).strip(),
        "departure_time": str(train.departure_time).strip(),
        "departure_station": str(config["dep"]).strip(),
        "arrival_station": str(config["arr"]).strip(),
    }
    rows = []

    def visit(value, scope_pnr):
        if isinstance(value, dict):
            own = str(value.get("h_pnr_no") or value.get("pnr_no") or "").strip()
            scope = own or scope_pnr
            if scope != pnr:
                return
            if value is not record:
                actual = _raw_trip_fields(value)
                if any(field in actual and actual[field] != expected[field] for field in expected):
                    return
            if str(value.get("h_seat_no", "") or "").strip():
                rows.append(value)
            for child in value.values():
                visit(child, scope)
        elif isinstance(value, (list, tuple)):
            for child in value:
                visit(child, scope_pnr)

    visit(record, pnr)
    return rows


def _trip_snapshot(train, config):
    return {
        "trainNo": str(getattr(train, "train_no", "") or "").strip(),
        "departureTime": str(getattr(train, "departure_time", "") or "").strip(),
        "date": str(config["date"]),
        "dep": str(config["dep"]),
        "arr": str(config["arr"]),
        "depCode": str(config["depCode"]),
        "arrCode": str(config["arrCode"]),
        "passengers": int(config["passengers"]),
        "special": bool(config["special"]),
        "maxFareWon": int(config.get("maxFareWon", 0) or 0),
    }


def _paid_ticket_matches(raw, pnr, train, config, amount):
    expected_room = "2" if config["special"] else "1"
    for record_pnr, record in _raw_ticket_units(raw):
        if record_pnr != pnr or not _raw_trip_matches(record, train, config):
            continue
        seat_rows = _scoped_seat_rows(record, pnr, train, config)
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
    return any(
        _raw_trip_matches(record, train, config)
        for _ticket_pnr, record in _raw_ticket_units(raw)
    )


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
    if not _is_single_journey(getattr(hold, "journey_count", "")):
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
    trip = _trip_snapshot(train, config)
    train_class_code = str(getattr(train, "train_class_code", "00") or "")
    if not train_class_code.isdecimal() and not config.get("allowHighSpeedAuto"):
        raise KorailPaymentBlockedError(
            "이 KORAIL+ 고속열차의 최신 예약 형식을 아직 검증하지 않아 자동 결제를 시작하지 않았어"
        )
    if _has_duplicate(client, train, config):
        raise KorailPaymentBlockedError("같은 열차의 예약 또는 승차권이 이미 있어")
    emit("RESERVE_IN_FLIGHT", "예약 요청 전송 중", attempt_id=attempt_id, trip=trip)
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
        emit(
            "HOLD_CREATED", "미결제 예약 생성 · 조건 검증 중",
            pnr=pnr, attempt_id=attempt_id, trip=trip,
        )
        amount = _validate_hold(client, hold, train, config)
        if should_stop():
            raise KorailMutationStoppedError("사용자가 중지해 미결제 예약을 취소했어")
        emit(
            "PAYMENT_IN_FLIGHT", f"카드 결제 1회 전송 중 · {amount:,}원",
            pnr=pnr, amount=amount, attempt_id=attempt_id, trip=trip,
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
            return {"pnr": pnr, "amount": amount, "attemptId": attempt_id, "trip": trip}
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
    return {"pnr": pnr, "amount": amount, "attemptId": attempt_id, "trip": trip}


def _payment_result(status, message, pnr="", amount=0, attempt_id="", trip=None):
    return json.dumps({
        "status": status,
        "statusCode": status,
        "terminal": True,
        "message": str(message)[:300],
        "pnr": str(pnr),
        "amount": int(amount or 0),
        "attemptId": str(attempt_id),
        "trip": trip if isinstance(trip, dict) else {},
    }, ensure_ascii=False)


def _load_json_object(value):
    if isinstance(value, str):
        value = json.loads(value)
    if not isinstance(value, dict):
        raise ValueError("JSON object required")
    return value


def _validated_attempt(config, attempt):
    trip = attempt.get("trip")
    if not isinstance(trip, dict):
        raise ValueError("attempt trip snapshot missing")
    required = (
        "trainNo", "departureTime", "date", "dep", "arr", "depCode", "arrCode",
        "passengers", "special", "maxFareWon",
    )
    if any(key not in trip for key in required):
        raise ValueError("attempt trip snapshot incomplete")
    train = SimpleNamespace(
        train_no=str(trip["trainNo"]), departure_time=str(trip["departureTime"]),
    )
    expected = _trip_snapshot(train, config)
    for key in required:
        if isinstance(expected[key], bool):
            matches = trip[key] is expected[key]
        elif isinstance(expected[key], int):
            actual = trip[key]
            if isinstance(actual, bool):
                matches = False
            elif isinstance(actual, int):
                matches = actual == expected[key]
            elif isinstance(actual, str) and re.fullmatch(r"[0-9]+", actual.strip()):
                matches = int(actual) == expected[key]
            else:
                matches = False
        else:
            matches = str(trip[key]) == expected[key]
        if not matches:
            raise ValueError("attempt trip does not match config")
    if (
        not re.fullmatch(r"[0-9]{6}", str(trip["departureTime"]))
        or not config["timeFrom"] <= str(trip["departureTime"]) <= config["timeTo"]
    ):
        raise ValueError("attempt departure time does not match config window")
    pinned = str(config.get("trainNo") or "").strip()
    if pinned and str(trip["trainNo"]) != pinned:
        raise ValueError("attempt train does not match config")
    pnr = str(attempt.get("pnr") or "").strip()
    attempt_id = str(attempt.get("attemptId") or "").strip()
    if not pnr or not attempt_id:
        raise ValueError("attempt identity missing")
    amount = attempt.get("amount")
    if isinstance(amount, bool) or (
        not isinstance(amount, int)
        and not (isinstance(amount, str) and re.fullmatch(r"[0-9]+", amount.strip()))
    ):
        raise ValueError("attempt amount invalid")
    try:
        amount = int(amount)
    except (TypeError, ValueError) as error:
        raise ValueError("attempt amount invalid") from error
    if amount <= 0 or amount > int(trip["maxFareWon"]):
        raise ValueError("attempt amount outside approved limit")
    return pnr, amount, attempt_id, trip, train


def check_payment_json(config_json, attempt_json):
    """Read-only ticket recheck; returns only PAID or UNCERTAIN JSON."""
    client = None
    try:
        config = _load_json_object(config_json)
        attempt = _load_json_object(attempt_json)
        _validate_config(config)
        if not config.get("autoPay"):
            raise ValueError("payment check requires auto-pay config")
        pnr, amount, attempt_id, trip, train = _validated_attempt(config, attempt)
    except Exception:
        return _payment_result("UNCERTAIN", "결제 확인 입력을 검증하지 못했어")
    try:
        client = _login(config)
        last_error = None
        for _ in range(3):
            try:
                tickets = client.get_ticket_list(mode="1")
                if _paid_ticket_matches(
                    getattr(tickets, "raw", {}), pnr, train, config, amount
                ):
                    return _payment_result(
                        "PAID", "발권 목록에서 결제를 재확인했어",
                        pnr, amount, attempt_id, trip,
                    )
            except Exception as error:
                last_error = error
        message = "발권 목록에서 결제를 확인하지 못했어"
        if last_error is not None:
            message = _safe_error(last_error, config)
        return _payment_result("UNCERTAIN", message, pnr, amount, attempt_id, trip)
    except Exception as error:
        return _payment_result(
            "UNCERTAIN", _safe_error(error, config), pnr, amount, attempt_id, trip
        )
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass


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
    train_no = str(config.get("trainNo") or "").strip()
    if train_no and not re.fullmatch(r"[0-9]{1,5}", train_no):
        raise ValueError("열차번호 형식이 잘못됐어")
    config["trainNo"] = train_no
    if config.get("allowHighSpeedAuto") and not config.get("autoPay"):
        raise ValueError("고속열차 자동 처리는 자동결제 감시에서만 켤 수 있어")
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

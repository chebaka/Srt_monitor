"""Read-only KORAIL+ adapter.

The bundled client still targets the pre-7.0 protocol. This adapter fails
closed: it verifies login identity, recognizes only confirmed seat codes, and
never sends a reservation request.
"""

import re
import random
import string
import threading
import time
from collections import deque
from urllib.parse import quote_plus

from korail_mobile_api import (
    DynapathConfig,
    DynapathTokenSettings,
    KorailAppUpdateRequiredError,
    KorailAuthContinuationRequired,
    KorailAuthError,
    KorailClient,
    KorailConfig,
    KorailDynaPathError,
    KorailDynaPathRequiredError,
    KorailInvalidRequestError,
    KorailNoDirectTrainError,
    KorailNoResultsError,
    KorailProtocolError,
    TrainSearchQuery,
)
from korail_mobile_api.constants import build_dalvik_user_agent
from korail_mobile_api.dynapath import (
    KORAIL_DYNAPATH_AS_VALUE,
    build_dynapath_prefix,
    encode_normal_be,
    make_dynapath_key,
    make_encode_table,
)


RESERVATION_ENABLED = False
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


def _reserve(client, train, config):
    raise KorailProtocolError(
        "KORAIL+ reservation is disabled until 7.0.5 protocol verification"
    )


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
    return isinstance(error, _TERMINAL_ERRORS)


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
        raise ValueError("KORAIL+ 자동결제는 비활성화됐어")

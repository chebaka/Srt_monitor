"""KORAIL+ adapter. Reservation allowed; payment deliberately absent."""

import re
import time
from datetime import datetime

from korail_mobile_api import (
    DynapathConfig,
    DynapathTokenSettings,
    KorailAppUpdateRequiredError,
    KorailAuthError,
    KorailClient,
    KorailConfig,
    KorailDynaPathError,
    KorailDynaPathRequiredError,
    KorailInvalidRequestError,
    KorailMutationNotAllowedError,
    KorailNoDirectTrainError,
    KorailNoResultsError,
    KorailPassengerCounts,
    KorailProtocolError,
    KorailReservationRefusedError,
    KorailSeatClass,
    KorailSeatUnavailableError,
    KorailSoldOutError,
    KorailTransportError,
    MutationConsent,
    TrainSearchQuery,
)
from korail_mobile_api.constants import build_dalvik_user_agent
from korail_mobile_api.dynapath import KORAIL_DYNAPATH_AS_VALUE


_TERMINAL_ERRORS = (
    KorailAppUpdateRequiredError,
    KorailAuthError,
    KorailDynaPathError,
    KorailDynaPathRequiredError,
    KorailInvalidRequestError,
    KorailMutationNotAllowedError,
    KorailProtocolError,
    KorailReservationRefusedError,
)
_INVENTORY_ERRORS = (KorailSoldOutError, KorailSeatUnavailableError)


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
        token_settings=settings,
        device_name=config["deviceModel"],
        os_version=config["osVersion"],
    )
    return KorailConfig(
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
    client = KorailClient(_client_config(config))
    try:
        client.login(config["srtId"], config["srtPassword"])
        return client
    except Exception:
        client.close()
        raise


def _history_matches(item, config, train_no=""):
    return (
        item.run_date == config["date"]
        and item.departure_station == config["dep"]
        and item.arrival_station == config["arr"]
        and config["timeFrom"] <= str(item.departure_time or "") <= config["timeTo"]
        and (not train_no or str(item.train_no or "") == str(train_no))
    )


def _existing_reservation(client, config):
    return next(
        (item for item in client.get_reservation_history().trains if _history_matches(item, config)),
        None,
    )


def _raw_rows(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from _raw_rows(child)
    elif isinstance(value, list):
        for child in value:
            yield from _raw_rows(child)


def _has_paid_ticket(client, config, train_no=""):
    history = client.get_reservation_history().trains
    if any(
        _history_matches(item, config, train_no)
        and (item.payment_flag == "Y" or item.settlement_flag == "Y")
        for item in history
    ):
        return True
    raw = client.get_ticket_list().raw
    for row in _raw_rows(raw):
        if (
            str(row.get("h_dpt_dt") or row.get("h_run_dt") or "") == config["date"]
            and str(row.get("h_dpt_rs_stn_nm") or "") == config["dep"]
            and str(row.get("h_arv_rs_stn_nm") or "") == config["arr"]
            and config["timeFrom"] <= str(row.get("h_dpt_tm") or "") <= config["timeTo"]
            and (not train_no or str(row.get("h_trn_no") or "") == str(train_no))
        ):
            return True
    return False


def _find_candidate(client, config):
    query = TrainSearchQuery(
        config["depCode"],
        config["arrCode"],
        config["date"],
        departure_time=config["timeFrom"],
        passengers=config["passengers"],
        include_srt=True,
    )
    continuation = None
    for _ in range(10):
        try:
            result = client.search_trains(query, continuation=continuation)
        except (KorailNoDirectTrainError, KorailNoResultsError):
            return None
        for train in result.trains:
            departure_time = str(train.departure_time or "")
            if departure_time > config["timeTo"]:
                return None
            code = train.special_reservation_code if config["special"] else train.general_reservation_code
            if config["timeFrom"] <= departure_time <= config["timeTo"] and code and code != "13":
                return train
        continuation = result.next_page()
        if continuation is None:
            return None
    raise KorailProtocolError("KORAIL+ search pagination exceeded 10 pages")


def _reserve(client, train, config):
    hold = client.reserve(
        train,
        consent=MutationConsent(allow_reserve=True, dry_run=False),
        passengers=KorailPassengerCounts(adult=config["passengers"]),
        seat_class=KorailSeatClass.SPECIAL if config["special"] else KorailSeatClass.GENERAL,
    )
    if not getattr(hold, "pnr_no", None):
        raise KorailProtocolError("API_CHANGED: KORAIL+ reservation returned no PNR")
    return hold


def _reservation_train_no(reservation):
    if hasattr(reservation, "train_no"):
        return str(reservation.train_no or "")
    journeys = getattr(reservation, "journeys", ())
    return str(journeys[0].train_no or "") if journeys else ""


def _payment_deadline(reservation):
    date_value = str(getattr(reservation, "payment_deadline_date", "") or "")
    time_value = str(getattr(reservation, "payment_deadline_time", "") or "")
    if len(time_value) == 4:
        time_value += "00"
    return datetime.strptime(date_value + time_value, "%Y%m%d%H%M%S")


def _deadline_text(reservation):
    date_value = str(getattr(reservation, "payment_deadline_date", "") or "")
    time_value = str(getattr(reservation, "payment_deadline_time", "") or "")
    return f"{date_value} {time_value}".strip()


def _safe_error(error, config):
    message = str(error)
    for key in ("srtPassword", "cardNumber", "cardPassword", "cardExpire", "cardValidation"):
        secret = str(config.get(key, "")).strip()
        if secret:
            message = message.replace(secret, "[redacted]")
    return message[:180]


def _is_terminal_error(error):
    return isinstance(error, _TERMINAL_ERRORS)


def _is_inventory_error(error):
    return isinstance(error, _INVENTORY_ERRORS)


def _is_ambiguous_mutation_error(error):
    return isinstance(error, (KorailTransportError, KorailProtocolError))


def _validate_config(config):
    if config.get("operator") != "KORAIL":
        raise ValueError("KORAIL+ engine received a non-KORAIL profile")
    required = (
        "srtId", "srtPassword", "dep", "arr", "date", "timeFrom", "timeTo",
        "depCode", "arrCode", "deviceId", "osVersion", "deviceModel",
        "deviceWidth", "deviceHeight", "androidSdkInt",
    )
    if any(not str(config.get(key, "")).strip() for key in required):
        raise ValueError("필수 KORAIL+ 조건이 비어 있어")
    if not re.fullmatch(r"[0-9a-f]{16}", str(config["deviceId"]).lower()):
        raise ValueError("Android 기기 식별자를 확인하지 못했어")
    for key, pattern in (("date", r"[0-9]{8}"), ("timeFrom", r"[0-9]{6}"), ("timeTo", r"[0-9]{6}")):
        if not re.fullmatch(pattern, str(config[key])):
            raise ValueError("날짜 또는 시간 형식이 잘못됐어")
    if config["timeFrom"] > config["timeTo"]:
        raise ValueError("종료 시각은 시작 시각 이후여야 해")
    for key in ("dep", "arr"):
        if not re.fullmatch(r"[가-힣A-Za-z0-9()·\-\s]{1,30}", str(config[key]).strip()):
            raise ValueError("역 이름에 허용되지 않는 문자가 있어")
    if config["dep"] == config["arr"]:
        raise ValueError("출발역과 도착역은 달라야 해")
    if any(not re.fullmatch(r"[0-9]{4}", str(config[key])) for key in ("depCode", "arrCode")):
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
        raise ValueError("KORAIL+ 자동결제는 0.2.0에서 비활성화됐어")

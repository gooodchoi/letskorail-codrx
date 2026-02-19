import json
import time
from threading import Lock
from typing import Optional, Tuple

from letskorail import Korail


_SESSION_LOCK = Lock()
_SESSION_KORAIL: Optional[Korail] = None
_SESSION_AUTH: Optional[Tuple[str, str]] = None


def _create_korail(user_id: str, password: str) -> Korail:
    """호출 환경에서 사용 중인 Korail 생성자 시그니처를 모두 지원한다."""
    try:
        korail = Korail(user_id, password, auto_login=False)
    except TypeError:
        korail = Korail()
        korail.login(user_id, password)
    return korail


def _get_korail(user_id: str, password: str, force_refresh: bool = False) -> Korail:
    """로그인 성공 세션을 재사용하고, 필요할 때만 재로그인한다."""
    global _SESSION_KORAIL, _SESSION_AUTH

    with _SESSION_LOCK:
        same_user = _SESSION_AUTH == (user_id, password)
        if not force_refresh and same_user and _SESSION_KORAIL and getattr(_SESSION_KORAIL, "logined", False):
            return _SESSION_KORAIL

        korail = _create_korail(user_id, password)
        if not getattr(korail, "logined", False):
            korail.login(user_id, password)

        _SESSION_KORAIL = korail
        _SESSION_AUTH = (user_id, password)
        return korail


def _seat_to_text(seat):
    psg = seat.psg_sub_type if seat.psg_sub_type else seat.psg_type
    return f"{seat.car_no}호차 {seat.seat_no}({psg})"


def _reserve_with_session(korail: Korail, dpt: str, arv: str, date: str, min_time: str, max_time: str) -> str:
    trains = korail.search_train(dpt, arv, date, min_time)

    if not trains:
        return json.dumps({"success": False, "message": "조회된 열차가 없습니다."}, ensure_ascii=False)

    for train in trains:
        if train.dpt_time <= max_time:
            reservation = korail.reserve(train)
            train_infos = []
            for sq in sorted(reservation.train_info.keys(), key=lambda x: int(x)):
                info = reservation.train_info[sq]
                tr = info["train"]
                seats = info.get("seats", ())
                train_infos.append(
                    {
                        "train_name": tr.train_name,
                        "train_no": tr.train_no,
                        "departure": f"{tr.dpt_name} {tr.dpt_time[:2]}:{tr.dpt_time[2:4]}",
                        "arrival": f"{tr.arv_name} {tr.arv_time[:2]}:{tr.arv_time[2:4]}",
                        "seats": [_seat_to_text(s) for s in seats],
                    }
                )

            return json.dumps(
                {
                    "success": True,
                    "message": "예약 성공",
                    "reserved_at_epoch_ms": int(time.time() * 1000),
                    "payment_timeout_sec": 10 * 60,
                    "reservation_no": reservation.rsv_no,
                    "price": reservation.total_price,
                    "deadline": reservation._str_deadline(),
                    "details": train_infos,
                },
                ensure_ascii=False,
            )

    return json.dumps(
        {
            "success": False,
            "message": "최대 출발 시간 이내 열차가 없습니다.",
        },
        ensure_ascii=False,
    )


def login(user_id: str, password: str) -> str:
    """안드로이드에서 호출하는 로그인 테스트 함수."""
    try:
        korail = _get_korail(user_id, password, force_refresh=True)
        if korail.logined:
            return f"로그인 성공: {user_id}"
        return "로그인 실패"
    except Exception as e:
        return f"로그인 오류: {e}"


def reserve_once(
    user_id: str,
    password: str,
    dpt: str,
    arv: str,
    date: str,
    min_time: str,
    max_time: str,
) -> str:
    """조회 1회 + 즉시 예매 1회 시도 결과를 JSON 문자열로 반환한다."""
    try:
        korail = _get_korail(user_id, password)
        return _reserve_with_session(korail, dpt, arv, date, min_time, max_time)
    except Exception:
        # 세션 만료 등으로 실패하면 1회 재로그인 후 재시도한다.
        try:
            korail = _get_korail(user_id, password, force_refresh=True)
            return _reserve_with_session(korail, dpt, arv, date, min_time, max_time)
        except Exception as e:
            return json.dumps({"success": False, "message": f"예약 오류: {e}"}, ensure_ascii=False)

import json
import time

from letskorail import Korail


def login(user_id: str, password: str) -> str:
    """안드로이드에서 호출하는 로그인 테스트 함수."""
    try:
        korail = Korail(user_id, password, auto_login=False)
        if korail.logined:
            return f"로그인 성공: {user_id}"
        return "로그인 실패"
    except Exception as e:
        return f"로그인 오류: {e}"


def _seat_to_text(seat):
    psg = seat.psg_sub_type if seat.psg_sub_type else seat.psg_type
    return f"{seat.car_no}호차 {seat.seat_no}({psg})"


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
        korail = Korail(user_id, password, auto_login=False)
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
    except Exception as e:
        return json.dumps({"success": False, "message": f"예약 오류: {e}"}, ensure_ascii=False)

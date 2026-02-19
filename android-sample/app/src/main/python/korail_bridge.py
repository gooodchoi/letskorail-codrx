import json

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
                return json.dumps(
                    {
                        "success": True,
                        "message": f"예약 성공: {reservation}",
                        "train": f"{train.train_name} {train.train_no} ({train.dpt_time})",
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

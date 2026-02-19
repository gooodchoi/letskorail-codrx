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


def reserve(user_id: str, password: str) -> str:
    """안드로이드에서 호출하는 예매 테스트 함수.

    실제 예매 전에 search_train 결과를 확인한 후 사용하세요.
    """
    try:
        korail = Korail(user_id, password, auto_login=False)

        # 예시: 서울 -> 부산, 성인 1명, 가장 첫 번째 열차 예약 시도
        trains = korail.search_train("서울", "부산", passengers=[1])
        if not trains:
            return "예약 가능한 열차가 없습니다."

        reservation = korail.reserve(trains[0])
        return f"예약 성공: {reservation}"
    except Exception as e:
        return f"예약 오류: {e}"

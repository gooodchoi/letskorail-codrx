# Android + Chaquopy 샘플

이 예제는 서버/API 없이 **Kotlin -> Python 직접 호출** 구조를 보여줍니다.

## 구조

- `app/build.gradle`: Chaquopy 설정
- `app/src/main/java/com/example/letskorail/MainActivity.kt`: Kotlin에서 Python 함수 호출
- `app/src/main/python/korail_bridge.py`: Kotlin이 호출할 파이썬 진입점
- `app/src/main/python/letskorail/*`: 기존 letskorail 코드 복사본

## 동작

1. 로그인 버튼: `korail_bridge.login(id, pw)` 호출
2. 예매 버튼: `korail_bridge.reserve(id, pw)` 호출
3. 결과 문자열을 `TextView`에 표시

## 주의

- 실제 결제/예매 API 호출이 발생할 수 있으므로 테스트 계정으로 확인하세요.
- `buildPython` 경로는 개발 환경에 맞춰 수정해야 합니다.

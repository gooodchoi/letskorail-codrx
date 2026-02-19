# Android + Chaquopy 샘플

이 예제는 서버/API 없이 **Kotlin -> Python 직접 호출** 구조를 보여줍니다.

## 구조

- `settings.gradle.kts`: 플러그인/의존성 저장소 및 모듈 포함 설정
- `app/build.gradle.kts`: Chaquopy 설정
- `app/src/main/java/com/example/letskorail/MainActivity.kt`: Kotlin에서 Python 함수 호출
- `app/src/main/python/korail_bridge.py`: Kotlin이 호출할 파이썬 진입점
- `app/src/main/python/letskorail/*`: 기존 letskorail 코드 복사본

## 동작

1. 로그인 버튼: `korail_bridge.login(id, pw)` 호출
2. 예매 시작: `korail_bridge.reserve_once(...)`를 반복 호출
3. 결과 문자열(JSON)을 파싱해 진행 상태 표시

## 빌드 시 주의사항

- Android Gradle Plugin 8.5.1 환경에서는 **Gradle 실행 JVM을 Java 17**으로 맞추는 것을 권장합니다.
- Java 25로 실행하면 `Unsupported class file major version 69` 오류가 발생할 수 있습니다.

예시:

```bash
cd android-sample
JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 PATH=$JAVA_HOME/bin:$PATH gradle :app:assembleDebug
```

## 기타 주의

- 실제 결제/예매 API 호출이 발생할 수 있으므로 테스트 계정으로 확인하세요.
- `buildPython` 경로는 개발 환경에 맞춰 수정해야 합니다.

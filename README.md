# 제시간 (가칭)

**내비는 건물 앞까지, 제시간은 약속한 문 앞까지 제시간에.**
내비 도착 시간에 차가 직접 잰 주차 소요시간과 도보 시간을 더해 **문 앞 도착 시각**을 알려주고, 늦을 것 같으면 더 빨리 도착하는 주차장으로 바꿔 안내하는 Pleos Connect 차량용 앱입니다.

```
문 앞 도착 시각 = 주차장까지 운전 + 그 주차장에서의 주차 소요시간(내 기록) + 문까지 도보
```

## APK

- 위치: [`apk/jesigan_release.apk`](apk/jesigan_release.apk) (release 서명)
- 패키지: `app.jesigan` · versionName 0.1.0 · minSdk 28 · targetSdk 35

## 사용한 Pleos Connect SDK

| SDK | 사용 API | 용도 |
|---|---|---|
| Vehicle SDK 2.3.2 · Gear | `getGear()`, `registerGearSelection`, `getCurrentGearSelectionState` | 기어 P = 주차 완료 → 주차 소요시간 측정 종료 |
| NaviHelper SDK 3.0.5 | `initialize`, `getDestinationInfo`/`onDestinationInfo`, `getCurrentLocationInfo`, `addWaypoint`/`removeWaypoint`, `onWayPointArrived`, `onReRouteEnded` | 목적지·남은 시간, 목적지 반경 500m 진입(측정 시작), 주차장 경유지 설정 |

- 주차 소요시간 측정: 목적지 반경 500m 진입 → 기어 P. 장소·요일·시간대·주차장별로 최근 5회 저장 (`ParkingHistory`).
- 문 앞 도착 시각 계산과 추천은 `TripEngine`(순수 Kotlin, 결정적)에서 하며 단위 테스트가 있습니다.
- 실시간 데이터: 서울 열린데이터광장 `GetParkingInfo` (서울시 시영주차장 실시간 주차대수). 키가 없으면 `sample` 키(최대 5건)로 조회합니다.
- 음성: 현재 기기 내장 TextToSpeech로 안내합니다. Pleos TextToSpeech · SpeechToText SDK는 Pleos Playground 클라이언트 ID/시크릿 발급 후 연동합니다. 마이크 음성을 외부 음성 인식 서비스로 보내지 않습니다.

## 실행 방법

1. Android Studio SDK Manager에 Pleos Connect System Image 사이트를 추가하고 **Pleos Connect v2.1.0 (API 34)** 이미지를 설치합니다. ([설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator))
2. Device Manager에서 Automotive → **Pleos Connect v2.1.0** AVD를 만들고 실행합니다 (Cold boot, RAM 4GB 권장).
3. APK 설치:
   ```bash
   adb install -r apk/jesigan_release.apk
   ```
4. 런처에서 **제시간**을 실행합니다.

### 시연 방법

화면 아래 **시연** 막대로 상황을 재현할 수 있습니다.

| 버튼 | 동작 |
|---|---|
| 시나리오 바꾸기 | 병원 진료(예시 데이터) ↔ 광화문 공연(세종로·종묘 공영주차장 실시간 데이터) |
| +3분 주행 | 시간 경과, 남은 시간 감소, 재계산 |
| 주차장 차오름 | 경유 주차장 잔여 4면 → 다른 주차장 제안 |
| 주차장 도착 / +1분 | 반경 진입(측정 시작), 2분 넘게 주차 못 하면 다음 후보 제안 |
| 기어 P / 기어 D | 주차 완료 → 기록 저장 (실제 Vehicle SDK 기어 이벤트와 같은 경로) |
| 다음 주 같은 시간 | 반복 방문지를 시동 시점에 먼저 제안 |

Pleos Connect Virtual Console에서 기어를 P로 바꾸면 시연 버튼 없이도 같은 흐름으로 동작합니다.

## 테스트 환경

- Pleos Connect Emulator **v2.1.0** (Android 14, API 34, x86_64)
- 화면 규격: **2560 × 1440, mdpi(160dpi)**, 가로
- 빌드: JDK 17, Gradle 8.10.2, Android Gradle Plugin 8.8.2, Kotlin 2.0.0, compileSdk 35

## 소스에서 빌드

```bash
./gradlew testDebugUnitTest assembleRelease
```

release 서명에는 루트의 `keystore.properties`(저장소에 포함하지 않음)가 필요합니다. 없으면 서명되지 않은 release APK가 만들어집니다.

## 구조

```
app/src/main/java/app/jesigan/
  TripEngine.kt      문 앞 도착 시각 계산 · 추천 · 제안 규칙 (단위 테스트 대상)
  ParkingHistory.kt  차가 잰 주차 소요시간 기록
  PleosBridge.kt     Vehicle SDK(Gear) · NaviHelper SDK 연결, SDK 호출 기록
  LotRepository.kt   시연 시나리오 + 서울 실시간 주차 데이터
  Voice.kt           음성 안내
  MainActivity.kt    차량 화면 · 상태 흐름
```

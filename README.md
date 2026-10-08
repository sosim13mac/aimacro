# MacroLearn — 한 번 보여주면 매일 실행하는 출석체크 매크로

사용자가 패턴을 **한 번** 시연하면 접근성 서비스가 클릭을 기록하고, 날짜처럼 매일 바뀌는 값은 일반화하여
매일 09:00에 자동 실행한다. 화면 판단(팝업 감지, 예상 밖 화면 대응)은 Hugging Face Space에 호스팅된 **Laya** 서버가 맡는다.

- 패키지: `com.onion.macrolearn` / Kotlin + Jetpack Compose + Room + WorkManager + Retrofit
- minSdk 26, targetSdk 34

## 구조

| 파일 | 역할 |
|---|---|
| `data/MacroStep.kt`, `Macro.kt` | 스텝 모델, Room Entity(steps는 JSON 저장) |
| `service/MacroRecorderService.kt` | 접근성 서비스: 클릭/윈도우 전환 이벤트 캡처, 플로팅 STOP, 제스처 탭 |
| `service/NodeFinder.kt` | 노드 탐색: **text → viewId → bounds(좌표)** 3단계 fallback, `dynamic:today` |
| `service/MacroRunner.kt`, `MacroRunnerService.kt` | 실행 엔진(팝업 감지 → 탐색 → 클릭 → 실패 시 Laya 질의 → 3회 실패 시 알림) / 포그라운드 서비스 |
| `ai/LayaClient.kt` | Retrofit 클라이언트 `decide(state, questions)`, 확률 0.8 이상일 때만 반영 |
| `ai/LayaGeneralizer.kt` | 녹화 스텝을 일반화 (`text="8"` + 동적 체크 → `dynamic:today`) |
| `ui/RecordScreen.kt`, `MacroListScreen.kt` | 기록 / 목록·실행·09:00 예약 |
| `worker/DailyMacroWorker.kt`, `MacroScheduler.kt` | WorkManager `PeriodicWorkRequest` (24h, 다음 09:00 기준 초기 지연) |

## 1. Laya 서버 배포 (Hugging Face Spaces)

1. <https://huggingface.co/new-space> → **Space name: `laya-macro-brain`**, **SDK: Docker**, Blank 템플릿.
2. 이 저장소의 `huggingface-space/` 내용(`Dockerfile`, `README.md`)을 Space 저장소에 push.
   ```bash
   git clone https://huggingface.co/spaces/<사용자명>/laya-macro-brain
   cp huggingface-space/* laya-macro-brain/ && cd laya-macro-brain
   git add . && git commit -m "Laya server" && git push
   ```
3. 빌드가 끝나면 URL은 `https://<사용자명>-laya-macro-brain.hf.space` 이다.
4. 동작 확인:
   ```bash
   curl -X POST https://<사용자명>-laya-macro-brain.hf.space/v1/systemone \
     -H 'Content-Type: application/json' \
     -d '{"state":"오늘의 이벤트 팝업\n닫기","questions":{"has_popup":{"type":"noul","instructions":"닫아야 할 팝업이 있는가?"}}}'
   # → {"answers":{"has_popup":{"noul":0.94}}}
   ```
   무료 Space는 유휴 시 잠들기 때문에 첫 호출이 느릴 수 있다(클라이언트 타임아웃 60초). Private Space로 바꿀 경우 인증 헤더 추가가 필요하다.

### 앱에 URL 연결 (환경변수로 관리)
우선순위: **환경변수 `LAYA_BASE_URL`** > `local.properties` > `gradle.properties`

```bash
LAYA_BASE_URL=https://<사용자명>-laya-macro-brain.hf.space ./gradlew assembleDebug
# 또는 local.properties 에: LAYA_BASE_URL=https://<사용자명>-laya-macro-brain.hf.space
```

> **API 가정**: 이 프로젝트는 `noul` 질문(응답 `{"noul": 확률}`)과, 선택 질문을 `{"type":"choice","instructions":...,"options":[...]}` →
> `{"answers":{id:{옵션: 확률}}}` 형태로 가정한다. 서버 스펙이 다르면 `ai/LayaClient.kt` 의 `LayaQuestion`/`LayaResponse` 만 수정하면 된다.
> Laya 호출이 실패하면 안전 측(아무것도 하지 않음)으로 동작한다.

## 2. 빌드

Android Studio (Hedgehog 이상, JDK 17)에서 열고 Run, 또는:
```bash
./gradlew testDebugUnitTest assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```
GitHub Actions(`.github/workflows/build.yml`)도 같은 명령으로 APK를 빌드한다.

## 3. 권한 설정

1. **접근성 서비스** — 앱의 `기록` 탭 → "설정 열기" 또는 `설정 → 접근성 → 설치된 앱 → MacroLearn 매크로` 를 켠다.
   - Android 13+에서 사이드로드 앱은 "제한된 설정" 때문에 토글이 회색일 수 있다:
     `설정 → 앱 → MacroLearn → ⋮ → 제한된 설정 허용` 후 다시 켠다.
2. **다른 앱 위에 표시** — 플로팅 STOP 버튼용. 앱의 "설정 열기"에서 허용.
   (백그라운드에서 포그라운드 서비스를 시작할 수 있게 해주는 역할도 한다.)
3. **알림** — Android 13+ 첫 실행 시 허용(실패 보고용).
4. 제조사별 배터리 최적화(삼성/샤오미 등)에서 앱을 "제한 없음"으로 두어야 09:00 예약이 정확히 실행된다.
   WorkManager 주기 작업이라 수 분 정도 지연될 수 있다.

## 4. 사용법

1. `기록` 탭 → **기록 시작** → 대상 앱으로 이동해 평소대로 한 번 클릭 → 플로팅 **STOP**.
2. "동적 값이 있나요?" 다이얼로그에서 날짜 같은 스텝을 체크(1~31 숫자는 자동 체크)하고 이름 입력 → 저장.
3. `매크로` 탭에서 **지금 실행** 또는 **매일 09:00 자동 실행** 스위치.
   대상 앱은 녹화 시 자동 기록되어 실행 때 먼저 실행된다.

## 5. 샘플 매크로로 동작 확인

`매크로` 탭 → **샘플 추가** → "샘플: 출석체크"
(`전체메뉴 → 닫기(팝업) → 출석 → 오늘 날짜`, 정의: `data/SampleMacro.kt`).

1. 샘플의 `text` 값("전체메뉴", "출석" 등)을 실제 앱 문구에 맞게 고치거나, 직접 한 번 녹화한다.
2. 대상 앱을 켜 둔 상태(샘플은 `targetPackage` 없음)에서 **지금 실행**.
3. 기대 동작: 전체메뉴 클릭 → 팝업이 있으면 Laya가 감지(≥0.8)해 닫기 → 출석 메뉴 클릭 →
   `LocalDate.now().dayOfMonth` 와 정확히 일치하는 날짜 칸 클릭.
4. 못 찾으면 Laya에 대안 행동을 묻고 최대 3회 재시도, 이후 "매크로 실패" 알림.

## 한계 / 주의

- 자동 로그인이 필요한 앱, 보안 화면(FLAG_SECURE), 일부 게임 엔진 화면은 접근성 노드가 노출되지 않아 좌표(bounds) fallback에만 의존한다.
- 화면 텍스트가 Laya 서버로 전송된다. 개인정보가 표시되는 앱에는 사용하지 말 것.
- 대상 앱의 약관이 자동화를 금지하는지 확인하고 사용할 것.
- 이 환경에서는 Android SDK를 받을 수 없어 로컬 빌드는 검증하지 못했다. 첫 빌드 오류는 CI 로그 또는 Android Studio에서 확인 바란다.

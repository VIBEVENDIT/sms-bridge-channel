# 호텔 앳 강남 · Android SMS bridge

호텔 업무폰의 SMS를 `#lounge-호텔앳강남`과 양방향으로 연결하는 Android 앱이다.
PC Phone Link, Windows `Setup.exe`, 상시 켜 둔 PC는 사용하지 않는다.

> **Windows 경로 폐기:** 기존 `manifest.json`, `commands.json`, 과거
> `sms-bridge-install.zip`은 설치되어 있을 수 있는 구버전의 설명용 legacy feed일
> 뿐이다. 새 설치·업데이트·운영에 사용하지 않는다.

## 지원 범위

- Android 8.0(API 26)+, SMS 가능한 SIM 장착 폰
- SMS 수신 → 고정 채널 `#lounge-호텔앳강남` (`C0AN0CDAADC`)
- 수신 Slack thread 답장 → 원 발신번호로 SMS
- `!sms <전화번호> <내용>` → 새 SMS 발신
- FAQ/질문 자동회신과 OTP·광고·noreply·단순 확인 스킵
- SQLite durable queue, Android/Slack event idempotency, cooldown/rate/loop guard
- Android Keystore에 Slack token 암호화 저장

iOS는 서드파티 앱이 일반 SMS를 수신·발신 자동화할 수 있는 공개 API를 제공하지
않으므로 지원하지 않는다.

## 동작 구조

```text
외부 SMS
  → Android SMS_RECEIVED
  → 로컬 SQLite 큐(중복 key)
  → Slack Web API  *[문자수신]*
  → 정책 판정/Android SmsManager 자동회신

#lounge message event
  → Slack Socket Mode
  → 로컬 SQLite 큐에 commit한 뒤 envelope ACK
  → thread 번호 매핑 또는 !sms parser
  → Android SmsManager
  → 모든 multipart sent callback 성공
  → Slack thread :완료:
```

서버·webhook·PC가 없다. 앱은 `remoteMessaging` foreground service로 실행되고
Socket Mode를 사용하므로 공개 inbound URL도 필요 없다.

## Slack 앱 준비

1. <https://api.slack.com/apps> → **Create New App** → **From an app manifest**에서
   `slack-app-manifest.yaml`을 사용한다.
2. 앱을 workspace에 설치해 `xoxb-…` bot token을 발급한다.
3. **Basic Information → App-Level Tokens**에서 `connections:write` scope의
   `xapp-…` token을 발급한다.
4. Slack에서 `/invite @호텔 SMS 브리지`로 앱을
   `#lounge-호텔앳강남`에 초대한다.
5. token은 Android 설정 화면에만 입력한다. 저장소, PR, Slack 메시지, 로그에
   붙여 넣지 않는다.

필요 bot scope와 event subscription은 manifest에 선언되어 있다:
`chat:write`, `channels:history`, `groups:history`, `message.channels`,
`message.groups`.

## 철수 폰 설치

1. PR의 green **Android bridge CI** artifact 또는 main CI artifact에서
   `hotel-sms-bridge-debug-<SHA>`를 내려받는다. 로컬 빌드는 아래와 같다.

   ```bash
   ./scripts/qa.sh
   ```

2. 폰에서 이 출처의 앱 설치를 허용하고 `app-debug.apk`를 연다. USB 설치는:

   ```bash
   ./scripts/prepare-phone.sh app/build/outputs/apk/debug/app-debug.apk
   ```

3. 앱에서 SMS 수신, SMS 발신, 알림 권한을 허용한다.
4. Android 설정에서 배터리를 **제한 없음**, 절전 앱 제외로 바꾼다.
5. 폰 앱에 `xoxb-…`, `xapp-…` token을 입력한다. 채널은
   `#lounge-호텔앳강남`으로 고정되어 변경할 수 없다.
6. 자동회신은 처음에는 끈 채 수신/발신을 확인하고, 문구·cooldown·시간당 한도와
   Slack user allowlist를 검토한 뒤 켠다.
7. **저장하고 브리지 시작**을 누르고 상시 알림의 `연결됨`과 대기 0건을 확인한다.
8. `docs/PHONE_E2E_CHECKLIST.md`의 실제 두 폰 A–F 시험을 모두 통과시킨다.

CI debug APK는 설치 시험용이다. 운영 배포는 고정된 별도 signing key로 서명해야
업데이트 시 앱 데이터와 Keystore token을 유지할 수 있다. signing key는 git에 넣지
않는다.

## 자동회신

정책과 근거는 `docs/AUTO_REPLY_POLICY.md`에 있다. 핵심 원칙:

- 정적 호텔 FAQ만 확정 답변
- 일반 질문은 설정된 안전 접수 문구만 사용
- 예약 변경·취소, 돈, 객실 배정, 불만, 안전, 분실은 사람 확인
- OTP, 광고, 자동발신/noreply, 수신거부, 감사/확인은 회신하지 않고
  `*[문자수신]* :완료:`로 triage
- Slack 게시가 성공하기 전에는 자동 SMS를 보내지 않음

## 개발·검증

요구 도구: JDK 17, Android SDK platform 36/build-tools 36.0.0.

```bash
./scripts/qa.sh
```

이 명령은 credential pattern scan, JVM unit/integration test, Android lint,
debug APK build를 수행한다. 실제 SIM·권한·통신사 발신은 자동화할 수 없으므로
`docs/PHONE_E2E_CHECKLIST.md`가 release의 강제 human gate다.

## 배포 규칙

다음 조건 전에는 merge하거나 GitHub Release/APK를 운영폰에 배포하지 않는다.

1. PR CI green
2. 동일 SHA APK로 실제 두 폰 E2E A–F 전부 PASS
3. 누락·중복 0건
4. 운영 signing key 서명 및 APK SHA-256 기록

현재 저장소에는 서버/Vercel 배포 대상이 없다. 배포 단위는 검증·서명된 Android
APK다. legacy raw URL은 200을 유지할 수 있지만 Android 배포 성공을 의미하지 않는다.

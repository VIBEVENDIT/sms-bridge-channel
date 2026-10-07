# 호텔 폰 설치 — Android SMS 브리지 (철수님용)

호텔 번호 SIM이 꽂힌 **Android 폰 1대**에 앱을 설치하면, 그 폰으로 오는 문자가 Slack `#lounge-호텔앳강남`에 「앳강남 막내」로 올라오고, lounge 스레드에서 `>> 내용` 으로 답하면 그 폰이 문자를 보냅니다. PC / Phone Link / Setup.exe 는 더 이상 필요 없습니다.

설계: [`DESIGN.md`](./DESIGN.md)

## 준비

- Android 8 이상, 호텔 번호 SIM, 항상 충전 + Wi-Fi 또는 LTE.
- iPhone 은 **지원 불가** (Apple이 앱의 문자 읽기/자동 발송을 막음). 호텔 폰이 iPhone이면 SIM을 Android 단말로 옮겨야 합니다.
- 서버(Vercel `hotel-at-gangnam-maknae`) 배포가 끝나 있어야 4단계가 됩니다. URL은 구현 PR에 적혀 있습니다.

## 1. 앱 설치

둘 중 하나:

- Google Play: **SMS Gateway for Android™** (개발자 capcom6, 패키지 `me.capcom.smsgateway`)
- 또는 APK: https://github.com/capcom6/android-sms-gateway/releases 에서 최신 `app-release.apk` (구현 PR에 검증한 버전 고정)

기본 문자앱은 바꾸지 않습니다. 삼성 메시지는 그대로 씁니다.

## 2. 권한 · 절전 (폰에서 직접 — 사람 단계)

1. 앱 첫 실행 → **SMS 보내기/받기**, **전화(번호 읽기)**, **알림** 권한 모두 허용.
2. 설정 → 애플리케이션 → SMS Gateway → 배터리 → **제한 없음**.
3. 삼성: 설정 → 배터리 → 백그라운드 사용 제한 → **사용 안 하는 앱을 절전 상태로 전환 끔**, 그리고 SMS Gateway를 **절전 예외 앱**에 추가.
4. 앱 설정에서 부팅 시 자동 시작(Autostart)이 있으면 켬.

## 3. Cloud 모드 켜기 · 키 확인

1. 앱 홈 → **Cloud server** 켜기 → 상태가 online 이 될 때까지 대기.
2. 홈 화면에 나오는 **Username / Password** 확인.
3. 설정 → Webhooks → **Signing Key** 확인.
4. 설정 → Ping → 켜고 간격 **15분**.

이 세 값은 **Slack·카톡·레포에 붙여넣지 말고** Vercel → `hotel-at-gangnam-maknae` → Settings → Environment Variables 에 바로 입력:

| Vercel env | 값 |
| --- | --- |
| `SMS_GATE_USERNAME` | 앱 Username |
| `SMS_GATE_PASSWORD` | 앱 Password |
| `SMS_GATE_WEBHOOK_SIGNING_KEY` | 앱 Signing Key |
| `HOTEL_SMS_NUMBER` | 호텔 폰 번호 (예 `01012345678`) |

저장 후 Vercel 에서 Redeploy.

## 4. 웹훅 등록 (PC/노트북 터미널 1회)

maknae 레포에서:

```bash
SMS_GATE_USERNAME=... SMS_GATE_PASSWORD=... APP_BASE_URL=https://<배포 URL> \
  node scripts/sms-gate-register-webhooks.mjs
node scripts/sms-gate-register-webhooks.mjs --list   # 5개 보이면 OK
```

폰에 "webhook registered" 알림이 뜨는 것이 정상입니다. 앱 설정 → Webhooks → Registered webhooks 에서도 확인할 수 있습니다.

## 5. 스모크 테스트

1. 개인 폰 → 호텔 번호로 "테스트" 문자.
2. lounge 에 `문자|010-xxxx-xxxx|테스트` 가 뜨고, 스레드에 `🤖 [DRY RUN] 자동회신 예정: …` 이 붙으면 수신 OK.
3. 그 스레드에 `>> 답장 테스트` 입력 → 개인 폰에 문자 도착, Slack 메시지에 📤 → ✅ 표시.
4. 접두어 없이 스레드에 쓴 글은 **발송되지 않습니다** (내부 메모).

## 6. 자동회신 켜기

스모크가 끝나면 Vercel env `SMS_AUTO_REPLY_MODE=on` → Redeploy. 같은 번호에는 24시간에 1번만, 하루 최대 50건, 광고/인증번호/대표번호/직원이 12시간 내 답한 번호에는 보내지 않습니다. 끄려면 `off`.

## 7. PC 브리지 정리

폰 경로가 확인되면 프런트 PC 에서 기존 브리지를 제거 (중복 게시 방지):
`%LocalAppData%\HotelAtGangnamSmsBridge\uninstall.ps1` 우클릭 → PowerShell에서 실행 → 시작프로그램/예약작업 삭제됨.

## 운영 규칙 · 한계

- 직원 답장은 **Slack 에서 `>>`** 로. 폰의 삼성 메시지로 직접 보낸 문자는 Slack에 안 남습니다.
- 폰이 꺼지거나 오프라인이면 수신 웹훅은 폰이 최대 약 2일간 재시도하고, Slack→문자는 클라우드에 대기하다 폰이 온라인이 되면 발송됩니다 (대기 초과 시 ❌ 표시).
- 앱 업데이트·재부팅 후 홈 화면에서 Cloud online 인지 확인.

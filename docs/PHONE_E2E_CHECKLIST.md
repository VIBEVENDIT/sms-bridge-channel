# 실통 E2E 및 배포 게이트

이 체크리스트의 모든 필수 항목이 PASS가 아니면 merge, GitHub Release, APK 배포를
하지 않는다. 에뮬레이터와 JVM 테스트는 통신사 SMS 권한·SIM·제조사 절전 동작을
증명하지 못한다.

## 대상 빌드

- [ ] PR:
- [ ] commit SHA:
- [ ] APK SHA-256:
- [ ] Android 기종 / OS:
- [ ] 통신사 / SIM:
- [ ] 시험 담당자 / KST 시각:

## 설치·권한

1. CI의 `hotel-sms-bridge-debug-<SHA>` artifact를 받거나
   `./scripts/qa.sh`로 APK를 만든다.
2. USB 디버깅 사용 시 `./scripts/prepare-phone.sh <apk>`를 실행한다.
3. 앱 화면에서 아래를 직접 확인한다.
   - [ ] SMS 수신 허용
   - [ ] SMS 발신 허용
   - [ ] 알림 허용
   - [ ] 배터리 사용 `제한 없음` / 절전 예외
   - [ ] 제조사 “절전 앱/사용하지 않는 앱 권한 제거” 제외
4. `slack-app-manifest.yaml`로 만든 앱의 bot token과
   `connections:write` app token을 폰 화면에만 입력한다.
5. Slack 앱을 `#lounge-호텔앳강남`에 초대한다. 채널 ID가
   `C0AN0CDAADC`인지 확인한다.
6. 발신 가능한 직원만 허용하려면 Slack user ID allowlist를 입력한다.

## 필수 실통 시나리오

각 시험에는 충돌하지 않는 표식(예: `E2E-20261007-01`)을 본문에 넣는다.
외부 시험폰에서 브리지폰으로 실제 SMS를 보내고, 수신 SMS 개수와 Slack 메시지
개수를 직접 센다.

### A. 수신 → Slack → FAQ 자동회신

- [ ] 자동회신 ON
- [ ] 외부폰 발신: `<표식> 체크인 전에 짐 보관 가능한가요?`
- [ ] lounge root가 정확히 **1개**
- [ ] root 첫 줄이 `*[문자수신]*`
- [ ] 발신번호·수신일시·원문·`짐보관 FAQ` 분류가 정확함
- [ ] 외부폰이 FAQ SMS를 정확히 **1개** 수신
- [ ] thread에 `:완료: 자동회신 SMS`가 정확히 **1개**

### B. Slack thread 답장 → SMS

- [ ] A의 root thread에 `<표식> 사람이 보내는 추가 답변` 작성
- [ ] 외부폰이 동일 본문을 정확히 **1개** 수신
- [ ] 같은 thread에 `:완료: SMS`가 정확히 **1개**
- [ ] Slack Socket 재연결 후에도 중복 SMS가 오지 않음

### C. 새 번호 발신

- [ ] lounge에 `!sms <외부폰번호> <표식> 새 발신 시험` 작성
- [ ] 외부폰이 정확히 **1개** 수신
- [ ] command thread에 `:완료: SMS`가 정확히 **1개**

### D. 회신 제외·사람 확인

- [ ] `<표식> 인증번호 381992` → Slack `:완료:` 1개, SMS 회신 0개
- [ ] `<표식> (광고) 특가 무료수신거부 080-...` → Slack `:완료:` 1개, SMS 회신 0개
- [ ] `<표식> 자동 발송된 문자입니다. 회신 불가` → Slack `:완료:` 1개, SMS 회신 0개
- [ ] `<표식> 감사합니다` → Slack `:완료:` 1개, SMS 회신 0개
- [ ] `<표식> 예약 취소하고 환불 가능한가요?` → `담당자 확인 필요`, SMS 회신 0개

### E. 오프라인 큐·재부팅

- [ ] 브리지폰의 Wi-Fi와 모바일 **데이터만** 끄고 SMS 수신은 유지
- [ ] 외부폰에서 FAQ 질문 SMS 발신
- [ ] 데이터가 꺼진 동안 자동회신이 먼저 발송되지 않음
- [ ] 데이터를 켜면 lounge root 1개 → 자동회신 SMS 1개 순서로 처리
- [ ] 앱 강제종료가 아닌 정상 재부팅 후 foreground 알림과 Socket 연결이 복구됨
- [ ] 재부팅 전후 큐 이벤트가 누락·중복되지 않음

### F. 제한·실패 가시성

- [ ] SMS 발신 권한을 끄면 발신 실패가 thread에 표시되고 성공 `:완료:`는 없음
- [ ] 시간당 한도를 낮춰 초과하면 추가 발신이 차단됨
- [ ] allowlist 밖 Slack 사용자의 `!sms`가 발신되지 않음
- [ ] 차단번호의 thread/command 발신이 거절됨

## 자동 증거

PR CI에서 아래가 모두 green이어야 한다.

- `BridgeProcessorIntegrationTest`: 수신→Slack→자동회신→thread 발신 및 중복 억제
- `HotelReplyPolicyTest`: FAQ, OTP, 광고, noreply, 단순 확인, 민감 문의
- parser/전화번호/loop guard 단위 테스트
- Android lint
- debug APK assemble
- credential-shaped 문자열 scan

## Release 승인

- [ ] 위 A–F 모두 PASS
- [ ] CI green
- [ ] 실패/중복/누락 0건
- [ ] 운영용 고정 signing key로 release APK 서명
- [ ] signing key와 Slack token은 GitHub 저장소에 없음
- [ ] 담당자가 APK SHA-256과 실통 결과를 PR에 기록

하나라도 비어 있으면 상태는 **NOT DEPLOYED — REAL PHONE GATE BLOCKED**이다.

# 수용기준 · 실폰 QA · 배포 게이트

판정자: Claude (개발팀장). **한 항목이라도 미달이면 머지 거절 / go-live 금지.** "부분 통과", "나중에 수정"은 미달로 본다.

## 1. 수용기준 (AC) — 자동 테스트

테스트 이름은 `AC-xx` 접두어. 구현 PR 설명에 표로 PASS/FAIL 기재.

### 폰 앱 (`android/`, JVM unit + Robolectric/instrumented)

| ID | 기준 |
| --- | --- |
| AC-A1 | provider sweep: inbox/sent SMS·MMS 신규 행만 pending 생성, 같은 `_id` 재스캔 시 0건 추가 |
| AC-A2 | 업로드 실패(네트워크/5xx) 시 pending 유지·백오프, ack된 것만 acked |
| AC-A3 | SMS_RECEIVED는 sweep만 트리거(직접 보고 없음) |
| AC-A4 | 앱 발송분 provider sent 행에 `outboxId` 매칭 |
| AC-A5 | outbox 항목 `sending` 기록 후 send, 크래시 복구 시 재전송 없이 `unknown` 보고 |
| AC-A6 | MMS 텍스트 part 결합 + 첨부 업로드(≤10MB), LMS 장문 전체 보존 |
| AC-A7 | 부팅/앱 업데이트 후 서비스 자동 시작, 워터마크 이후 + 48h 재확인 |
| AC-A8 | heartbeat에 권한·배터리 최적화 제외·충전·counts 포함 |

### 서버 (`npm test`, KV in-memory, Slack/AI mock)

| ID | 기준 |
| --- | --- |
| AC-S1 | 같은 이벤트 2회(동시 포함) → 원장 1, Slack 게시 1 |
| AC-S2 | Slack 게시 실패 → cron 재게시 1회, after와 cron 경합 시에도 1 |
| AC-S3 | counts 불일치 heartbeat → `resync` 응답, 재보고 멱등 |
| AC-S4 | 인증: device token 없음/틀림 → 401, Slack 서명 없음/틀림/5분 초과 → 401, 부작용 0 |
| AC-S5 | 페어링 코드 1회용·48h 만료, 토큰은 해시로만 저장 |
| AC-S6 | 스레딩: 72h 내 같은 번호 → 같은 스레드, 초과 → 새 parent |
| AC-S7 | 포맷: [LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md) §2–§4 각 블록 스냅샷 테스트(문자열 완전 일치), 상태 이모지 태그 옆 1개 |
| AC-S8 | 상태 전이: §1 표의 모든 전이 + reaction `:완료:`/`:loading:` → parent `chat.update` |
| AC-S9 | `>>` 답글 → outbox 1건(접두어 제거), 접두어 없음·봇·편집·subtype·Slack 재시도 → 0 |
| AC-S10 | 앱 발송 provider 행 + outbox 매칭 → 추가 게시 0, `*발송 상태*` 갱신만. 매칭 실패 → `호텔폰 직접` 1건 |
| AC-S11 | 규칙 R1–R8 각각 ≥ 3 fixture(실제 한국 문자 샘플 마스킹, 총 ≥ 30) |
| AC-S12 | LLM 결과 `faq`/`escalate`/`no_reply_needed` 각 경로, 검증 V1–V8 각각 위반 시 강등 |
| AC-S13 | 가드 G1–G9 각각 단독 차단 테스트, G7 동시 2요청 → 발송 1 |
| AC-S14 | stage `qa`: 게시 채널 = QA 채널, 허용 번호 외 발송 0 |
| AC-S15 | 발신 이벤트로 자동회신 호출 0 (spy) |
| AC-S16 | 로그에 토큰·서명키 0, 전화번호는 뒤 4자리만 (로그 캡처 테스트) |
| AC-S17 | 기존 Gmail/채널톡 테스트 회귀 0 |

### 빌드·보안

| ID | 기준 |
| --- | --- |
| AC-B1 | CI green: `npm run lint && npm test && npm run build`, `./gradlew test assembleRelease` |
| AC-B2 | 서명된 release APK + SHA-256, 공개 다운로드 URL(Release asset) |
| AC-B3 | 레포·APK·로그·PR에 시크릿/페어링 코드/실제 게스트 번호 0 (`git grep`, `apkanalyzer` 문자열 검사) |
| AC-B4 | Vercel production 배포, `/api/health` 200, 디바이스·웹훅 라우트 무인증 401 |

## 2. 실폰 E2E QA 매트릭스 (stage `qa`)

준비: 호텔 폰(앱 설치·페어링), 테스트 폰 A·B(`SMS_QA_ALLOWED_NUMBERS`), QA 채널, `SMS_AUTO_REPLY_MODE=on`. 각 케이스 증거 = Slack permalink + 폰 화면 캡처(번호 뒤 4자리 외 가림) + 시각.

| # | 시나리오 | 기대 결과 | 시간 |
| --- | --- | --- | --- |
| Q1 | A → 호텔폰 단문 "감사합니다" | `*[문자수신]*` parent 1건, 필드·포맷 일치, `no_reply_needed` → `:완료:`·발송 0 | ≤ 60s |
| Q2 | A: "오늘 체크인 전에 짐 맡길 수 있나요?" | 자동회신 P6 근거 문자 A 수신, `*[문자발송]* 자동회신 (FAQ)` + `전달확인`, parent `:완료:` | ≤ 2분 |
| Q3 | A: "환불해 주세요" | 보류응답 A 수신, `*[확인 필요]*`, parent `:loading:` | ≤ 2분 |
| Q4 | A: 같은 날 두 번째 에스컬레이션 문의 | 보류응답 추가 발송 0, parent `:loading:` 유지 | — |
| Q5 | A: 영어 "Can I check in at 2am?" | 영어 회신(P8), `*[원문]*` 스레드, `*문의내용*` 한국어 | ≤ 2분 |
| Q6 | A: 장문 300자 이상(LMS) | 전문 보존(잘림 0), 1건 | ≤ 60s |
| Q7 | A: 사진 1장 + 문구(MMS) | parent + 스레드 이미지 1, `[사진 1장]` | ≤ 2분 |
| Q8 | 실제 OTP 1건 수신(아무 서비스 인증번호 요청) | `:loudspeaker:` `*분류* = 인증번호`, 본문 그대로, 발송 0 | ≤ 60s |
| Q9 | A: "(광고) 테스트 무료수신거부 080-000-0000" | `:loudspeaker:` 광고, 발송 0 | — |
| Q10 | A: "[자동응답] 부재중입니다" | 발송 0, `:loudspeaker:` | — |
| Q11 | QA 채널 스레드 `>> 슬랙 답장 테스트` | A 수신, `*[문자발송]* 슬랙 (@이름)`, `:outbox_tray:`→`:white_check_mark:`, parent `:완료:` | ≤ 30s |
| Q12 | 스레드에 접두어 없이 "내부 메모" | 발송 0 | — |
| Q13 | 호텔폰 삼성 메시지로 A에게 직접 발송 | `*[문자발송]* 호텔폰 직접` 1건, 같은 스레드 | ≤ 90s |
| Q14 | Q13 직후 A 답장 | 같은 스레드, G3로 자동회신 0, parent `:loading:` | — |
| Q15 | 호텔폰 비행기 모드 → A가 3건 발송 → 해제 | 3건 순서대로, 각 1건, 누락·중복 0 | 복귀 후 ≤ 3분 |
| Q16 | 앱 강제 종료 → B가 1건 → 앱 재시작 없이 2분 대기, 그 후 재부팅 | 재부팅 후 자동 시작, 1건 게시 | 부팅 후 ≤ 3분 |
| Q17 | 서버에 같은 이벤트 재전송(curl, 토큰) | 게시 추가 0 | — |
| Q18 | B → 호텔폰 (B가 허용 목록에서 제외된 상태) | 게시 1, 발송 0 | — |
| Q19 | A: "STOP" | optout, 이후 A 문의에 자동회신 0 | — |
| Q20 | Samsung 기기 A → 호텔폰 (채팅+ 꺼짐 확인) | SMS로 수신·게시 (RCS로 새지 않음) | ≤ 60s |
| Q21 | `SMS_AUTO_REPLY_MODE=off` 후 A 문의 | 발송 0, parent `:loading:` | — |
| Q22 | 디바이스 오프라인 15분 | lounge/QA 채널 경보 1회 | ≤ 12분 |
| Q23 | 24h 대사: 앱 counts = 서버 counts | 차이 0 | 다음날 |

Shadow 리허설: QA 종료 후 stage `qa` + `SMS_AUTO_REPLY_MODE=shadow`로 **실제 게스트 문자 ≥ 10건 또는 12시간**(먼저 도달) 초안 수집 → 리드가 전수 검토. 정책 위반·사실 오류 0이어야 통과.

## 3. 배포 게이트

| 게이트 | 조건 | 판정 |
| --- | --- | --- |
| G0 | 호텔 폰 = Android 8+ 확인, 채팅+ OFF 가능 | 실패 시 즉시 보고, 이후 진행 중지 |
| G1 | AC-A*, AC-S*, AC-B1–B3 PASS | 리드 코드 리뷰 approve → 머지 |
| G2 | AC-B4 + stage `qa` 배포 + 앱 페어링 | QA 시작 허용 |
| G3 | Q1–Q22 전부 PASS + shadow 리허설 통과 | 리드 PASS 코멘트 |
| G4 | `SMS_BRIDGE_STAGE=live`, `SMS_AUTO_REPLY_MODE=on`, lounge 스모크 1건(Q1+Q2 재현) | go-live |
| G5 | 다음날 Q23 PASS, PC 브리지 제거 확인 | 완료 보고 |

## 4. 판정 템플릿 (PR 코멘트)

```
판정: PASS | FAIL
게이트: G1/G2/G3/G4/G5
미달 항목: (없음 | AC-xx / Qxx — 근거 링크)
증거: CI run, Slack permalinks, 캡처
다음 단계:
```

# 구현 핸드오프 DO — GPT Astra Max (구현·QA)

리드: Claude (개발팀장). 원본 스펙: [DESIGN.md](./DESIGN.md) · [LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md) · [AUTO-REPLY.md](./AUTO-REPLY.md) · 판정 기준: [ACCEPTANCE.md](./ACCEPTANCE.md).

스펙과 다르게 가야 하면 PR 설명에 `Deviation:` + 사유. 조용히 바꾸면 FAIL. 막히면 우회하지 말고 `Blocked:` + 정확한 사람 단계로 보고.

## 레포 / 브랜치 / PR

- 레포 `VIBEVENDIT/hotel-at-gangnam-maknae`(private). 베이스 `cursor/realtime-ops-webhooks-746b`(PR #1) → 새 브랜치 → `main` 대상 PR **1개**(서버 + `android/`). 설명에 "Includes #1".
- PC 브랜치(PR #2–#6)는 쓰지 않는다. 머지 후 리드가 superseded 처리 권고.
- APK 공개 배포: `VIBEVENDIT/sms-bridge-channel` Release `android-v<ver>`에 `atgangnam-sms-bridge-<ver>.apk` + `.sha256`. 권한 없으면 `Blocked:`.

## DO — 서버 (Next.js, PR #1 위)

1. `lib/phone.ts` — KR 번호 정규화/표시/휴대폰 판별.
2. `lib/kv.ts` — Upstash + in-memory 동일 인터페이스(`get/set{nx,ex}/incr/del/lpush/lrange`).
3. `lib/sms-ledger.ts` — 원장·게시 락·counts·resync ([DESIGN.md](./DESIGN.md) §3.2, §4).
4. `lib/sms-format.ts` — [LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md) §2–§4 블록 생성 순수 함수 + 상태 전이 함수. KST 포맷 `2026년 10월 7일 11:02 (KST)`.
5. `lib/slack.ts` 확장 — `thread_ts`, `ts` 반환, `chat.update`, `reactions.add`, 파일 업로드(external upload API). 기존 시그니처 유지.
6. `lib/sms-classify.ts` — 규칙 R1–R8 ([AUTO-REPLY.md](./AUTO-REPLY.md) §2).
7. `lib/hotel-policy.ts` — KB P1–P13, id·문구·출처 URL 그대로.
8. `lib/sms-ai.ts` — AI SDK `generateObject` + AI Gateway(`SMS_AI_MODEL`), 스키마·검증 V1–V8·강등, 15s 타임아웃.
9. `lib/auto-reply.ts` — 가드 G1–G9, 보류응답 템플릿, outbox 생성.
10. `lib/sms-outbox.ts` — outbox 큐·lease·결과 반영·provider 매칭 ([DESIGN.md](./DESIGN.md) §3.3).
11. 라우트: `app/api/sms/device/pair`, `.../events`, `.../attachments`, `.../outbox`(long-poll ≤25s), `.../revoke`(admin), `app/api/cron/sms`(1분: 재게시·오프라인 경보·대사). `vercel.json` cron 등록, `CRON_SECRET` 검증.
12. `app/api/webhooks/slack` 확장 — 서명 필수, `message.channels` `>>`, `reaction_added` `:완료:`/`:loading:`, `event_id` 멱등, 3초 ack + `after()`.
13. `.env.example` — [DESIGN.md](./DESIGN.md) §5 키 이름만.
14. 테스트 — [ACCEPTANCE.md](./ACCEPTANCE.md) AC-S1–S17 전부, 이름 `AC-Sxx …`.

## DO — Android (`android/`)

15. Kotlin, minSdk 26 / target 35, 패키지 `com.vendit.atgangnam.smsbridge`. 권한: `READ_SMS RECEIVE_SMS RECEIVE_MMS SEND_SMS READ_PHONE_STATE RECEIVE_BOOT_COMPLETED POST_NOTIFICATIONS FOREGROUND_SERVICE FOREGROUND_SERVICE_REMOTE_MESSAGING REQUEST_IGNORE_BATTERY_OPTIMIZATIONS INTERNET ACCESS_NETWORK_STATE`. 기본 문자앱 role 요청 금지.
16. Foreground service(`remoteMessaging`) + ContentObserver(`content://sms`, `content://mms`) + 60s sweep + Room 원장 + WorkManager 백업 워커(15분).
17. 업로더(배치 ≤50, 백오프 ≤5분), 첨부 업로더, outbox long-poll + `sendMultipartTextMessage` + sent/delivered intents, `sending→unknown` 복구.
18. 화면 1개: 페어링(서버 URL 기본값 + 코드), 상태(마지막 업로드·heartbeat·권한 체크리스트·배터리 최적화·채팅+ 안내), "지금 동기화" 버튼. 문자 본문은 화면에 표시하지 않음.
19. CI: `./gradlew test assembleRelease`, release 서명 키는 Actions secrets(`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). 없으면 `Blocked:` — 디버그 서명 APK로 QA 금지(업데이트 불가).
20. 테스트 — AC-A1–A8.

## DO — 배포·QA

21. Vercel: team `venditjasons-projects`에 Git 프로젝트 `hotel-at-gangnam-maknae` 생성, Upstash Redis 연결, env 설정(`SMS_BRIDGE_STAGE=qa`, `SMS_AUTO_REPLY_MODE=shadow`). 비밀 값(`SLACK_*`)이 없으면 `Blocked:`.
22. QA 채널 `#qa-sms-bridge`(private) 생성·봇 초대 → `SMS_QA_CHANNEL_ID`.
23. 페어링 코드 생성 → **private 경로로만** 전달(이 공개 레포·PR에 쓰지 않음).
24. [ACCEPTANCE.md](./ACCEPTANCE.md) §2 Q1–Q23 실행 계획을 PR에 체크리스트로. 실폰 조작은 현장 직원/철수님과 진행, 결과·증거를 PR에 누적.
25. G3 리드 PASS 전 `SMS_BRIDGE_STAGE=live` 금지.

## 하지 말 것

- sms-gate.app 등 제3자 SMS 게이트웨이 사용.
- lounge(`C0AN0CDAADC`)에 QA 메시지 게시 (G4 스모크 1건 제외).
- 실제 게스트 번호·본문을 PR·테스트 fixture에 그대로 (마스킹 필수).
- 공개 `manifest.json` 버전 bump, PC `sms-bridge/` 수정.
- 일부만 된 상태로 "완료" 보고.

## 사람 단계 (대신 못 함 — PR에 체크박스)

1. 호텔 폰 OS 확인(G0), 잠금 해제, APK 설치·권한 허용, 배터리 최적화 제외, **채팅+ 끄기**, 상시 충전 — [INSTALL-ANDROID.md](./INSTALL-ANDROID.md).
2. 페어링 코드 입력.
3. Slack 앱 스코프·이벤트 추가 후 재설치(워크스페이스 관리자).
4. GitHub Actions secrets(Android 서명 키) 등록.
5. 테스트 폰 A·B로 QA 조작.
6. go-live 후 프런트 PC에서 PC 브리지 제거.

## 머지 게이트 (리드)

[ACCEPTANCE.md](./ACCEPTANCE.md) §3 G1 조건 + 아래 코드 리뷰 체크:

- [ ] 원장 NX 기록이 Slack 게시·자동회신보다 먼저, 게시 락 존재
- [ ] 앱이 브로드캐스트를 직접 보고하지 않음(provider 단일 원천)
- [ ] outbox `sending` 기록 후 send, 재전송 경로 없음
- [ ] 자동회신 경로가 발신 이벤트에서 호출되지 않음
- [ ] LLM 출력이 검증 V1–V8 없이 발송되는 경로 없음
- [ ] 포맷 스냅샷이 [LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md) 예시와 문자 단위 일치
- [ ] 모든 인증 fail closed, 시크릿·번호 로그 없음

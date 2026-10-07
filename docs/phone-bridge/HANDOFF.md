# 구현 핸드오프 DO — GPT Astra Max

리드: Claude (개발팀장). 설계 원본: [`DESIGN.md`](./DESIGN.md). 설계와 다르게 가야 하면 PR 설명에 "Deviation: …" 로 사유를 적는다 — 조용히 바꾸지 않는다.

## 작업 레포 / 브랜치

- 레포: `VIBEVENDIT/hotel-at-gangnam-maknae` (private).
- 베이스: `cursor/realtime-ops-webhooks-746b` (PR #1 head, Next.js 16 + `lib/slack.ts` 등). 여기서 새 브랜치를 따고 **`main` 대상 PR 1개**를 연다. PR #1 커밋이 포함되므로 머지 시 PR #1도 같이 들어간다 — PR 설명에 "Includes #1" 명시.
- PC 브랜치(PR #2–#6, `sms-bridge/` .NET)는 **베이스로 쓰지 않는다**. 코드 재사용 없음.
- 공개 레포 `VIBEVENDIT/sms-bridge-channel`은 문서만 (이미 이 브랜치에 있음). 바이너리 미러링 하지 않음.

## DO (순서대로)

1. **lib/phone.ts** — `normalizeKrPhone(raw) → e164 | null`, `formatKrPhone(e164)`, `isKrMobile(e164)`. unit test.
2. **lib/kv.ts** — `Kv` 인터페이스(`get`, `set(key, val, {nx?, exSeconds?}) → boolean`, `incr`, `del`) + Upstash REST 구현 + in-memory 구현. 두 env 이름 세트 모두 지원 (DESIGN §3.4). `@upstash/redis` 사용 가능.
3. **lib/sms-gate.ts** — `verifySmsGateSignature(rawBody, ts, sig, key, now)` (constant-time, ±300s), `sendSms({to, text}) → {id}` (OpenAPI의 실제 필드명 확인 후 고정, Basic auth), 웹훅 바디 타입(`zod` 등으로 파싱).
4. **lib/sms-classify.ts** — `classifySms` (DESIGN §4.1). 기존 `lib/classify.ts` 스타일·테스트 패턴 그대로.
5. **lib/auto-reply.ts** — `readAutoReplyConfig(env)` (기본 `dry-run`), pure `decideAutoReply` (DESIGN §4.2 1–10, 순서 고정), 실행부 `runAutoReply` (SET NX → send → INCR → 스레드 메모).
6. **lib/slack.ts 확장** — `postLoungeTriage`에 `thread_ts`, `reply_broadcast` 옵션 추가 + `ts` 반환. `addReaction`. 기존 호출부 시그니처는 유지.
7. **app/api/webhooks/sms-gate/route.ts** — raw body 읽기 → 서명 → dedup → 이벤트 분기 (DESIGN §3.1–3.2, §4). `runtime = "nodejs"`.
8. **app/api/webhooks/slack/route.ts 확장** — 서명 필수화, `>>` 스레드 답글 → `sendSms` (DESIGN §3.3). 3초 내 ack + `after()`.
9. **scripts/sms-gate-register-webhooks.mjs** — env(`SMS_GATE_USERNAME/PASSWORD`, `APP_BASE_URL`)에서 읽어 `sms:received`, `sms:sent`, `sms:delivered`, `sms:failed`, `system:ping` 5개를 `${APP_BASE_URL}/api/webhooks/sms-gate`로 등록. 이미 같은 URL+event 있으면 skip (idempotent). `--list`, `--delete-all` 옵션. 비밀번호를 로그에 찍지 않음.
10. **.env.example** — DESIGN §5 키 이름만 추가 (값 비움, 기본값은 주석).
11. **README.md (maknae)** — "SMS (phone bridge)" 섹션: 라우트 2개, env, 링크 → `https://github.com/VIBEVENDIT/sms-bridge-channel/blob/main/docs/phone-bridge/INSTALL-ANDROID.md`.
12. **tests/** — DESIGN §7 AC1–AC9 각각 최소 1개 테스트, 테스트 이름에 `AC<n>` 접두어. fetch는 mock, KV는 in-memory.
13. **CI** — `.github/workflows/ci.yml` (Node 22: `npm ci && npm run lint && npm test && npm run build`) 없으면 추가.
14. **Vercel** — Vercel MCP/CLI로 team `venditjasons-projects`에 Git 프로젝트 `hotel-at-gangnam-maknae` 생성 (현재 없음), Upstash Redis Marketplace 연결, env는 **키만 생성하고 값은 비밀 값이 필요한 것만 사람에게 요청** (`SMS_GATE_*`, `SLACK_*`). `SMS_AUTO_REPLY_MODE=dry-run`, `SMS_AUTO_REPLY_TEXT`(DESIGN §4.3 예시)는 직접 세팅 가능. 권한 없으면 PR에 "Blocked: Vercel" 와 정확한 클릭 단계 기록.
15. **PR 설명** — AC1–AC13 체크리스트(통과/미검증 구분), 배포 URL, 사람이 해야 할 단계 목록(아래), "No secrets" 선언.

## 하지 말 것

- 커스텀 Android 앱/APK 빌드 (P2).
- PC `sms-bridge/` 코드 수정·머지, 공개 `manifest.json` 버전 bump (설치된 POS가 계속 폴링 중 — 건드리면 실패 루프).
- 실제 lounge에 테스트 메시지 게시 (dry-run + 직원 스모크는 철수 단계). 단 배포 검증용 `conversations.info` 읽기는 OK.
- `SMS_AUTO_REPLY_MODE=on` 으로 배포. on 전환은 철수 스모크(AC13) 후 사람이 한다.
- 시크릿 커밋, 로그에 토큰/비밀번호/서명키 출력. 전화번호는 로그에서 뒤 4자리만.

## 사람 단계 (구현 에이전트가 대신 못 함)

1. 호텔 폰 잠금 해제·Play 로그인, 앱 설치, SMS/전화 권한 허용, 배터리 최적화 제외 — [`INSTALL-ANDROID.md`](./INSTALL-ANDROID.md).
2. 앱 화면의 Cloud username/password, Webhook signing key → Vercel env 입력 (채팅/레포에 붙여넣지 않음).
3. Slack 앱 스코프 추가 + Event Subscriptions URL 저장 + 재설치 (워크스페이스 관리자).
4. 스모크 후 `SMS_AUTO_REPLY_MODE=on` 전환, PC POS 브리지 제거.

## 머지 게이트 (리드가 판단)

전부 만족해야 approve:

- [ ] CI green (lint/test/build), 테스트에 `AC1`–`AC9` 존재하고 통과
- [ ] 서명 검증 fail-closed (sms-gate, Slack 둘 다), dedup 키가 부작용 **전에** 확인
- [ ] 자동회신 SET NX가 send **전에** 성공해야만 발송 (AC7)
- [ ] `sms:sent/delivered/failed` 경로에 자동회신 호출 없음 (코드 리뷰로 확인)
- [ ] 기본 모드 dry-run, 템플릿 없으면 skip
- [ ] `.env.example`·fixture·로그에 시크릿/실번호 없음
- [ ] 기존 Gmail/Channel Talk 라우트 테스트 회귀 없음
- [ ] PR 설명에 배포 상태와 사람 단계가 정확히 적힘

Nit 은 머지 차단하지 않고 follow-up 으로 남긴다. 위 항목 중 하나라도 빠지면 "Request changes" + 구체 파일/라인.

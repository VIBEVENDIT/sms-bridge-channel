# 폰 SMS 브리지 v2 — 설계 · 우선순위 · 수용기준

Owner: 영미 (설계/구현/배포) · 리드: Claude (개발팀장) · 구현: GPT Astra Max · 요청: Jason 2026-10-07 via 김철수

PC Phone Link / Setup.exe (1.x) 경로를 **폐기**하고, 호텔 폰에 설치한 SMS 게이트웨이 앱이 직접 Vercel 웹훅으로 붙는 구조로 바꾼다. Slack `#lounge-호텔앳강남`에서 수신·발신·자동회신이 모두 보이게 한다.

## 1. 조사 결과 (현 상태)

| 항목 | 위치 | 상태 |
| --- | --- | --- |
| PC 브리지 (.NET tray, Setup.exe, auto-update) | `VIBEVENDIT/hotel-at-gangnam-maknae` PR #2–#6 | 미머지. Phone Link 토스트 파싱 → Grok 웹훅. 발신은 `sms:` URI 반자동(TODO). v1.5.0 zip Release 미게시(404) |
| 공개 업데이트 채널 | 이 레포 `manifest.json` / `commands.json` | live 200. 설치된 POS가 폴링 중 |
| Slack 막내 봇 + 메일 triage | maknae PR #1 (`cursor/realtime-ops-webhooks-746b`) | 미머지, Vercel 미배포. Next.js 16, `lib/slack.ts` `postLoungeTriage`, `lib/classify.ts` (광고/noreply/이미회신 skip), `lib/dedup.ts`(in-memory) |
| 호텔메일 "자동회신" | 접근 가능한 VIBEVENDIT 레포에 발송 코드 없음 | 현재 메일 플로우 = **회신 필요 건만 lounge에 올림**(`메일\|발신\|내용` + 권장 핸들링). SMS 자동회신은 이 분류 규칙 + 1차 응답 발송으로 정의한다 (§4) |
| Vercel | team `venditjasons-projects` | `hotel-at-gangnam-maknae` 프로젝트 **없음** → 구현 PR에서 생성 |

결론: 새 서비스를 만들지 않는다. **maknae Next.js 앱(PR #1 기반)에 SMS 라우트 2개 + lib 몇 개를 추가**하고, 폰에는 오픈소스 앱을 설치한다. 커스텀 APK는 만들지 않는다 (P2).

## 2. 아키텍처

```
 호텔 폰 (Android, 호텔 SIM)
 └─ SMS Gateway for Android (sms-gate.app, Cloud mode)
      │  sms:received / sms:sent / sms:delivered / sms:failed / system:ping
      │  HTTPS POST, X-Signature = HMAC-SHA256(rawBody + X-Timestamp)
      ▼
 Vercel: hotel-at-gangnam-maknae (Next.js)
   POST /api/webhooks/sms-gate ──► classify ──► Slack lounge 「앳강남 막내」
        │                            │            문자|010-1234-5678|내용 + 권장 핸들링
        │                            └─► decideAutoReply ─► (on) send / (dry-run) 스레드 메모
        │
   POST /api/webhooks/slack  ◄── Slack Events (message.channels, lounge 스레드 답글)
        │   ">> 내용" 인 사람 답글만
        └─► sms-gate Cloud API  POST https://api.sms-gate.app/3rdparty/v1/messages
                                   (폰이 FCM 푸시로 받아 실제 발송)
   Upstash Redis (Vercel Marketplace): idempotency · thread 매핑 · 쿨다운 · 캡 · 수신거부
```

선정 이유: sms-gate.app (capcom6/android-sms-gateway, Apache-2.0)은 기본 문자앱을 대체하지 않고(직원은 삼성 메시지 계속 사용), 서명 웹훅 + 약 2일 지수 재시도 + 클라우드 발송 API를 이미 제공한다. 폰에 인바운드 포트를 열 필요가 없다.

iOS: 서드파티 앱의 SMS 읽기/무인 발송을 Apple이 허용하지 않는다. **미지원**. 호텔 폰이 iPhone이면 호텔 번호용 Android 단말(중고 가능)에 SIM을 옮기는 것이 유일한 정식 경로.

## 3. 계약 (Contracts)

### 3.1 인바운드 `POST /api/webhooks/sms-gate`

- 서명: `hex(HMAC_SHA256(SMS_GATE_WEBHOOK_SIGNING_KEY, rawBody + X-Timestamp))` == `X-Signature` (constant-time). `|now - X-Timestamp| > 300s` 거부. 키 미설정 또는 불일치 → `401`, 부작용 없음 (**fail closed**; PR #1의 "env 없으면 통과"와 다름).
- 처리 이벤트: `sms:received`, `sms:sent`, `sms:delivered`, `sms:failed`, `system:ping`. 그 외 → `200 {ignored}`.
- 응답은 30초 내 2xx. Slack/발송은 `after()`(Next) 또는 await 하되 전체 < 10s.
- Idempotency: 바디 `id` 로 `SET sms:evt:{id} NX EX 604800`. 이미 있으면 `200 {duplicate}`.
- 번호 정규화: `normalizeKrPhone()` → E.164 (`01012345678`, `+821012345678` → `+821012345678`). 표시는 `010-1234-5678`.

### 3.2 Slack 게시 형식 (메일 플로우와 동일 톤)

- 번호당 스레드 1개: `sms:thread:{e164}` → parent `ts` (EX 30일). 없으면 새 parent, 있으면 그 스레드에 `reply_broadcast: true`.
- Parent: `문자|010-1234-5678|<본문 300자 컷>` + 줄바꿈 `권장 핸들링: …` (`postLoungeTriage` 재사용, username 「앳강남 막내」).
- 분류 태그(§4.1)가 `ad`/`system` 이면 parent 앞에 `[광고]`/`[시스템]` 표시하고 권장 핸들링 생략. **모든 인바운드는 lounge에 남긴다** (메일처럼 버리지 않음 — 폰 번호 수신함은 lounge가 유일한 사본).
- 역매핑: `sms:ts:{parentTs}` → e164 (EX 30일).

### 3.3 Slack → SMS `POST /api/webhooks/slack` (기존 라우트 확장)

- `SLACK_SIGNING_SECRET` 서명 필수 (미설정 시 `event_callback` 처리 거부, `url_verification` 만 허용).
- 대상: `event.type == "message"`, `channel == SLACK_LOUNGE_CHANNEL_ID`, `thread_ts` 있음, `subtype` 없음, `bot_id` 없음, `user != 봇 자신`.
- 발송 트리거: 텍스트가 `SMS_OUTBOUND_PREFIX`(기본 `>>`)로 시작. 접두어 없는 스레드 대화는 **내부 메모로 간주, 발송 안 함**.
- 선택: `SMS_SLACK_ALLOWED_USER_IDS` 설정 시 해당 user만 발송 가능.
- Dedup: `SET slack:evt:{event_id} NX EX 86400`, `X-Slack-Retry-Num` 재시도 무시. 3초 내 200 후 `after()`로 발송.
- 발송: `POST {SMS_GATE_BASE_URL}/3rdparty/v1/messages` (Basic `SMS_GATE_USERNAME:SMS_GATE_PASSWORD`, `textMessage.text`, `phoneNumbers:[e164]`, `deviceId` 선택 — 필드명은 OpenAPI로 확인). 응답 `id` → `sms:out:{id}` = `{ts, kind:"staff"}` EX 7일. `sms:human:last:{e164}` = now.
- 피드백: 수락 → 직원 메시지에 `:outbox_tray:` reaction. `sms:delivered` → `:white_check_mark:`. `sms:failed` → `:x:` + 스레드에 사유.
- 폰에서 삼성 메시지로 직접 보낸 문자는 sms-gate가 이벤트를 내지 않는다 → **Slack에 안 보임** (알려진 한계, P2). 운영 규칙: 직원 회신은 Slack `>>` 로.

### 3.4 State (Upstash Redis via Vercel Marketplace)

`KV_REST_API_URL`/`KV_REST_API_TOKEN` 또는 `UPSTASH_REDIS_REST_URL`/`UPSTASH_REDIS_REST_TOKEN` 둘 다 허용. 테스트용 in-memory 구현을 같은 인터페이스로. KV 장애 시: 인바운드 Slack 게시는 진행, 자동회신은 `skip:kv-unavailable`, Slack→SMS는 `:x:` + "저장소 오류".

| Key | 값 | TTL |
| --- | --- | --- |
| `sms:evt:{id}` / `slack:evt:{event_id}` | 1 | 7d / 1d |
| `sms:thread:{e164}` / `sms:ts:{ts}` | ts / e164 | 30d |
| `sms:out:{gatewayMsgId}` | `{ts, kind, staffMsgTs?}` | 7d |
| `sms:ar:last:{e164}` | 발송 시각 (SET NX = at-most-once) | 쿨다운 |
| `sms:ar:count:{YYYYMMDD KST}` | INCR | 2d |
| `sms:human:last:{e164}` | 직원 발신 시각 | human grace |
| `sms:optout:{e164}` | 1 | 없음 |
| `sms:ping:last` | system:ping 시각 | 없음 |

## 4. 자동회신 (호텔메일형)

### 4.1 분류 `classifySms({sender, body})` → `guest | ad | system | self`

- `self`: sender == `HOTEL_SMS_NUMBER`.
- `ad`: 본문이 `(광고)`로 시작, 또는 `무료수신거부`/`수신거부 080`/`080-` 포함 (정보통신망법 표기).
- `system`: sender가 한국 휴대폰(`+8210/11/16/17/18/19`)이 아님(15xx/16xx/18xx 대표번호, 단축번호, 국제), 또는 본문에 `인증번호`/`인증 번호`/`verification code`/`[Web발신]` 류 + 숫자 4–8자리, 또는 자동응답 흔적(`자동응답`, `부재중`, `automated`, `auto-reply`, `(자동응답)`).
- 그 외 `guest`. OTA(야놀자/여기어때/아고다 등) 대표번호 알림은 `system`으로 떨어져 lounge 게시만 된다 — 메일 분류와 같은 의도.

### 4.2 결정 `decideAutoReply(input, state, config)` → `{action: "send" | "dry-run" | "skip", reason}`

순서대로 평가, 첫 매치에서 종료 (pure function, 전부 unit test):

1. `mode == off` → skip `mode-off`
2. class != `guest` → skip `class-<x>`
3. `sms:optout` → skip `opted-out`
4. 본문이 수신거부 키워드(`STOP`, `수신거부`, `그만`, `차단`) → optout 기록 + skip `opt-out-request`
5. `sms:human:last` < `SMS_AUTO_REPLY_HUMAN_GRACE_HOURS`(12h) → skip `human-active` (메일의 "이미 회신한 스레드" 대응)
6. `sms:ar:last` 존재 (쿨다운 `SMS_AUTO_REPLY_COOLDOWN_HOURS`, 24h) → skip `cooldown`
7. 오늘 카운트 >= `SMS_AUTO_REPLY_DAILY_CAP`(50) → skip `daily-cap` + lounge 경고 1회/일
8. `SMS_AUTO_REPLY_TEXT` 비어있음 → skip `no-template`
9. `mode == dry-run` → `dry-run` (쿨다운 키는 기록하지 않음)
10. → `send`

`send` 시: `SET sms:ar:last:{e164} NX EX cooldown` 성공한 경우에만 발송(재시도·동시 웹훅에도 최대 1회), INCR count, `sms:out:{id}` kind=`auto`. 스레드에 `🤖 자동회신 발송: <text>` (dry-run이면 `🤖 [DRY RUN] 자동회신 예정: <text> (사유: …)`). skip도 `guest` 클래스면 스레드에 한 줄 `🤖 자동회신 생략: <reason>` (디버그 가능성 > 노이즈).

루프 가드 요약: (a) `sms:received`만 자동회신 트리거 — `sms:sent/delivered`는 절대 아님, (b) 자기 번호 · 비휴대폰 · 자동응답 본문 skip, (c) 번호당 24h 1회 SET NX, (d) 일일 캡, (e) Slack 봇/편집/subtype 메시지는 발송 트리거 아님, (f) 기본 모드 `dry-run`.

### 4.3 템플릿

`SMS_AUTO_REPLY_TEXT` (Vercel env, 70자 이하 권장 = SMS 1건 UCS-2). 예:

```
[호텔앳강남] 문자 감사합니다. 프런트에서 확인 후 곧 답장드리겠습니다. (자동응답)
```

`(자동응답)` 접미어는 필수 (상대가 봇이어도 우리 쪽 분류에서 걸리고, 게스트에게 투명).

## 5. Env (Vercel only, `.env.example`에는 키 이름만)

`SMS_GATE_BASE_URL`(기본 `https://api.sms-gate.app`), `SMS_GATE_USERNAME`, `SMS_GATE_PASSWORD`, `SMS_GATE_DEVICE_ID`(선택), `SMS_GATE_WEBHOOK_SIGNING_KEY`, `HOTEL_SMS_NUMBER`, `SMS_AUTO_REPLY_MODE`(`off|dry-run|on`, 기본 `dry-run`), `SMS_AUTO_REPLY_TEXT`, `SMS_AUTO_REPLY_COOLDOWN_HOURS`(24), `SMS_AUTO_REPLY_DAILY_CAP`(50), `SMS_AUTO_REPLY_HUMAN_GRACE_HOURS`(12), `SMS_OUTBOUND_PREFIX`(`>>`), `SMS_SLACK_ALLOWED_USER_IDS`(선택), KV 키(§3.4). 기존: `SLACK_BOT_TOKEN`, `SLACK_SIGNING_SECRET`(이제 필수), `SLACK_LOUNGE_CHANNEL_ID`(기본 `C0AN0CDAADC` — #lounge-호텔앳강남 맞는지 배포 시 `conversations.info`로 확인).

Slack 앱 추가 설정: scopes `channels:history`, `reactions:write` (기존 `chat:write`, `chat:write.customize`), Event Subscriptions `message.channels`, Request URL `/api/webhooks/slack`, 봇을 lounge에 초대. 스코프 변경 시 앱 재설치(워크스페이스 관리자).

## 6. 우선순위

- **P0 (머지 게이트)**: §3.1–3.4, §4 전부, register-webhooks 스크립트, `.env.example`, 테스트, Vercel 프로젝트 생성·배포, 이 레포 문서 링크.
- **P1**: `system:ping` 15분 → `GET /api/sms/health` (`lastPingAt`), Vercel Cron 30분마다 60분 이상 무응답이면 lounge 경고(1회/6h). `mms:downloaded` 본문 텍스트 게시(첨부는 "[사진 n장]"만).
- **P2**: 폰에서 직접 보낸 문자 동기화(커스텀 앱 또는 sent-box 지원 필요), JWT 인증 전환, sms-gate E2E 암호화, `/sms 010… 내용` 신규 대화 슬래시 커맨드, 메일 자동회신이 별도로 존재하면 템플릿 문구 통일.

## 7. 수용기준 (Acceptance Criteria)

리뷰어(리드)는 아래를 PR 설명의 체크리스트 + 테스트 이름으로 대조한다.

| # | 기준 | 검증 |
| --- | --- | --- |
| AC1 | 유효 서명 `sms:received` → lounge에 `문자\|010-…\|본문` 1건, 같은 번호 재수신은 같은 스레드 | unit (Slack mock) + 실폰 스모크 |
| AC2 | 서명 누락/불일치/5분 초과 timestamp → 401, Slack·발송 호출 0 | unit |
| AC3 | 같은 `id` 웹훅 2회 → Slack 게시 1회 | unit |
| AC4 | lounge 스레드 `>> 안녕하세요` (사람) → sms-gate send 1회, 번호 = 스레드 매핑 번호, 텍스트에서 접두어 제거 | unit + 실폰 |
| AC5 | 접두어 없는 답글, 봇 메시지, `message_changed`, 스레드 아닌 메시지, Slack 재시도 → send 0 | unit |
| AC6 | `decideAutoReply` §4.2 1–10 각 분기 테이블 테스트 (최소 12 케이스: 광고, 인증번호, 1588 번호, 자기번호, 자동응답 본문, STOP, human-active, cooldown, cap, no-template, dry-run, send) | unit |
| AC7 | 동시 2개 `sms:received`(같은 번호, 다른 id) → 자동회신 send 최대 1회 | unit (in-memory KV with NX) |
| AC8 | `sms:sent/delivered/failed` 는 자동회신을 절대 트리거하지 않고 해당 Slack 메시지 reaction/스레드만 갱신 | unit |
| AC9 | 기본 env로 배포 시 `SMS_AUTO_REPLY_MODE` = dry-run (실발송 0) | unit (env 파서) |
| AC10 | 레포에 시크릿 없음: `.env.example` 값 비어있음, 테스트 fixture 키는 명백한 dummy | 리뷰 + `git grep` |
| AC11 | `npm test`, `npm run lint`, `npm run build` green (CI) | CI |
| AC12 | Vercel production 배포, `GET /api/health` 200, 웹훅 URL 2개가 401(무서명) 응답 | curl |
| AC13 | 설치 문서(`INSTALL-ANDROID.md`) 단계대로 실폰 1대에서 수신 → lounge, `>>` → 폰 발신 확인 (사람 단계 포함) | 철수 스모크 리포트 |

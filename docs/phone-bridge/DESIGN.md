# 호텔 SMS 브리지 v3 — 설계

Owner: 영미 · 리드/머지 게이트: Claude (개발팀장) · 구현·QA: GPT Astra Max · 요청: Jason 2026-10-07 via 김철수

같이 읽을 문서: [LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md) · [AUTO-REPLY.md](./AUTO-REPLY.md) · [ACCEPTANCE.md](./ACCEPTANCE.md) · [HANDOFF.md](./HANDOFF.md) · [INSTALL-ANDROID.md](./INSTALL-ANDROID.md)

## 0. 목표 (Jason 필수 범위, 전부 P0)

1. 폰 설치만. PC Phone Link / Setup.exe 폐기.
2. 호텔 폰의 **수신·발신 문자 전부**(SMS·LMS·MMS, Slack발·폰 직접발 포함)가 `#lounge-호텔앳강남`(`C0AN0CDAADC`)에 **누락 0 · 중복 0**.
3. 호텔메일과 동일 수준 자동회신 — FAQ/정책 답변, 답장 필요한 것만, OTP·광고·시스템 스킵 ([AUTO-REPLY.md](./AUTO-REPLY.md)).
4. lounge 형식 = 기존 `*[메일수신]*` / `:loading:` / `:완료:` 핸드오프 형식 ([LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md)).
5. 실폰 E2E QA PASS 전에는 go-live 금지 ([ACCEPTANCE.md](./ACCEPTANCE.md)).

## 1. 현 상태 (조사)

| 항목 | 위치 | 상태 |
| --- | --- | --- |
| PC 브리지 (.NET tray, Setup.exe) | `hotel-at-gangnam-maknae` PR #2–#6 | 미머지. Phone Link 토스트 파싱 → 수신만, 발신 반자동. **폐기** |
| 공개 업데이트 채널 | 이 레포 `manifest.json` | live, 설치된 POS 폴링 중. 버전 bump 금지 |
| 막내 봇 (Slack/Gmail/채널톡 웹훅) | maknae PR #1 (`cursor/realtime-ops-webhooks-746b`) | 미머지, Vercel 프로젝트 없음. Next.js 16, `lib/slack.ts`, `lib/classify.ts` |
| 메일 자동회신 | 김철수 에이전트(Slack 봇 `B0C080ZKPGW`)가 LLM으로 정책 기반 회신 | 코드 레포 없음. 규칙은 lounge 스레드에 Jason 지시로 축적 → [AUTO-REPLY.md](./AUTO-REPLY.md) §1에 출처 링크로 정리 |
| lounge 형식 | `*[메일수신]* :loading:` + `*필드* = 값`, `*[지메일 발송]*` | Jason 지시로 확정 → [LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md) |
| 호텔 폰 | 앳 강남 업무폰 010-5198-5442 (`#호텔-앳-강남` 채널 설명) | **OS 미확인** — Android 아니면 G0 차단 |

## 2. 결정: 자체 Android 앱 (v2의 sms-gate.app 안 폐기)

v2 초안(sms-gate.app)은 **요구 2 불충족**이라 폐기한다.

- sms-gate는 자기 앱으로 보낸 문자만 `sms:sent` 이벤트를 낸다. 직원이 삼성 메시지로 직접 보낸 답장은 Slack에 안 남는다 → "발신 전부" 위반.
- 발신 본문·번호가 제3자 클라우드(api.sms-gate.app)를 경유 → 게스트 개인정보.
- 수신(웹훅)과 직접발신(별도 앱)을 두 앱으로 나누면 중복 판정이 fuzzy match가 된다 → "중복 0" 보장 불가.

채택: **AtGangnam SMS Bridge** (Kotlin, 이 프로젝트 소유, 사이드로드 APK). 기본 문자앱은 삼성 메시지 그대로.

```
 호텔 폰 (Android, 호텔 SIM, 채팅+ OFF, 상시 충전)
 └─ AtGangnam SMS Bridge (foreground service, type=remoteMessaging)
      ├─ Telephony provider 읽기: content://sms (inbox=1, sent=2), content://mms (inbox, sent)
      │    ContentObserver + 60s sweep + 부팅/재시작 시 sweep, provider _id 워터마크
      ├─ 로컬 원장(Room): providerKey 유니크 → 서버 ack 전까지 무한 재시도
      ├─ 발송: SmsManager.sendMultipartTextMessage + sent/delivered PendingIntent
      └─ HTTPS
           POST /api/sms/device/events        (수신·발신·발송결과·heartbeat 배치)
           POST /api/sms/device/attachments   (MMS 첨부)
           GET  /api/sms/device/outbox?wait=25 (long-poll, 발송 지시 수신)
                 ▲
 Vercel: hotel-at-gangnam-maknae (Next.js, PR #1 위에)
   ├─ ingest → 원장(KV) → Slack 게시 (스레드/상태 이모지)  [LOUNGE-FORMAT]
   ├─ 수신 → 규칙 분류 → LLM(AI Gateway, KB 근거) → 가드 → outbox  [AUTO-REPLY]
   ├─ POST /api/webhooks/slack: 스레드 `>> 본문` → outbox, 상태 reaction → parent 갱신
   ├─ Cron 1분: Slack 미게시 원장 재게시 · 디바이스 오프라인 경보 · 대사(reconcile)
   └─ Upstash Redis (Vercel Marketplace)
```

iOS: 서드파티 앱의 SMS 읽기/발송을 Apple이 막음 → **미지원**. 호텔 폰이 iPhone이면 G0 차단, 보고.

## 3. 정확히 한 번 (누락 0 · 중복 0) 설계

### 3.1 폰 → 서버 (누락 0)

- **원천은 provider 한 곳**: SMS_RECEIVED 브로드캐스트는 sweep 트리거로만 쓴다(브로드캐스트 메시지를 직접 보고하지 않음 → 이중 보고 경로 없음).
- `providerKey` = `sms:{_id}` / `mms:{_id}`. 로컬 Room 테이블 `reported(providerKey PK, type, state[pending|acked], attempts)`.
- sweep: `_id > watermark(type)` 전부 + `date >= now-48h` 재확인(워터마크 손상 대비). 새 행 → pending 삽입(PK 충돌 = 이미 있음).
- 업로드: pending 배치(≤50) → 서버 `acked[]` 받은 것만 acked. 네트워크 실패 → 지수 백오프(최대 5분), 데이터 삭제 없음.
- MMS: 텍스트 part 결합 + 첨부(image/*, ≤10MB 각) 별도 업로드. 한국 LMS(장문)는 MMS로 저장되므로 **MMS 처리는 P0**.
- 앱 자체 발송분: provider sent 행과 outbox 매칭(`address`+`body`+`|date-sentAt|<120s`) → 이벤트에 `outboxId` 포함. 서버도 같은 규칙으로 2차 매칭.
- Heartbeat 60초: `{appVersion, permissions, batteryOptExempt, charging, network, sims[], maxId{sms,mms}, counts{yyyymmdd:{in,out}}}`.

### 3.2 서버 (중복 0)

- 이벤트 키 `{deviceId}:{providerKey}` → `SET sms:msg:{key} NX` 실패면 이미 처리 → ack만 반환.
- Slack 게시는 원장 기록 후 `after()`에서. 게시 성공 시 `slackTs` 저장. 게시 락 `sms:post:{key}` NX EX 60 → 동시 경로(after/cron) 이중 게시 불가.
- Cron(1분): `slackTs` 없는 원장 → 재게시. 앱이 보낸 일별 counts ≠ 서버 counts → heartbeat 응답에 `resync:{sinceMs}` → 앱이 그 구간 재보고(멱등).
- 앱 발송 매칭 실패 시 서버 2차 매칭 → 실패해도 `*[문자발송]* 발송경로 = 호텔폰 직접`으로 1건 게시(누락보다 안전). 단, 같은 outbox가 이미 게시됐으면 중복 아님 판정을 위해 outbox 원장에 `providerKey` 기록.

### 3.3 서버 → 폰 발송 (최대 1회, 결과 가시화)

- outbox 항목 `{id, to, text, origin: auto|slack, slackUser?, convKey}` → 앱 long-poll 응답(lease 60초).
- 앱: 로컬에 `sending` 기록 **후** SmsManager 호출. 결과 PendingIntent → `send_result{sent|failed(reason)|delivered}` 보고.
- 앱 크래시로 결과 없는 `sending` → 재전송하지 않고 `unknown` 보고 → Slack `발송 상태 = 확인 필요 :loading:`.
- 서버: lease 만료 + 미ack → 재전달은 앱이 그 id를 `sending/sent`로 모를 때만(앱이 id 원장 보유) → 이중 발송 불가.

### 3.4 인증

- 페어링: 앱 첫 실행 → 서버 URL(빌드 기본값) + **일회용 페어링 코드**(`SMS_BRIDGE_PAIRING_CODE`, Vercel env, 48h 내 1회) → `POST /api/sms/device/pair` → 서버가 32바이트 device token 발급(KV에 sha256만 저장), 코드 소진.
- 이후 모든 디바이스 요청 `Authorization: Bearer <token>`. 토큰 회전: 재페어링. 분실 시 `/api/sms/device/revoke`(관리 시크릿).
- Slack: `SLACK_SIGNING_SECRET` 서명 필수(fail closed). Cron: `CRON_SECRET`.

## 4. 상태 저장 (Upstash Redis)

| Key | 값 | TTL |
| --- | --- | --- |
| `sms:msg:{deviceId}:{providerKey}` | `{dir, e164, body, ts, kind, slackTs?, convKey, outboxId?}` | 90d |
| `sms:post:{key}` | 게시 락 | 60s |
| `sms:conv:{e164}` | `{parentTs, lastActivityMs, status, guestName?}` | 30d |
| `sms:conv:ts:{parentTs}` | e164 | 30d |
| `sms:outbox:{id}` | outbox 항목 + 상태 + `providerKey?` | 30d |
| `sms:outbox:pending` | 리스트 | — |
| `sms:ar:evt:{key}` | 자동회신 1회 락 | 7d |
| `sms:ar:num:{e164}:{yyyymmdd}` | 일 카운트 | 2d |
| `sms:ar:hold:{e164}` | 보류응답 쿨다운 | 24h |
| `sms:ar:last:{e164}` | 마지막 자동회신 ms | 24h |
| `sms:ar:day:{yyyymmdd}` | 전체 일 카운트 | 2d |
| `sms:human:last:{e164}` | 직원 발신 ms | 24h |
| `sms:optout:{e164}` | 1 | 없음 |
| `sms:device:{id}` | 토큰 해시, 마지막 heartbeat | 없음 |
| `sms:count:{yyyymmdd}:{in|out}` | 원장 카운트 | 7d |

KV 장애: 이벤트에 5xx → 앱이 재시도(누락 없음). Slack 게시·자동회신 없음.

## 5. Env (Vercel only, 레포엔 키 이름만)

기존: `SLACK_BOT_TOKEN`, `SLACK_SIGNING_SECRET`(필수), `SLACK_LOUNGE_CHANNEL_ID`(`C0AN0CDAADC`).
신규: `SMS_BRIDGE_STAGE`(`qa|live`, 기본 `qa`), `SMS_QA_CHANNEL_ID`, `SMS_QA_ALLOWED_NUMBERS`(쉼표), `SMS_BRIDGE_PAIRING_CODE`, `SMS_BRIDGE_ADMIN_SECRET`, `CRON_SECRET`, `HOTEL_SMS_NUMBER`(`01051985442` 확인 후), `SMS_AUTO_REPLY_MODE`(`off|shadow|on`, 기본 `shadow`), `SMS_AI_MODEL`, `SMS_URGENT_MENTION`(기본 `<!here>`), KV(`KV_REST_API_URL`/`KV_REST_API_TOKEN` 또는 `UPSTASH_REDIS_REST_URL`/`UPSTASH_REDIS_REST_TOKEN`). AI Gateway는 Vercel OIDC(키 없음), 로컬만 `AI_GATEWAY_API_KEY`.

Slack 앱: scopes `chat:write`, `chat:write.customize`, `channels:history`, `reactions:read`, `reactions:write`, `files:write`; events `message.channels`, `reaction_added`; Request URL `/api/webhooks/slack`; 봇을 lounge + QA 채널에 초대.

## 6. 단계 (stage)

- `qa`: 모든 게시 → `SMS_QA_CHANNEL_ID`. 자동회신·발송은 `SMS_QA_ALLOWED_NUMBERS`에게만. 그 외 번호는 원장+QA 채널 게시만(발송 0).
- `live`: lounge 게시, 전체 번호. **리드 PASS 판정([ACCEPTANCE.md](./ACCEPTANCE.md) §4) 후에만** env 전환.

## 7. 우선순위

전부 P0: 위 §2–§6, [AUTO-REPLY.md](./AUTO-REPLY.md), [LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md), 설치·APK 배포, 실폰 QA.
P1 (go-live 후): PMS(pms.hoteliers.space) 전화번호→투숙객명·기간 자동 채움, 앱 인앱 업데이트 알림, 대시보드.
P2: 다중 디바이스, RCS 수신(플랫폼상 불가 — 채팅+ OFF로 회피).

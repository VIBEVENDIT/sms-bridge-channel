# 문자 자동회신 — 호텔메일 규칙 매핑 · 정책 KB · 가드

메일 자동회신은 김철수 에이전트가 정책·FAQ에 근거해 직접 회신하고, 정책 밖은 `:loading: 프론트 확인 필요`로 올리는 방식이다. 문자도 같은 수준으로 한다: **결정적 규칙 → LLM(KB 근거만) → 출력 검증 → 가드 → 발송**.

## 1. 메일 규칙 → 문자 매핑

| # | 메일 규칙 (출처) | 문자 적용 |
| --- | --- | --- |
| M1 | 회신이 필요한 것만 처리. 야간 신규 중 OTP·마케팅·예약확정만은 스킵 ([9/13 새벽 전달](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789254214074069)) | §2 R2–R5: OTP·광고·시스템·예약확정 알림은 `:loudspeaker:` 게시만, 자동회신 0 |
| M2 | ads / noreply / system 스킵, 호텔이 이미 회신한 스레드 스킵 (maknae `lib/classify.ts`) | noreply ≈ 비휴대폰 발신(15xx·16xx·18xx·080·단축번호·국제 대표) → R4. 이미 회신 ≈ 직원 발신 30분 이내 → G3 |
| M3 | 정책·FAQ로 답할 수 있는 건 바로 회신하고 클로징 ([폐기요청](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789270445241489?thread_ts=1789254214.074069&cid=C0AN0CDAADC), [엘베](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789270466049329?thread_ts=1789254214.074069&cid=C0AN0CDAADC), "정책에 박고 회신도 직접" [9/11](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789132816968109?thread_ts=1789132543.693449&cid=C0AN0CDAADC)) | §3 `faq` → KB 근거 답변 발송, parent `:완료:` |
| M4 | 정책 밖·결정 필요(무료취소 승인 등)는 `:loading: 프론트 확인 필요` ([10/5](https://venditinc.slack.com/archives/C0AN0CDAADC/p1791156236381489), [9/19](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789786032180629?thread_ts=1789786032.180629&cid=C0AN0CDAADC)) | §3 `escalate` → 보류응답 1회 + `:loading:` + `*[확인 필요]*` 한 줄 |
| M5 | 서명 「HOTEL AT GANGNAM 드림」, 개인 이름 금지 ([9/13](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789276928448139?thread_ts=1789254214.074069&cid=C0AN0CDAADC), 전역 적용 [14:31](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789277498815999?thread_ts=1789254214.074069&cid=C0AN0CDAADC)) | 모든 발송 본문 끝 `HOTEL AT GANGNAM 드림`, 시작 `안녕하세요, HOTEL AT GANGNAM입니다.`(언어별 동일 의미) |
| M6 | 게스트 언어로 회신(JA/EN 예), 원문은 스레드 | 수신 언어로 회신. Slack에는 한국어 요약 + `*[원문]*` |
| M7 | 발송 후 `*[지메일 발송]*` 내부 공유 ([9/13](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789277674249079?thread_ts=1789254214.074069&cid=C0AN0CDAADC)) | `*[문자발송]*` ([LOUNGE-FORMAT.md](./LOUNGE-FORMAT.md) §4) |
| M8 | 분실물 미확인 시 +2일 후 사과·미발견 종결 ([9/12](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789195472264909?thread_ts=1789168682.588469&cid=C0AN0CDAADC)) | 1차 문자: KB P2 안내 + `:loading:`(로그북 확인은 프런트). +2일 종결은 직원/철수가 `>>`로 |

## 2. 결정적 규칙 (LLM 전, 순서대로)

| ID | 조건 | 결과 |
| --- | --- | --- |
| R1 | 발신 = `HOTEL_SMS_NUMBER` | 게시만(`:loudspeaker:`), 자동회신 0 |
| R2 | OTP: `인증번호|인증 번호|승인번호|verification code|OTP|code is` + 4–8자리 숫자 | `:loudspeaker:` `*분류* = 인증번호` |
| R3 | 광고: 본문 `(광고)` 시작, `무료수신거부|수신거부 080|080-` 포함 | `:loudspeaker:` `*분류* = 광고` |
| R4 | 비휴대폰 발신: E.164가 `+8210/11/16/17/18/19` 아님(대표번호·단축·국제 발신 포함) **또는** `[Web발신]`+예약/결제/배송 알림 패턴 | `:loudspeaker:` `*분류* = 시스템` |
| R5 | 예약확정 알림(야놀자·여기어때·아고다·부킹 등 OTA 템플릿) | `:loudspeaker:` `*분류* = 시스템` |
| R6 | 자동응답 흔적: `자동응답|부재중|auto-reply|automated|(자동응답)` | 게시(`:loading:` 아님 → `:loudspeaker:`), 자동회신 0 (루프 방지) |
| R7 | 수신거부 요청: `STOP|수신거부|그만 보내|차단` (단독 또는 문장 전체 의도) | `sms:optout` 기록, 자동회신 0, `:loading:` |
| R8 | 긴급: `불이|화재|연기|119|응급|쓰러|갇혔|경찰|폭행|도와주세요` | `:rotating_light:` + `SMS_URGENT_MENTION`, 자동회신 0 |
| R9 | 휴대폰 발신 + 위 해당 없음 | §3 LLM |

R2–R5 패턴은 `lib/sms-classify.ts` 상수로, 테스트 fixture 실제 한국 문자 샘플(마스킹) ≥ 30개.

## 3. LLM 분석 (`generateObject`, AI SDK + Vercel AI Gateway)

입력: 본문, 같은 스레드 최근 10건(방향·시각·본문), KB 전문(§4), 현재 KST 시각. 시스템 프롬프트: **KB에 없는 사실 단정 금지, 가격·시간·규정은 KB 문구만, 모르면 escalate.**

출력 스키마:

```ts
{
  category: "faq" | "escalate" | "no_reply_needed",
  language: "ko" | "en" | "ja" | "zh" | "other",
  summaryKo: string,            // Slack *문의내용* / *이슈 요약*
  resolutionKo?: string,        // *해결 방안* (faq)
  escalateReasonKo?: string,    // *[확인 필요]* (escalate)
  usedPolicyIds: string[],      // faq면 1개 이상 필수
  replyText?: string            // faq면 필수, 서명 포함
}
```

- `faq`: KB만으로 완전히 답변 가능 → `replyText` 발송, parent `:완료:`.
- `escalate`: 환불·취소·변경·요금 협상·불만·파손·분실물 확인·예약 조회 필요·KB 밖 → 보류응답 템플릿(아래) 발송(번호당 24h 1회), parent `:loading:`.
- `no_reply_needed`: 감사·확인 인사·이모지만 → 발송 0, parent `:완료:`(직전 상태가 `:loading:`인 대화 중이면 유지).

보류응답 템플릿 (언어별 고정, LLM 생성 아님):

- ko: `안녕하세요, HOTEL AT GANGNAM입니다. 문의 확인했습니다. 프런트에서 확인 후 이 번호로 다시 안내드리겠습니다. HOTEL AT GANGNAM 드림`
- en: `Hello, this is HOTEL AT GANGNAM. We received your message and our front desk will get back to you at this number shortly. HOTEL AT GANGNAM`
- ja: `こんにちは、HOTEL AT GANGNAMです。お問い合わせを確認しました。フロントで確認のうえ、この番号へご連絡いたします。HOTEL AT GANGNAM`
- zh/other → en.

### 출력 검증 (실패 시 `faq` → `escalate`로 강등)

V1 `usedPolicyIds` 모두 KB에 존재 · V2 본문 끝 서명 · V3 URL은 `hotelat.com`만 · V4 전화번호는 `0507-1449-5442`만 · V5 금액(`₩|원|KRW`)은 사용한 정책에 있는 금액만 · V6 길이 ≤ 600자 · V7 언어 = 수신 언어 · V8 약속 금지어(`환불해 드리|무료로 취소|보상해`) 없음.

LLM 오류/타임아웃(15s) → `escalate` 경로.

## 4. 정책 KB (시드) — `lib/hotel-policy.ts`에 id·문구·출처로 그대로 옮긴다

| id | 내용 | 출처 |
| --- | --- | --- |
| P1 | 서명 「HOTEL AT GANGNAM 드림」, 개인 이름 금지 | M5 링크 |
| P2 | 분실물: 택배·우편 발송 불가, 호텔 직접 수령만. 프런트가 분실물 로그북 확인 후 안내(발견 여부 단정 금지) | [9/11](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789095715853219?thread_ts=1789093540.997159&cid=C0AN0CDAADC), [로그북](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789095772964809?thread_ts=1789093540.997159&cid=C0AN0CDAADC) |
| P3 | 두고 간 물건 폐기 요청: 폐기 처리 안내 + 클로징 (lounge 공유) | [9/13](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789270445241489?thread_ts=1789254214.074069&cid=C0AN0CDAADC) |
| P4 | 엘리베이터에서 먼 객실 요청: 모든 객실이 락커를 경유하는 구조라 엘리베이터와 떨어져 있음 | [9/13](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789270466049329?thread_ts=1789254214.074069&cid=C0AN0CDAADC) |
| P5 | 조용한 객실 요청: 가능하면 조용한 구역 배정 노력, 당일 상황에 따라 변동 | [9/13 발송본](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789276987368769?thread_ts=1789254214.074069&cid=C0AN0CDAADC) |
| P6 | 짐 보관: 체크인 당일·투숙 중 1F 셀프 스토리지. 캐리어 배송은 프런트까지 오면 수령, 직원은 1F 셀프 스토리지 보관까지만 | [9/11](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789132816968109?thread_ts=1789132543.693449&cid=C0AN0CDAADC) |
| P7 | 하단(아래칸) 변경: 당일 공실 시 +₩10,000 (확정은 현장) | [10/7](https://venditinc.slack.com/archives/C0AN0CDAADC/p1791328894941799?thread_ts=1791327850.323169&cid=C0AN0CDAADC) |
| P8 | 체크인 15:00부터, 24시간 무인 셀프 체크인 → 늦은 밤·새벽·자정 도착 가능 | [hotelat.com FAQ](https://hotelat.com/), [10/5](https://venditinc.slack.com/archives/C0AN0CDAADC/p1791192091031319) |
| P9 | 위치: 서울 서초구 사평대로53길 27-6. 신논현역(9호선·신분당선) 1번 출구 380m 도보 5분, 논현역 7분, 강남역 10분. 인천공항 리무진 6703 신논현역 하차 | [hotelat.com](https://hotelat.com/) |
| P10 | 남녀 층 분리: 3층 여성 전용, 4층 남성 전용. 객실 타입 스탠다드 캡슐·디럭스 더블 캡슐 | [hotelat.com](https://hotelat.com/) |
| P11 | 객실 내부 라커(26인치 캐리어 수납), 개별 조명·콘센트·USB, 무료 와이파이, 투숙객 카페 음료 30% 할인 | [hotelat.com](https://hotelat.com/) |
| P12 | 대표번호 0507-1449-5442, 웹 https://hotelat.com/ | [hotelat.com](https://hotelat.com/) |
| P13 | 환불·잔여일 방 변경·취소수수료 면제: 프런트 단독 결정 불가 → **항상 escalate** (OTA 예약은 플랫폼 경유) | [9/13 401호](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789254214074069), [9/19](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789786032180629?thread_ts=1789786032.180629&cid=C0AN0CDAADC) |

KB 밖(퇴실 시각, 주차, 조식, 요금, 연장 가능 여부 등)은 **escalate**. 새 정책은 lounge Jason 지시 permalink와 함께 KB에 추가하는 PR로만.

## 5. 가드 (LLM 후, 발송 전 — 하나라도 걸리면 발송 0)

| ID | 가드 |
| --- | --- |
| G1 | `SMS_AUTO_REPLY_MODE`: `off` → 0, `shadow` → 초안 게시만, `on` → 발송 |
| G2 | stage `qa`: 수신번호 ∈ `SMS_QA_ALLOWED_NUMBERS` 일 때만 |
| G3 | 직원 발신(슬랙 `>>`·호텔폰 직접) 30분 이내 번호 → 0 (직원이 대화 중) |
| G4 | 번호당: 자동회신 ≤ 3건/24h, 직전 자동회신 후 ≥ 2분, 보류응답 ≤ 1건/24h |
| G5 | 전체 ≤ 100건/일 → 초과 시 0 + lounge `:loading:` 경고 1회/일 |
| G6 | 같은 번호 같은 본문 10분 내 3회 이상 → 0 (스팸/루프) |
| G7 | 수신 이벤트당 `SET sms:ar:evt:{key} NX` 성공 시에만 → 최대 1회 |
| G8 | `sms:optout` 번호 → 0 |
| G9 | 발신 이벤트(`outbound`/`send_result`)는 자동회신 트리거 아님 — 코드 경로 분리 |

## 6. 운영 스위치

- 끄기: `SMS_AUTO_REPLY_MODE=off` (redeploy 불필요하게 KV `sms:config:mode` 우선 — Slack 관리자 커맨드는 P1).
- 김철수 메일 루틴과 충돌 없음: 문자는 막내 봇 단독. 철수 루틴은 `[문자수신]` parent 상태(`:loading:`)만 새벽 전달사항에 포함.

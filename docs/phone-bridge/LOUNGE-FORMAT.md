# lounge 메시지 포맷 스펙 — 문자

`#lounge-호텔앳강남`(`C0AN0CDAADC`)의 기존 메일 핸드오프 형식을 그대로 따른다. 게시자는 막내 봇(`chat.postMessage` `username` = 「앳강남 막내」, `icon_emoji` `:bellhop_bell:`).

## 근거 (Jason 지시)

| 규칙 | 출처 |
| --- | --- |
| 태그 `*[메일수신]*` + `*필드* = 값` 줄, 원문이 외국어면 스레드에 원문, 내부 ID(Gmail thread 등) 금지 | [9/11 12:07](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789096051664989?thread_ts=1789093540.997159&cid=C0AN0CDAADC) |
| 상태 이모지는 **태그 옆에만**, 프런트 확인 필요 = 진행중, 공유 = 공유 딱지, 완료 = 완료 딱지, 바뀌면 갱신 | [9/11 12:07](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789096051664989?thread_ts=1789093540.997159&cid=C0AN0CDAADC), [12:35](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789097703423949?thread_ts=1789093540.997159&cid=C0AN0CDAADC), [12:21](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789096919107999?thread_ts=1789093540.997159&cid=C0AN0CDAADC) |
| 헤드(필드명)는 볼드 | [9/11 22:27](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789133236537659?thread_ts=1789132543.693449&cid=C0AN0CDAADC) |
| 발송 공유는 `*[지메일 발송]*` + `*이름*님 by *경로*` + `*이슈 요약*` / `*해결 방안*` / `*발송 본문*` | [9/13 14:34](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789277674249079?thread_ts=1789254214.074069&cid=C0AN0CDAADC) |
| 수신 트리아지 태그 `*[메일수신]*` / `*[문자수신]*` / `*[채널톡]*` 유지 | [9/13 14:37](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789277867363349?thread_ts=1789254214.074069&cid=C0AN0CDAADC) |
| 실제 예: `[메일수신] :loading:` + 채널/투숙객명/수신일시/투숙기간/투숙타입/문의내용 + `[메일회신]` | [9/21 12:31](https://venditinc.slack.com/archives/C0AN0CDAADC/p1789961482493549?thread_ts=1789961183.852709&cid=C0AN0CDAADC) |

## 1. 상태 이모지 (태그 바로 뒤, 1개만)

| 이모지 | 의미 | 언제 |
| --- | --- | --- |
| `:loading:` | 프런트 확인 필요 | 답장 필요 + 자동회신으로 해결 안 됨(에스컬레이션, 보류응답만 보냄, LLM 실패, 발송 실패/불명) |
| `:완료:` | 처리 완료 | FAQ 자동회신으로 해결, 직원 답장 발송 성공, 답장 불필요(감사 인사 등), 직원이 `:완료:` reaction |
| `:loudspeaker:` | 공유(조치 불필요) | OTP/인증번호, 광고, 시스템·대표번호 알림, 예약확정 알림 |
| `:rotating_light:` | 긴급 | 화재·응급·폭력·갇힘 등 ([AUTO-REPLY.md](./AUTO-REPLY.md) §3) — `SMS_URGENT_MENTION` 함께 |

상태는 parent를 `chat.update`로 바꾼다(새 메시지 금지). 같은 스레드에 새 수신이 오고 답장이 필요하면 `:loading:`으로 되돌린다.

## 2. 수신 parent — `*[문자수신]*`

번호당 스레드 1개. 같은 번호의 마지막 활동이 72시간 이내면 기존 스레드에 이어 붙인다(§3), 아니면 새 parent.

```
*[문자수신]* :loading:
*발신번호* = 010-1234-5678
*수신일시* = 2026년 10월 7일 11:02 (KST)
*문의내용* = 오늘 체크인 전에 짐 맡길 수 있나요?
```

- `*투숙객명*` / `*투숙기간*` / `*투숙타입*` 은 값이 확인될 때만 넣는다(P1 PMS 연동). 모르면 줄 자체를 생략 — "확인 필요"로 채우지 않는다.
- `*문의내용*` = 한국어. 원문이 외국어면 한국어 요약/번역을 넣고, 스레드 첫 답글에 `*[원문]*` + 원문 그대로.
- 본문 1,500자 초과 시 `*문의내용*`은 앞부분 + `… (전문은 스레드)`, 스레드에 `*[전문]*`.
- MMS 첨부: parent 다음 스레드 답글로 파일 업로드(`files.getUploadURLExternal` → `files.completeUploadExternal`, `thread_ts`), `*문의내용*` 끝에 ` [사진 2장]`.
- 공유 분류는 `*[문자수신]* :loudspeaker:` + `*분류* = 인증번호|광고|시스템` 줄 추가. OTP 본문은 그대로 보여준다(직원이 lounge에서 인증번호를 요청해 쓰는 기존 관행: [4/1](https://venditinc.slack.com/archives/C0AN0CDAADC/p1775031034037369?thread_ts=1775031034.037369&cid=C0AN0CDAADC)).
- 내부 ID(eventId, providerKey, outboxId, deviceId) 금지.

## 3. 같은 번호의 후속 수신 (스레드 답글)

```
*[문자수신]*
*수신일시* = 2026년 10월 7일 11:05 (KST)
*문의내용* = 그리고 주차도 되나요?
```

`reply_broadcast` 하지 않는다(채널 노이즈 방지). 대신 parent 상태를 갱신(§1).

## 4. 발신 (스레드 답글) — `*[문자발송]*`

모든 발신은 해당 번호 스레드에 남긴다. 스레드가 없으면(호텔 폰에서 먼저 보낸 경우) 이 블록이 parent가 된다.

자동회신:

```
*[문자발송]* :완료:
*수신번호* = 010-1234-5678
*발송경로* = 자동회신 (FAQ)
*이슈 요약* = 체크인 전 짐 보관 문의
*해결 방안* = 체크인 당일 1F 셀프 스토리지 보관 가능 안내
*발송 본문*
> 안녕하세요, HOTEL AT GANGNAM입니다. 체크인 당일과 투숙 중에는 1층 셀프 스토리지에 짐을 보관하실 수 있습니다. HOTEL AT GANGNAM 드림
*발송 상태* = 전달확인
```

- `*발송경로*` 값: `자동회신 (FAQ)` · `자동회신 (보류응답)` · `슬랙 (@이름)` · `호텔폰 직접`.
- 직원 발신(슬랙/호텔폰)은 `*이슈 요약*`/`*해결 방안*` 생략.
- `*발송 상태*` 값: `발송대기` → `발송완료` → `전달확인` / `실패 (사유)` / `확인 필요`. 같은 메시지를 `chat.update`로 갱신. 실패·확인 필요면 태그 옆 `:loading:` + parent도 `:loading:`.
- shadow 모드 초안: `*[문자발송 초안]* :loading:` + 동일 필드 + `*발송 상태* = 미발송 (shadow)`.
- 자동회신 생략은 게시하지 않는다(로그만). 단 에스컬레이션은 parent `:loading:` + 스레드 한 줄: `*[확인 필요]* <한국어 사유 한 문장>` (예: `무료 취소 요청 — 프런트 결정 필요`).

## 5. 직원 조작

| 조작 | 결과 |
| --- | --- |
| 스레드 답글 `>> 본문` | 그 번호로 발송, §4 블록 게시(`슬랙 (@이름)`), 원 메시지에 `:outbox_tray:` → 결과 후 `:white_check_mark:` / `:x:` |
| 접두어 없는 스레드 답글 | 내부 메모. 발송 없음 |
| parent에 `:완료:` reaction | parent 상태 `:완료:` |
| parent에 `:loading:` reaction | parent 상태 `:loading:` |

## 6. 금지

- 상태 이모지를 본문·필드 줄에 붙이기, parent 외 위치에 상태 표시.
- "Mail", "SMS" 같은 영문 태그, 내부 ID, 토큰, 전화번호 마스킹 없는 로그(로그는 뒤 4자리만, Slack은 전체 번호).
- 같은 이벤트로 2개 이상 Slack 메시지(첨부·원문 스레드 답글 제외).

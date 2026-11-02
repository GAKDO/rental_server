# 기능 명세: RE1 렌탈·기기·기종·시점

**브랜치**: `claude/project-thread-je1x8b` (Spec Kit 기본 `feature/001-rental-device-checkpoint` 대신 세션 지정 브랜치)
**작성**: 2026-11-02
**상태**: 확정(clarify 반영)
**요구사항**: FR-RNT-01, FR-RNT-02, FR-RNT-03, FR-RNT-05 (목록 범위로 FR-USR-03 일부)
**계약**: GAKDO/contracts develop `openapi/rental-server.yaml` 0.1.0(PR #6 PublicId 반영), `codes.yaml` 0.4.0, `events/` 1.0. 후속(11/2): contracts PR #8(develop c0dfcba) — 기기 상태 전이 규칙, `codes.yaml` 0.6.0
**입력(backlog.md)**: "관리자가 기종(제조사·형태·치수·기준 사양)과 기기(시리얼·상태)를 관리하고, 렌탈 건을 만들어 기기를 연결한다. 렌탈은 출고·수령·반납 세 시점을 순서대로 가진다(FR-RNT-01, 02, 03, 05). 수용 기준: 같은 시리얼로 진행 중인 렌탈이 2건 생기지 않는다. 출고 전에 수령 기록을 만들면 거부된다. 모델명으로 3D 트윈 치수를 조회한다. 수리 중·폐기 기기로 렌탈을 만들면 409이고 폐기해도 행은 남는다. 시리얼은 등록 뒤 바꿀 수 없고 변경자(updated_by)를 남긴다. 기기 목록은 기종·상태로 찾는다. 스키마는 design/ddl.md의 rental 절이다."

## 사용자 시나리오와 테스트

### 이야기 1 — 기종과 기기 등록 (P1)

관리자가 기종(제조사·형태·치수·기준 사양)을 등록하고, 그 기종으로 기기(시리얼·OS·색상·자산번호)를 등록한다. 고객 화면은 기종 코드로 3D 트윈 치수를 읽는다.

**독립 시험**: 기종 PUT → 기기 POST → 기종 GET으로 치수가 나오는지 본다.

**수용 시나리오**
1. **주어진** 기종이 없을 때 **하면** `PUT /device-models/MBA13-M2` **그러면** 200과 치수·updatedAt이 온다. 같은 코드로 다시 PUT하면 값이 바뀐다.
2. **주어진** 기종 MBA13-M2 **하면** `GET /device-models/MBA13-M2` **그러면** widthMm·depthMm·baseHMm·lidHMm가 온다(모델명으로 3D 트윈 치수를 조회한다).
3. **주어진** 없는 기종 코드 **하면** 기기 등록 **그러면** 404 NOT_FOUND.
4. **주어진** 이미 등록된 시리얼 **하면** 같은 시리얼로 기기 등록 **그러면** 409 DEVICE_SERIAL_EXISTS.
5. **주어진** 등록된 기기 **하면** PATCH 본문에 serial이나 modelCode를 넣음 **그러면** 400 VALIDATION_FAILED, 시리얼은 그대로다(시리얼은 등록 뒤 바꿀 수 없다).
6. **주어진** 관리자 토큰 **하면** 기종 저장·기기 등록·수정 **그러면** updated_by에 토큰 sub가 남는다.

### 이야기 2 — 렌탈 생성과 기기 상태 (P1)

관리자가 ACTIVE 기기로 렌탈을 만든다. 같은 기기로 진행 중(OPEN) 렌탈은 하나뿐이다. 수리 중·폐기 기기는 렌탈로 나가지 않는다.

**수용 시나리오**
1. **주어진** ACTIVE 기기 **하면** `POST /rentals` **그러면** 201, 상태 OPEN, checkpoints `[]`, Location 헤더.
2. **주어진** 같은 기기에 OPEN 렌탈 **하면** 렌탈을 또 만듦 **그러면** 409 RENTAL_OPEN_EXISTS(같은 시리얼로 진행 중인 렌탈이 2건 생기지 않는다). 동시에 두 요청이 와도 하나만 성공한다(DB 부분 고유 인덱스).
3. **주어진** MAINTENANCE 또는 RETIRED 기기 **하면** 렌탈 생성 **그러면** 409 DEVICE_NOT_ACTIVE.
4. **주어진** 기기 **하면** PATCH status=RETIRED **그러면** 200, retiredAt이 채워지고 행은 남아 목록·조회에 계속 나온다(폐기해도 행은 남는다).
5. **주어진** OPEN 렌탈이 있는 기기 **하면** PATCH status=MAINTENANCE·RETIRED **그러면** 409 DEVICE_HAS_OPEN_RENTAL.
6. **주어진** RETIRED 기기 **하면** PATCH status=ACTIVE **그러면** 409 DEVICE_STATUS_TRANSITION_INVALID, 상태·retiredAt은 그대로다. (11/2 결정)
7. **주어진** RETIRED 기기 **하면** PATCH status=MAINTENANCE **그러면** 200, retiredAt이 비워진다. 점검 뒤 MAINTENANCE → ACTIVE로 다시 렌탈할 수 있다. (11/2 결정)

### 이야기 3 — 세 시점 순서 (P1)

렌탈은 출고(OUT) → 수령(RCV) → 반납(RET) 시점을 순서대로 가진다. 앞 시점이 봉인(SEALED)되어야 다음 시점을 시작한다. 반납이 봉인되면 렌탈은 RETURNED가 되고 관리자가 종료(CLOSED)한다.

**수용 시나리오**
1. **주어진** 시점이 없는 OPEN 렌탈 **하면** `POST /checkpoints {type: OUT}` **그러면** 201, 시점 OPEN, 렌탈 startedAt이 채워진다.
2. **주어진** 출고 시점이 없거나 아직 봉인 전 **하면** RCV 시작 **그러면** 409 CHECKPOINT_ORDER_VIOLATION(출고 전에 수령 기록을 만들면 거부된다).
3. **주어진** 이미 있는 종류 **하면** 같은 종류를 또 시작 **그러면** 409 CHECKPOINT_ORDER_VIOLATION.
4. **주어진** 시점 **하면** `trace.capture.uploaded`·`trace.checkpoint.graded`·`trace.record.sealed` 이벤트가 옴 **그러면** 시점 상태가 CAPTURED·GRADED·SEALED로 앞으로만 바뀐다. occurredAt은 촬영 완료(uploaded) 시각. 같은 이벤트가 두 번 와도 결과가 같다.
5. **주어진** RET 시점 봉인 이벤트 **그러면** 렌탈이 RETURNED가 된다.
6. **주어진** RETURNED 렌탈 **하면** `POST /close` **그러면** 204, CLOSED, endedAt. RETURNED가 아니면 409 RENTAL_NOT_CLOSABLE.

### 이야기 4 — 기기·렌탈 목록 (P2)

관리자가 기기를 기종·상태·시리얼/자산번호 일부로 찾고, 렌탈을 상태로 거른다. 고객은 내 렌탈만 본다.

**수용 시나리오**
1. **주어진** 기기 여러 대 **하면** `GET /devices?modelCode=&status=&q=` **그러면** 조건에 맞는 기기만 최근 등록 순으로 온다(기기 목록은 기종·상태로 찾는다).
2. **주어진** limit보다 많은 결과 **그러면** nextCursor가 오고, 그 커서로 다음 쪽을 겹침 없이 받는다. 마지막 쪽은 nextCursor null. 깨진 커서는 400.
3. **주어진** CUSTOMER 토큰(rentals 클레임) **하면** `GET /rentals` **그러면** 클레임에 있는 렌탈만, `GET /rentals/{id}`에서 클레임 밖이면 403 OUT_OF_SCOPE.

### 경계 상황
- CLOSED·RETURNED 렌탈에 시점을 시작하면 409 CHECKPOINT_ORDER_VIOLATION.
- 모르는 rentalId·checkpointId 이벤트는 경고 로그 후 ack(다시 와도 처리할 대상이 없음).
- 형식이 깨진 이벤트는 재시도하지 않고 `trace.dlx`로 보낸다.
- ~~RETIRED → ACTIVE로 되돌리면 retiredAt을 지운다~~ → 11/2 결정으로 바뀜: 폐기 기기는 MAINTENANCE로만 되돌리고 그때 retiredAt을 지운다(DB CHECK와 일치). RETIRED → ACTIVE는 409 DEVICE_STATUS_TRANSITION_INVALID.
- 요청 본문의 모르는 필드는 400(계약의 `additionalProperties: false`).

## 요구사항

- **FR-001** 기종을 modelCode로 등록·수정(PUT 업서트), 목록(전부), 단건(치수 포함) 조회한다. (FR-RNT-03)
- **FR-002** 기기를 시리얼·기종·OS로 등록한다. 상태는 ACTIVE로 시작. 시리얼 중복 409, 기종 없음 404. (FR-RNT-01)
- **FR-003** 기기는 색상·자산번호·OS·상태만 바꾼다. serial·modelCode를 보내면 400. RETIRED면 retiredAt을 채운다. OPEN 렌탈이 있는데 MAINTENANCE·RETIRED로 바꾸면 409. RETIRED → ACTIVE는 409 DEVICE_STATUS_TRANSITION_INVALID, RETIRED → MAINTENANCE는 retiredAt을 비운다. (FR-RNT-05)
- **FR-004** 기기를 지우는 API는 없다. 폐기해도 행이 남는다. (FR-RNT-05)
- **FR-005** 기종·기기를 바꾼 사람(JWT sub)을 updated_by에 남긴다.
- **FR-006** 기기 목록은 modelCode·status·q(시리얼·자산번호 일부, 대소문자 무시)로 거르고 커서로 나눈다. (FR-RNT-05)
- **FR-007** 렌탈은 ACTIVE 기기로만, 같은 기기에 OPEN 렌탈 하나만 만든다(서버 검사 + DB 부분 고유 인덱스). (FR-RNT-01)
- **FR-008** 시점은 OUT → RCV → RET 순서로, 앞 시점이 SEALED일 때만, 렌탈이 OPEN일 때만 시작한다. OUT을 시작하면 startedAt. (FR-RNT-02)
- **FR-009** rental-server는 자기 큐 `trace.rental.checkpoints`로 이벤트 3종을 받아 시점 상태를 앞으로만 바꾼다. RET 봉인이면 렌탈 RETURNED. 멱등. (FR-RNT-02)
- **FR-010** RETURNED 렌탈만 CLOSED로 종료하고 endedAt을 채운다. (FR-RNT-02)
- **FR-011** 렌탈 목록·조회는 ADMIN 전체, CUSTOMER는 rentals 클레임 안만. (FR-USR-03 일부)
- **FR-013** 밖에 보이는 ID는 publicId(NanoID 21자)만 쓰고 DB 기본 키(bigint)는 응답·커서·이벤트에 내보내지 않는다. PublicId 형식이 아닌 경로·본문 ID는 400. (헌법 1.1.0)
- **FR-012** 모든 오류는 Problem Details + codes.yaml `error_code`. 인증은 auth-server JWKS로 RS256 JWT를 검증하고 role 클레임으로 역할을 본다. ping은 로그인 없이 연다.

### 핵심 엔터티
- **기종(device_model)**: modelCode, 이름, 제조사, 형태, 기준 사양(JSON), 치수 4개.
- **기기(device)**: 시리얼(불변), 기종, OS, 색상, 자산번호, 상태, 폐기 시각.
- **렌탈(rental)**: 기기, renterRef, 상태(OPEN·RETURNED·CLOSED), 시작·종료 시각.
- **시점(checkpoint)**: 렌탈, 종류(OUT·RCV·RET), 상태(OPEN·CAPTURED·GRADED·SEALED), 촬영 완료 시각. 렌탈마다 종류별 1건.

## 성공 기준
- **SC-001** 수용 기준 문장마다 자동 테스트가 있고 `./mvnw verify`(JaCoCo 40%)가 통과한다.
- **SC-002** 같은 기기로 렌탈 생성 요청 2개를 동시에 보내도 OPEN 렌탈은 1건이다(Testcontainers PostgreSQL 시험).
- **SC-003** API 응답 형식이 contracts `rental-server.yaml`의 스키마·오류 code와 같다.

## 범위 밖
- 파일럿 등록(`/pilot/participants`)은 RE2.
- 시점의 촬영·등급·봉인 자체(capture-server·report-server), QR·페어링(auth-server).

## Clarifications

### Session 2026-11-02 (민서가 "묻지 말고 진행"을 지시해 추천안으로 확정, 최종 보고에 모아 알림)
- Q: 시점 상태(CAPTURED·GRADED·SEALED)를 rental-server가 어떻게 아나? → A: 이벤트 스키마는 그대로 두고 rental-server가 `trace.events`에 자기 큐 `trace.rental.checkpoints`를 붙여 `trace.capture.uploaded`·`trace.checkpoint.graded`·`trace.record.sealed`를 받는다. 소비자만 늘고 계약(스키마·라우팅 키)은 바뀌지 않는다. 설계서 이벤트 표의 소비 칸에 rental-server를 더한다.
- Q: RCV·RET 시작 조건은? → A: 계약 예시대로 앞 시점이 SEALED여야 한다.
- Q: RETURNED는 언제? → A: RET 시점이 봉인될 때(이벤트). close는 RETURNED만 CLOSED로 바꾼다. RETURNED는 OPEN이 아니므로 그 기기로 새 렌탈을 만들 수 있다.
- Q: 폐기 기기를 되살릴 수 있나? → A: 된다. ACTIVE로 바꾸면 retiredAt을 지운다(전용 오류 code가 계약에 없고 DB CHECK와 맞음).
- Q: 커서 형식은? → A: (createdAt, publicId) 내림차순 키셋을 base64url로(내부 id는 싣지 않음). 형식은 서버 내부이고 클라이언트는 불투명 문자열로 쓴다.

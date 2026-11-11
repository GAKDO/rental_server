# 구현 계획: RE1 렌탈·기기·기종·시점

**브랜치**: `claude/project-thread-je1x8b` | **날짜**: 2026-11-02 | **명세**: [spec.md](spec.md)

## 요약

contracts `rental-server.yaml`의 렌탈·기기·기종 경로 12개(파일럿 제외)를 Spring Boot로 만든다. rental 스키마는 GAKDO/docs `db/V1__init.sql`의 rental 절을 Flyway V1로 그대로 쓴다. 시점 상태는 `trace.events`에 rental-server 전용 큐를 붙여 이벤트 3종으로 앞으로만 바꾼다.

## 기술 맥락 (CLAUDE.md "계획에 줄 기술 설명")

Java 21, Spring Boot 3.5.7, Maven(./mvnw). Spring Web MVC, Spring Data JPA, PostgreSQL 스키마 rental, Flyway(src/main/resources/db/migration, 이 서버 스키마만, GAKDO/docs db/V1__init.sql의 해당 절에서 시작), Spring Security OAuth2 리소스 서버(auth-server JWKS 검증), Spring AMQP(trace.events), Bean Validation, ProblemDetail(RFC 9457). 테스트는 JUnit 5 + Spring Boot Test + Testcontainers(PostgreSQL·RabbitMQ), JaCoCo 라인 커버리지 40% 이상. Testcontainers 테스트는 `@Testcontainers(disabledWithoutDocker = true)`, 도메인 로직은 Docker 없이 도는 단위 테스트로도 덮는다. 패키지는 `com.gakdo.trace.rental` 아래 기능별. 기존 ping·application.yml·Dockerfile을 유지하고, 주소·비밀은 환경 변수(gitops infra.env, trace-secrets의 이름)로 받는다.

| 항목 | 값 |
| --- | --- |
| DB | `jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/trace`, 계정 `DB_USERNAME`·`DB_PASSWORD`, Flyway 스키마 `rental` |
| AMQP | `RABBITMQ_HOST`·`RABBITMQ_PORT`·`RABBITMQ_VHOST`·`RABBITMQ_USERNAME`·`RABBITMQ_PASSWORD` |
| JWKS | `AUTH_JWKS_URI`, 기본 `${AUTH_SERVER_URL}/.well-known/jwks.json`, iss `trace` |
| 큐 | `trace.rental.checkpoints` ← `trace.capture.uploaded`·`trace.checkpoint.graded`·`trace.record.sealed`, 실패는 `trace.dlx`(topic) 키 `trace.rental.checkpoints.dead` → `trace.rental.checkpoints.dlq` |

## 헌법 점검

| 원칙 | 판단 |
| --- | --- |
| I 계약 먼저 | OpenAPI·codes.yaml·이벤트 스키마를 그대로 따른다. 이벤트 소비자를 하나 더하는 것은 스키마·라우팅 키를 바꾸지 않는다(설계서 이벤트 표의 소비 칸만 갱신, decisions.md 기록). 통과 |
| II 서비스 경계 | rental 스키마만 쓴다. 다른 서비스와는 이벤트로만. 통과 |
| III 기록 불변 | 기기·렌탈 행을 지우는 API 없음. 시점 상태는 앞으로만. 통과 |
| IV 테스트 | 수용 기준 문장을 `@DisplayName`으로. 단위 + WebMvc + Testcontainers. 통과 |
| V 비밀 | 비밀은 환경 변수만. JWKS로 JWT 검증. 통과 |
| VI 단순 | 파일럿은 RE2로 미룸. 추상화 없이 기능 패키지 3개. 통과 |

## 구조

```
src/main/java/com/gakdo/trace/rental/
  common/   api(ProblemAdvice) · domain(ErrorCode, DomainException) · infra(UuidV7, Cursor, Times, SecurityConfig, CurrentUser)
  devicemodel/ api · application · domain · infra
  device/      api · application · domain · infra
  rental/      api · application · domain(Rental, Checkpoint, 순서 규칙) · infra(CheckpointEventListener, AmqpConfig)
src/main/resources/db/migration/V1__init_rental.sql
```

## 설계 결정 (research)

0. **ID (11/2 변경, 헌법 1.1.0)**: 기본 키는 DB가 매기는 bigint `id`(IDENTITY)이고 밖에 내보내지 않는다. API의 deviceId·rentalId·checkpointId, JWT sub·rentals, 이벤트 봉투의 ID는 `public_id`(NanoID 21자, 앱에서 생성, contracts `PublicId`). 기종은 public_id가 없고 modelCode로 찾는다. updated_by는 계정 publicId(varchar 21).

1. **순서 규칙은 도메인 객체에**: `Rental.startCheckpoint(type)`가 OUT→RCV→RET, 앞 시점 SEALED, 렌탈 OPEN을 검사한다. DB `UNIQUE(rental_id, type)`가 동시 요청의 마지막 방어선.
2. **진행 중 렌탈 하나**: 서버 검사 + `ux_rental_open_device` 부분 고유 인덱스 위반을 409 RENTAL_OPEN_EXISTS로 바꾼다. 기기 행은 `SELECT … FOR UPDATE`로 잠가 상태 변경과 렌탈 생성이 엇갈리지 않게 한다.
3. **시리얼 불변**: 요청 본문의 모르는 필드는 Jackson `FAIL_ON_UNKNOWN_PROPERTIES=true`로 400. PATCH는 null(지우기)과 생략(그대로)을 나누려고 JsonNode로 읽는다. 엔티티에 serial setter가 없다.
4. **커서**: `(createdAt, publicId)` 내림차순 키셋, base64url. 내부 순번 id는 커서에도 싣지 않고, 같은 시각이면 publicId로 순서를 정한다.
5. **인증**: 리소스 서버가 JWKS로 RS256 검증, `role` 클레임 → `ROLE_*`. CUSTOMER는 `rentals` 클레임으로 거른다. 401·403도 Problem Details.
6. **이벤트 소비**: JsonNode로 필요한 키만 읽고 모르는 필드는 무시. 상태는 앞으로만 바꿔 멱등. 형식 오류·계속 실패는 재시도 3번 뒤 DLX.
7. **시각**: UTC `OffsetDateTime`, 마이크로초로 잘라 DB와 응답이 같게.

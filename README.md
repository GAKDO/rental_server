# rental-server

TRACE(노트북 렌탈 상태 기록 서비스)의 **rental-server**: 렌탈·기기·기종·시점·파일럿 참여자

- 담당: A
- 설계: [GAKDO/docs](https://github.com/GAKDO/docs) — 요구사항·아키텍처·ERD·REST API v0
- 기술: Java 21, Spring Boot 3.5.7, Maven

## 실행

```bash
./mvnw spring-boot:run
```

```bash
curl http://localhost:8080/api/v1/rentals/ping
```

## API

계약 원본: GAKDO/contracts `openapi/rental-server.yaml`. 아래는 요약입니다.

| 경로 | 하는 일 |
|---|---|
| `/api/v1/device-models` | 기종 목록·등록·수정(치수 포함) |
| `/api/v1/devices` | 기기 목록·등록·수정 |
| `/api/v1/rentals` | 렌탈 열기·목록·상세·수정. 부스는 `GET /api/v1/rentals?status=OPEN`으로 검사 기다리는 기기를 읽음 |
| `POST /api/v1/rentals/{rentalId}/checkpoints`, `POST /api/v1/rentals/{rentalId}/close` | 출고·수령·반납 시점 시작, 렌탈 종료 |
| `POST /api/v1/rentals/{rentalId}/booth-code`, `POST /api/v1/rentals/booth-codes/redeem` | 일회용 6자리 부스 코드 만들기(관리자), 부스가 코드로 기기 고르기 |
| `GET /api/v1/catalog/devices` | 로그인 없이 보는 보유 노트북 목록 |
| `/api/v1/pilot/participants` | 파일럿 참여자 |

시점 상태는 `OPEN → CAPTURED → GRADED → SEALED`로 바뀝니다. `checkpoint.started`를 발행하고, `capture.uploaded`·`checkpoint.graded`·`record.sealed`를 받아 상태를 옮깁니다. 삭제 API는 시험 데이터(`TEST-` 시리얼)만 지웁니다.

환경 변수: `POSTGRES_HOST`·`POSTGRES_PORT`·`POSTGRES_DB`, `DB_USERNAME`·`DB_PASSWORD`(`rental` 스키마), `RABBITMQ_HOST`·`RABBITMQ_PORT`·`RABBITMQ_VHOST`·`RABBITMQ_USERNAME`·`RABBITMQ_PASSWORD`, `AUTH_JWKS_URI`(또는 `AUTH_SERVER_URL`).

## 테스트

```bash
./mvnw verify
```

줄 커버리지 40% 미만이면 실패합니다(블로그와 같은 기준).

통합 테스트 `RentalIntegrationTest`(실제 PostgreSQL·RabbitMQ로 렌탈·시점 흐름 끝까지)는 Docker가 있어야 돕니다. Docker가 없으면 건너뜁니다(`@Testcontainers(disabledWithoutDocker = true)`, 실패가 아님). auth·diagnostics와 달리 `TRACE_IT_LOCAL` 로컬 DB 모드는 없습니다. GitHub Actions에서는 모두 돌고, 나머지 단위·컨트롤러 테스트는 Docker 없이 늘 돕니다. 테스트마다 한국어 이름(`@DisplayName` 또는 한국어 메서드 이름)과 `[상황]`·`[기대]` 주석이 달려 있습니다.

## CI/CD

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `backend-ci` | PR, develop push | 테스트 + JaCoCo 40% + (켜져 있으면) 학교 SonarQube |
| `배포` | PR(빌드만), develop·main push(`.md`만 바뀌면 건너뜀) | 이미지 → GHCR(`ghcr.io/<조직>/rental-server`, develop은 `:develop`). 지금 운영(https://gakdo.site, 학교 서버 Compose)은 gitops `compose/ops/autodeploy.sh`가 3분마다 `:develop`을 받아 이 서비스만 무중단으로 바꾼다. main이면 gitops `overlays/school` 이미지 태그도 바꾼다(클러스터 경로, 지금은 쓰지 않음) |
| `비밀값 검사` | PR, main·develop push | 학교 공용 비밀번호 패턴 + gitleaks |
| `release` | main에 CHANGELOG 버전 | 태그와 GitHub Release |
| `PR 알림`·`CI 결과 알림` | PR·CI 끝 | Discord·텔레그램 (변수로 켬) |

브랜치 규칙과 릴리스 절차는 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.

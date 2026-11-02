# 작업 목록: RE1

[P]는 서로 다른 파일이라 함께 할 수 있는 작업.

## 1단계 설정
- [x] T001 pom.xml에 data-jpa·flyway(+postgresql)·postgresql·amqp·oauth2-resource-server, 테스트에 spring-security-test·testcontainers(postgresql·rabbitmq) 추가
- [x] T002 application.yml에 datasource·flyway·jpa(validate, open-in-view off)·rabbitmq·jwks·Jackson 모르는 필드 거부 추가(기존 값 유지)
- [x] T003 `db/migration/V1__init_rental.sql` (docs V1 rental 절)

## 2단계 공통
- [x] T004 [P] `common.domain` ErrorCode(codes.yaml error_code 중 쓰는 값)·DomainException
- [x] T005 [P] `common.infra` UuidV7·Times·Cursor·CurrentUser
- [x] T006 `common.api` ProblemAdvice(400·404·409·500), SecurityConfig(역할×경로, 401·403 Problem)

## 3단계 이야기 1 기종·기기 (P1)
- [x] T007 devicemodel domain·infra·application·api (PUT 업서트, 목록, 단건)
- [x] T008 device domain·infra·application·api (등록, PATCH, 목록 필터·커서)
- [x] T009 [P] 단위 테스트: Device 상태 전이·retiredAt, DeviceUpdate 파싱(serial·modelCode 400)
- [x] T010 [P] WebMvc 테스트: 기종·기기 컨트롤러 계약 형식, 권한

## 4단계 이야기 2·3 렌탈·시점 (P1)
- [x] T011 rental domain: Rental·Checkpoint, 순서 규칙, close, 이벤트 상태 반영
- [x] T012 rental application·api: 생성(잠금·409 두 종류), 조회·목록(CUSTOMER 범위), 시점 시작, 종료
- [x] T013 rental infra: AmqpConfig(큐·바인딩·DLX), CheckpointEventListener
- [x] T014 [P] 단위 테스트: 순서 규칙 수용 기준, 이벤트 멱등
- [x] T015 [P] WebMvc 테스트: 렌탈 컨트롤러, OUT_OF_SCOPE

## 5단계 통합
- [x] T016 Testcontainers(PostgreSQL·RabbitMQ) 통합 테스트: Flyway·validate, 동시 렌탈 생성 1건, 폐기 후 행 유지, 이벤트로 SEALED → RETURNED → close, 커서 페이지
- [x] T017 `./mvnw verify` 통과(JaCoCo 40%)

## 후속(11/2, contracts PR #8)
- [x] T018 ErrorCode에 DEVICE_STATUS_TRANSITION_INVALID(codes.yaml 0.6.0)
- [x] T019 Device 상태 전이: RETIRED → ACTIVE 409, RETIRED → MAINTENANCE 허용·retiredAt 비움
- [x] T020 단위·WebMvc·통합 테스트 두 경우

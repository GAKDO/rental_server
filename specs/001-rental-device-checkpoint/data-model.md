# 데이터 모델: RE1

원본은 GAKDO/docs `design/ddl.md` rental 절(Flyway `V1__init_rental.sql`로 그대로 옮김). 이 기능이 쓰는 테이블과 규칙만 적는다.

모든 테이블의 기본 키 `id`는 bigint IDENTITY이고 밖에 내보내지 않는다. 기기·렌탈·시점·파일럿 참여자는 `public_id`(NanoID 21자)를 API·이벤트에 쓴다(11/2 결정).

| 테이블 | 엔티티 | 규칙 |
| --- | --- | --- |
| `rental.device_model` | `DeviceModel` | `model_code` 고유, PUT 업서트, `spec` jsonb `{cpu, ramGb, ssdGb}`, 치수 numeric(6,1)·(5,1) → 소수 한 자리로 반올림, `updated_by` |
| `rental.device` | `Device` | `serial` 고유·불변, 상태 ACTIVE·MAINTENANCE·RETIRED, `(status = RETIRED) = (retired_at IS NOT NULL)`, 삭제 없음, `updated_by` |
| `rental.rental` | `Rental` | 상태 OPEN·RETURNED·CLOSED, `ux_rental_open_device`(device_id WHERE status='OPEN') |
| `rental.checkpoint` | `Checkpoint` | 종류 OUT·RCV·RET, 상태 OPEN·CAPTURED·GRADED·SEALED, `UNIQUE(rental_id, type)` |
| `rental.pilot_participant` | (RE2) | 테이블만 만든다 |

## 상태 전이

```
렌탈:  OPEN --(RET 시점 SEALED 이벤트)--> RETURNED --(POST /close)--> CLOSED
시점:  OPEN --capture.uploaded--> CAPTURED --checkpoint.graded--> GRADED --record.sealed--> SEALED (앞으로만, 건너뛰기 허용)
시작:  OUT(시점 없음) → RCV(OUT SEALED) → RET(RCV SEALED), 렌탈 OPEN일 때만
기기:  ACTIVE ⇄ MAINTENANCE ⇄ RETIRED, ACTIVE → RETIRED (RETIRED → ACTIVE는 409 DEVICE_STATUS_TRANSITION_INVALID, OPEN 렌탈 있으면 MAINTENANCE·RETIRED 불가, RETIRED면 retired_at)
```

## API ↔ 테이블

| 응답 필드 | 출처 |
| --- | --- |
| `deviceId`·`rentalId`·`checkpointId` | 각 테이블 `public_id` |
| `Device.modelCode`·`modelName` | `device_model.model_code`·`name` |
| `RentalSummary.serial`·`modelCode` | `device.serial`, `device_model.model_code` |
| `Rental.checkpoints[]` | `checkpoint` 행(OUT·RCV·RET 순) |
| `Checkpoint.occurredAt` | `checkpoint.occurred_at`(촬영 완료 = capture.uploaded의 occurredAt) |

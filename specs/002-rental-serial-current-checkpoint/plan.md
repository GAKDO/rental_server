# 계획: 렌탈 상세 serial, 목록 currentCheckpoint

## 계약 (contracts)

- `openapi/rental-server.yaml`
  - `Rental.properties.serial` (string, 선택)
  - `RentalSummary.properties.currentCheckpoint` (`oneOf: [Checkpoint, null]`, 선택). 상세의 `Checkpoint` 스키마를 그대로 쓴다
  - 예시(생성 201·상세·목록)와 목록 설명 한 줄
- `CHANGELOG.md` `[Unreleased] > 추가`에 한 줄. `info.version`은 다음 릴리스 PR에서 올린다
- 검사: `yamllint`, `gen_openapi_codes.py --check`, `redocly lint`

## 서버 (rental_server)

- `RentalDtos.RentalResponse`에 `serial` = `device.serial`(생성·상세 경로 모두 기기를 이미 읽음)
- `Rental.currentCheckpoint()`: 시작된 시점 중 type이 가장 뒤인 것
- `RentalDtos.RentalSummaryResponse`에 `currentCheckpoint`
- 목록 N+1 방지: `Rental.checkpoints`에 `@BatchSize(100)`, `RentalService.list`가 트랜잭션 안에서 시점을 읽어 둔다
  (컬렉션 fetch join + limit은 메모리 페이징이 되어 쓰지 않음, `open-in-view: false`)
- 테스트: 도메인(지금 시점), API(상세 serial, 목록 currentCheckpoint·null), 통합(실제 DB로 상세·목록)

## 화면 (web, C)

- 계약 사본 갱신 후 `openapi-typescript` 재생성. 두 필드는 선택이라 `?`로 나온다

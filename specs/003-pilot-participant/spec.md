# RE2 파일럿 참여자 동의·별칭 등록

백로그: RE2 · 요구사항: FR-PIL-01 · 계약: contracts `rental-server.yaml` `POST /api/v1/pilot/participants`(변경 없음)

## 수용 기준

- 관리자만 별칭(P01~P999)·기기·동의 시각으로 등록한다. 실명은 받지도 저장하지도 않는다(실명과 측정값이 같은 테이블에 없다).
- 같은 별칭은 409 PILOT_ALIAS_EXISTS(동시 등록도 UNIQUE로 하나만), 없는 기기는 404, 지금보다 뒤의 동의 시각은 400.
- 고객·부스 토큰은 403, 토큰 없으면 401.
- 테이블은 V1의 `rental.pilot_participant`를 그대로 쓴다(새 마이그레이션 없음). 파일럿 참여 기기는 시험 데이터 삭제에서 이미 보호된다.

## 테스트

PilotApiTest(권한·형식·409), PilotServiceTest(저장 값·거부 사유).

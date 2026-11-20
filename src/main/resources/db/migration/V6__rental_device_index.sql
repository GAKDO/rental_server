-- ERD 점검(11/20): rental.rental.device_id는 FK인데 인덱스가 OPEN 렌탈만 덮는 부분 인덱스(ux_rental_open_device)뿐이다.
-- 공개 기기 목록(기기별 렌탈 수, 끝난 시점 모으기)과 기기 삭제 확인이 닫힌 렌탈까지 device_id로 찾으므로 전체 인덱스를 둔다.
-- 칸·데이터는 바꾸지 않으므로 이전 버전 코드와 함께 돌아도 문제없다.
CREATE INDEX IF NOT EXISTS ix_rental_device ON rental.rental(device_id);

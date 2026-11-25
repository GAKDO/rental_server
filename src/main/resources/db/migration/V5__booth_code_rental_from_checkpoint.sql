-- 정규화(11/20 ERD 점검): booth_code.rental_id를 더는 쓰지 않는다.
-- 시점이 렌탈을 정하므로(checkpoint_id → checkpoint.rental_id) 같은 사실을 두 번 적으면 서로 어긋날 수 있다(3NF 이행 종속).
-- 헌법 "삭제는 다음 배포": 이번에는 NULL만 허용해 새 코드(칸을 안 채움)와 이전 코드(칸을 채움)가 모두 돌게 하고,
-- 칸은 이 버전이 배포된 다음 마이그레이션에서 지운다.
ALTER TABLE rental.booth_code ALTER COLUMN rental_id DROP NOT NULL;
-- 같은 렌탈의 안 쓴 코드를 시점으로 찾는다(이전에는 rental_id 인덱스로 찾았다)
CREATE INDEX ix_booth_code_checkpoint ON rental.booth_code(checkpoint_id);

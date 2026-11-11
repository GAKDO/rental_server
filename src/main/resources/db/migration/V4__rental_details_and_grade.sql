-- contracts #23(11/10 민서 결정): 관리자 화면 요청 4건 중 서버 칸.
-- 1) 렌탈에 고객 이름·반납 예정일·메모(모두 선택). 이미 있는 렌탈(진행 중 X391 포함)은 NULL로 남고 상태·시점은 그대로다.
ALTER TABLE rental.rental ADD COLUMN customer_name varchar(64);
ALTER TABLE rental.rental ADD COLUMN due_at date;
ALTER TABLE rental.rental ADD COLUMN note varchar(500);

-- 2) 시점의 마지막 등급(trace.checkpoint.graded의 grade). "문제 있는 검사만 보기"(지금 시점 등급 B·C·D) 거르기에 쓴다.
ALTER TABLE rental.checkpoint ADD COLUMN grade varchar(1) CHECK (grade IN ('A','B','C','D'));
CREATE INDEX ix_checkpoint_rental_grade ON rental.checkpoint(rental_id, grade);

-- 3) 이미 등급이 난 시점은 capture-server의 등급표에서 한 번 채운다. 같은 DB에 inspection 스키마가 있을 때만(학교 서버 Compose).
--    테스트 DB처럼 없으면 건너뛴다. 이후 등급은 이벤트로 받는다.
DO $$
BEGIN
  IF to_regclass('inspection.grade_result') IS NOT NULL THEN
    UPDATE rental.checkpoint c
       SET grade = g.grade
      FROM inspection.grade_result g
     WHERE g.checkpoint_id = c.public_id;
  END IF;
END
$$;

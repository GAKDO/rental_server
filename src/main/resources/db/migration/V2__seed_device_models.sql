-- 기종 시드 1: MacBook Air (M2, 2022). 근거 GAKDO/docs decisions.md 11/2.
-- 폭·깊이는 Apple 사양, 본체·상판 두께는 B 실측. 그램은 11/4 실측 뒤 V3로 넣는다.
-- spec은 계약 ModelSpec(cpu·ramGb·ssdGb) 키만 쓴다. 이미 같은 model_code가 있으면 건너뛴다.
INSERT INTO rental.device_model
  (model_code, name, manufacturer, form_factor, spec, width_mm, depth_mm, base_h_mm, lid_h_mm)
VALUES
  ('MBA13-M2', 'MacBook Air (M2, 2022)', 'Apple', 'CLAMSHELL', '{"cpu":"Apple M2"}'::jsonb,
   304.1, 215.0, 7.0, 4.0)
ON CONFLICT (model_code) DO NOTHING;

# 분석 (speckit-analyze · converge)

명세·계획·작업·코드를 대조한 결과입니다. 2026-11-02.

| 점검 | 결과 |
| --- | --- |
| 수용 기준 7개(backlog) ↔ 테스트 | 모두 `@DisplayName`에 문장 그대로: 진행 중 렌탈 2건 불가(동시 10건, 통합), 출고 전 수령 거부(단위·통합), 모델명으로 치수 조회(WebMvc·통합), 수리 중·폐기 409와 행 유지(단위·통합), 시리얼 불변·updated_by(단위·WebMvc·통합), 기종·상태 검색(WebMvc·통합) |
| 계약 경로 12개(파일럿 제외) ↔ 컨트롤러 | 12/12. 오류 code 10종이 codes.yaml error_code와 같음 |
| 응답 필드 ↔ 계약 스키마 | 필수 필드 모두, null 허용 필드는 null로, spec의 빈 키는 뺌, 내부 id 없음 |
| DB ↔ 엔티티 | Flyway V1(docs rental 절) + `ddl-auto: validate` 통합 시험 통과 |
| 헌법 1.1.0 | ID 전략(bigint + publicId), 서비스 경계, 비밀 환경 변수, 테스트 게이트 통과 |
| 커버리지 | 줄 90.5% (기준 40%) |
| 남은 것 | 파일럿(RE2). 이벤트 소비 큐 추가를 설계서 이벤트 표에 반영(docs) |

Converged: 위 표에서 고칠 항목 없음.

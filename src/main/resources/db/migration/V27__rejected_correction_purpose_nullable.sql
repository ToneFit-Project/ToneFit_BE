-- 교정 입력에서 purpose(목적) 제거 (PM 확정 2026-07 — 테스트 결과 품질 영향 없음).
-- 교정 요청·거절 회송에서 purpose 가 사라지므로 신규 rejected_correction row 는 purpose 없이 저장된다.
-- 컬럼은 과거 보존 데이터의 분석 축으로 남기고 NOT NULL 만 해제 (신규 row = NULL).
-- 생성(generation) 의 purpose 는 유지 — generation_metadata.purpose 무변경.

ALTER TABLE rejected_correction ALTER COLUMN purpose DROP NOT NULL;

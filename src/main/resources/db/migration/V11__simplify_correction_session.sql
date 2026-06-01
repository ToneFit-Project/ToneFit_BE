-- v0.5 스키마 — 교정 흐름 단순화 (후교정/재교정/구조교정/제목생성/편집 모두 제거).
-- correction_session 의 status enum 도 IN_PROGRESS / CONFIRMED 두 값만 남는다.
--
-- 운영 데이터 영향:
--  - 진행 중 in-flight 상태(STRUCTURING/RECORRECTING/FINALIZING/EDITING) 행은
--    더 이상 클라이언트가 진행시킬 단계가 없으므로 IN_PROGRESS 로 회수.
--  - STRUCTURE_REVIEW / DRAFT 행도 IN_PROGRESS 로 흡수 (단순 검토 대기 = 미확정 세션).
--  - CONFIRMED 행은 그대로.
-- 위 정규화 후 사라지는 컬럼(subject/ai_final/ai_subject/user_subject/final_prompt_ver_id/
-- recorrect_count/structure_corrected)을 drop. 사용자 송신 본문은 user_final 에 그대로 유지.

UPDATE correction_session
   SET status = 'IN_PROGRESS'
 WHERE status IN ('DRAFT', 'STRUCTURING', 'STRUCTURE_REVIEW', 'RECORRECTING', 'FINALIZING', 'EDITING');

ALTER TABLE correction_session
    DROP COLUMN subject,
    DROP COLUMN ai_final,
    DROP COLUMN ai_subject,
    DROP COLUMN user_subject,
    DROP COLUMN final_prompt_ver_id,
    DROP COLUMN recorrect_count,
    DROP COLUMN structure_corrected;

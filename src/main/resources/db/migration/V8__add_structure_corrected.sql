-- 선택적 구조 교정 단계 도입.
-- POST /corrections/structure 호출 시 본문 구조(문장 순서·결합·분할 등) 교정 결과를 저장.
-- null 이면 구조 교정 미사용 세션. 이후 단계(initial, recorrect, finalize) 에서 null 이 아니면
-- AI 호출 입력으로 structure_corrected 가 사용됨 (effectiveOriginal).

ALTER TABLE correction_session
    ADD COLUMN structure_corrected TEXT;

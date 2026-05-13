-- 세션 당 재교정 횟수 카운터.
-- PM 요청: /corrections/{id}/recorrect 는 세션당 최대 2회까지 허용.
-- 한도 초과 시 BE 가 429 RECORRECT_LIMIT_EXCEEDED 반환.
-- 카운터는 persistRecorrectResult (TX2) 에서 증가 — AI 실패 시 증가하지 않음 (재시도 시 한도 소진 안 됨).

ALTER TABLE correction_session
    ADD COLUMN recorrect_count INTEGER NOT NULL DEFAULT 0;

-- PM 요구사항 REQ-Agree 반영.
--  - 약관 종류 재정의: SERVICE(필수) / PRIVACY(필수) / ANALYTICS(필수) /
--                     MARKETING(선택) / AI_LEARNING(선택)
--    (기존 CONTENT_USAGE → AI_LEARNING 으로 이름 변경, ANALYTICS 는 필수 약관으로 유지)
--  - 선택 동의 철회: revoked_at 컬럼 추가 (FUNC-Ag-06)
--
-- IP / user-agent 는 PM 확인 결과 수집 불필요 → 컬럼 추가하지 않음.
--
-- 기존 row 처리:
--  - CONTENT_USAGE → AI_LEARNING 으로 단순 rename. 동일 (user, version) 조합에 이미 AI_LEARNING 이
--    있는 경우는 거의 없지만 안전을 위해 충돌 회피.
--  - ANALYTICS 는 enum 값 그대로 유지 (필수 격상 — DB 상 변경 없음, 코드 측 isRequired 만 true).
--  - 자체 가입자 0명이라 운영 데이터 영향 미미.

-- 1) revoked_at 컬럼 추가 (nullable)
ALTER TABLE user_terms_agreement
    ADD COLUMN revoked_at TIMESTAMP;

-- 2) CONTENT_USAGE 를 AI_LEARNING 으로 rename
--    AI_LEARNING row 가 이미 있으면 옛 CONTENT_USAGE row 는 건드리지 않고 둘 다 보존.
UPDATE user_terms_agreement
   SET terms_type = 'AI_LEARNING'
 WHERE terms_type = 'CONTENT_USAGE'
   AND NOT EXISTS (
       SELECT 1 FROM user_terms_agreement other
        WHERE other.user_id    = user_terms_agreement.user_id
          AND other.terms_type = 'AI_LEARNING'
          AND other.version    = user_terms_agreement.version
   );

-- is_guest / anonymous_token 레거시 컬럼 제거.
-- 익명 토큰/유저 폐지로 신규 익명 발급이 없고, 남은 익명 row(is_guest=true)는 로그인 불가·복구 불필요.
-- V10 과 동일 패턴: CHECK 재정의 전에 익명 user 와 연관 FK 데이터를 먼저 삭제해야 제약 위반을 피한다.

-- 1) 익명 user 와 연관 데이터 정리 (FK 자식부터 역순). refresh_tokens 는 V15 에서 이미 drop.
DELETE FROM event_log            WHERE user_id IN (SELECT id FROM users WHERE is_guest = true);
DELETE FROM correction_feedback  WHERE user_id IN (SELECT id FROM users WHERE is_guest = true);
DELETE FROM correction_session   WHERE user_id IN (SELECT id FROM users WHERE is_guest = true);
DELETE FROM user_terms_agreement WHERE user_id IN (SELECT id FROM users WHERE is_guest = true);
DELETE FROM generation_metadata  WHERE user_id IN (SELECT id FROM users WHERE is_guest = true);
DELETE FROM users WHERE is_guest = true;

-- 2) 기존 CHECK 제약 제거 (익명/정식 분기 기반)
ALTER TABLE users DROP CONSTRAINT users_kind_check;

-- 3) 레거시 컬럼 제거 (관련 UNIQUE 제약은 컬럼 DROP 시 함께 제거됨)
ALTER TABLE users DROP COLUMN is_guest;
ALTER TABLE users DROP COLUMN anonymous_token;

-- 4) 새 CHECK: 모든 user 는 정식 (email + provider + provider_id + nickname)
ALTER TABLE users
    ADD CONSTRAINT users_kind_check CHECK (
        email IS NOT NULL AND provider IS NOT NULL
        AND provider_id IS NOT NULL AND nickname IS NOT NULL
    );

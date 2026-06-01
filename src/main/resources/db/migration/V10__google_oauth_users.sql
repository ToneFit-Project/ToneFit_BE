-- v0.5 스키마 — Google OAuth 단일 인증으로 전환.
-- 자체 email/password 회원가입 제거, OAuth provider 정보(provider/provider_id) 추가.
-- 추가로 사용 안 하는 사용자 메타(industry/career_level)와
-- 일일 무료 reset 정책 사라진 last_used 제거.
-- 생성(Generation) 무료 한도도 BE 미관리 결정 → free_used 컬럼 제거.
--
-- 보존: nickname — Google 프로필의 표시 이름을 저장하기 위해 유지 (PM 요구사항 FUNC-Au-02 #2).
--
-- 운영 데이터 영향:
--  - 기존 정식 가입자(password_hash 기반)는 한 명도 없음(테스트 한 명) → drop 안전.
--  - 익명 사용자는 컬럼 의존 없음 → drop 영향 없음.
--  - prod 에 정식 가입자가 잠재해 있다면 미리 export 후 운영 결정 필요(현 시점엔 없음).

ALTER TABLE users
    ADD COLUMN provider     VARCHAR(16),
    ADD COLUMN provider_id  VARCHAR(255);

-- 기존 CHECK 제약(자체 로그인 기반) 제거
ALTER TABLE users DROP CONSTRAINT users_kind_check;

-- 사용 안 하는 컬럼 제거. nickname 은 보존.
ALTER TABLE users
    DROP COLUMN password_hash,
    DROP COLUMN industry,
    DROP COLUMN career_level,
    DROP COLUMN last_used,
    DROP COLUMN free_used;

-- 자체 로그인 폐지 정리: provider 없이 만들어진 기존 정식 계정(자체 signup 테스트 계정 등)은
-- Google OAuth 로 재가입해야 하므로 연관 데이터와 함께 삭제한다.
-- 이 단계가 없으면 아래 CHECK 추가 시, 방금 NULL 로 추가된 provider/provider_id 때문에
-- 기존 is_guest=false row 가 제약을 위반해 마이그레이션이 실패한다(SQLSTATE 23514).
-- fresh DB 에선 대상 0건이라 무해하고 멱등하다. FK 자식부터 역순으로 삭제.
DELETE FROM event_log            WHERE user_id IN (SELECT id FROM users WHERE is_guest = false);
DELETE FROM correction_feedback  WHERE user_id IN (SELECT id FROM users WHERE is_guest = false);
DELETE FROM correction_session   WHERE user_id IN (SELECT id FROM users WHERE is_guest = false);
DELETE FROM user_terms_agreement WHERE user_id IN (SELECT id FROM users WHERE is_guest = false);
DELETE FROM refresh_tokens       WHERE user_id IN (SELECT id FROM users WHERE is_guest = false);
DELETE FROM users WHERE is_guest = false;

-- 새 CHECK: 익명=anonymous_token, 정식=email + provider + provider_id + nickname
ALTER TABLE users
    ADD CONSTRAINT users_kind_check CHECK (
        (is_guest = true  AND anonymous_token IS NOT NULL) OR
        (is_guest = false AND email IS NOT NULL
                          AND provider IS NOT NULL
                          AND provider_id IS NOT NULL
                          AND nickname IS NOT NULL)
    );

-- (provider, provider_id) 조합 유일성. provider_id IS NULL 인 익명 행은 인덱스 대상 제외.
CREATE UNIQUE INDEX users_provider_unique
    ON users (provider, provider_id)
    WHERE provider_id IS NOT NULL;

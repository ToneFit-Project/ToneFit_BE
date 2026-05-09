-- =========================================================================
-- 사용자 / 인증
-- =========================================================================
CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    is_guest         BOOLEAN      NOT NULL DEFAULT false,
    anonymous_token  VARCHAR(255),
    email            VARCHAR(255),
    password_hash    VARCHAR(255),
    nickname         VARCHAR(64),
    industry         VARCHAR(64),
    career_level     VARCHAR(32),
    plan             VARCHAR(16)  NOT NULL DEFAULT 'FREE',
    free_used        INTEGER      NOT NULL DEFAULT 0,
    credit_balance   INTEGER      NOT NULL DEFAULT 0,
    last_used        DATE,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_anon_token_unique UNIQUE (anonymous_token),
    CONSTRAINT users_kind_check CHECK (
        (is_guest = true  AND anonymous_token IS NOT NULL) OR
        (is_guest = false AND email IS NOT NULL AND password_hash IS NOT NULL AND nickname IS NOT NULL)
    )
);

-- 단일 디바이스 정책: user당 refresh row 1개만 허용.
-- generateAndSaveTokens 의 check-then-act 경합으로 행이 2개 생기는 INSERT race 차단.
-- user_id UNIQUE 가 인덱스 역할 겸하므로 별도 비유니크 인덱스 생성 안 함.
CREATE TABLE refresh_tokens (
    id      BIGSERIAL    PRIMARY KEY,
    token   VARCHAR(512) NOT NULL UNIQUE,
    user_id BIGINT       NOT NULL UNIQUE REFERENCES users (id)
);

CREATE TABLE user_terms_agreement (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    terms_type  VARCHAR(32)  NOT NULL,
    version     VARCHAR(32)  NOT NULL,
    agreed      BOOLEAN      NOT NULL,
    agreed_at   TIMESTAMP    NOT NULL,
    CONSTRAINT user_terms_agreement_uk UNIQUE (user_id, terms_type, version)
);

CREATE INDEX idx_user_terms_user ON user_terms_agreement (user_id);

-- =========================================================================
-- 프롬프트
-- =========================================================================
CREATE TABLE prompt_version (
    id         BIGSERIAL    PRIMARY KEY,
    purpose    VARCHAR(64)  NOT NULL,
    version    VARCHAR(64)  NOT NULL,
    content    TEXT         NOT NULL,
    is_active  BOOLEAN      NOT NULL,
    created_at TIMESTAMP,
    CONSTRAINT uk_prompt_version_purpose_version UNIQUE (purpose, version)
);

-- =========================================================================
-- 교정 세션
-- =========================================================================
CREATE TABLE correction_session (
    id                    BIGSERIAL    PRIMARY KEY,
    user_id               BIGINT       NOT NULL REFERENCES users (id),
    initial_prompt_ver_id BIGINT       REFERENCES prompt_version (id),
    final_prompt_ver_id   BIGINT       REFERENCES prompt_version (id),
    receiver_type         VARCHAR(64),
    purpose               VARCHAR(64),
    subject               VARCHAR(255),
    protected_ranges      JSONB,
    status                VARCHAR(32)  NOT NULL,
    original              TEXT,
    ai_final              TEXT,
    user_final            TEXT,
    ai_subject            VARCHAR(255),
    user_subject          VARCHAR(255),
    created_at            TIMESTAMP,
    updated_at            TIMESTAMP
);

CREATE INDEX idx_correction_session_user ON correction_session (user_id);
CREATE INDEX idx_correction_session_status ON correction_session (status);

-- =========================================================================
-- 교정 피드백 (사용자 수락/거절 + AI 변경 항목)
-- =========================================================================
CREATE TABLE correction_feedback (
    id               BIGSERIAL         PRIMARY KEY,
    user_id          BIGINT            NOT NULL REFERENCES users (id),
    session_id       BIGINT            NOT NULL REFERENCES correction_session (id),
    "index"          INT               NOT NULL,
    start            INT               NOT NULL,
    "end"            INT               NOT NULL,
    original         TEXT              NOT NULL,
    corrected        TEXT              NOT NULL,
    reason           TEXT              NOT NULL,
    label            VARCHAR(32)       NOT NULL,
    confidence       DOUBLE PRECISION  NOT NULL,
    applied_rules    JSONB,
    action           VARCHAR(32),
    reason_primary   VARCHAR(64),
    reason_secondary VARCHAR(64),
    reason_text      VARCHAR(255),
    created_at       TIMESTAMP,
    updated_at       TIMESTAMP
);

CREATE INDEX idx_correction_feedback_session ON correction_feedback (session_id);
CREATE INDEX idx_correction_feedback_user    ON correction_feedback (user_id);

-- =========================================================================
-- BE 자동 발화 이벤트 (Amplitude 미러링 정본)
-- =========================================================================
CREATE TABLE event_log (
    id                BIGSERIAL    PRIMARY KEY,
    client_event_id   VARCHAR(64)  NOT NULL UNIQUE,
    user_id           BIGINT       NOT NULL REFERENCES users (id),
    event_type        VARCHAR(32)  NOT NULL,
    visit_session_id  VARCHAR(64)  NOT NULL,
    session_id        BIGINT       REFERENCES correction_session (id),
    properties        JSONB,
    created_at        TIMESTAMP    NOT NULL
);

CREATE INDEX idx_event_log_user    ON event_log (user_id);
CREATE INDEX idx_event_log_session ON event_log (session_id);
CREATE INDEX idx_event_log_type    ON event_log (event_type);

-- PM 요구사항 REQ-Extension FUNC-Ext-11 — 생성(초안) 호출 메타데이터.
--  - 초안 원문(제목·본문)은 저장하지 않음. 개인 식별·재구성 불가능한 메타데이터만 보존.
--  - AI 학습 활용(AI_LEARNING) 동의자에 한해 INSERT. 미동의자는 row 자체 없음.
--  - 90일 보존 후 자동 파기 (GenerationMetadataCleanup @Scheduled, 매일 0시).
--  - AI_LEARNING 동의 철회 시 해당 user row 즉시 삭제 (UserService.toggleTerms).
--  - 용도: PM 프롬프트 개선용 수동 분석 한정. 파인튜닝·외부 전송·자동 학습 미사용.
--
-- 측정(event_log / Amplitude, ANALYTICS 약관)과는 별개 — 보존정책·약관기준·목적이 다르므로 분리.

CREATE TABLE generation_metadata (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users (id),
    receiver_type  VARCHAR(32)  NOT NULL,
    purpose        VARCHAR(32)  NOT NULL,
    brief_length   INT          NOT NULL,
    result_length  INT,
    duration_ms    BIGINT,
    success        BOOLEAN      NOT NULL,
    created_at     TIMESTAMP    NOT NULL
);

-- 90일 파기 DELETE 의 범위 스캔 효율.
CREATE INDEX idx_generation_metadata_created_at ON generation_metadata (created_at);
-- 동의 철회 시 user 단위 삭제 효율.
CREATE INDEX idx_generation_metadata_user ON generation_metadata (user_id);

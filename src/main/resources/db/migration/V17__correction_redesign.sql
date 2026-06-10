-- v0.6 교정 도메인 재설계 (REQ-Correction / FUNC-Cor-01~07).
-- 교정 세션·피드백·히스토리·확정 흐름 제거 → 무상태 교정. 거절 구절만 별도 보존.
--
--  1) event_log 의 correction_session FK 제거 (이벤트는 더 이상 세션에 결합되지 않음)
--  2) correction_feedback / correction_session 테이블 제거 (교정 결과 미저장 — FUNC-Cor-06)
--  3) rejected_correction 신설 — AI_LEARNING 동의자의 거절 항목 90일 보존
--
-- ※ FE 비호환 재설계라 기존 교정 세션·피드백 데이터는 보존하지 않고 drop 한다.

-- 1) event_log.session_id 제거 (인덱스 → 컬럼 순). FK 제약은 컬럼 삭제와 함께 사라진다.
DROP INDEX IF EXISTS idx_event_log_session;
ALTER TABLE event_log DROP COLUMN IF EXISTS session_id;

-- 2) 교정 피드백·세션 테이블 제거 (feedback 이 session 을 참조하므로 feedback 부터)
DROP TABLE IF EXISTS correction_feedback;
DROP TABLE IF EXISTS correction_session;

-- 3) 거절 교정 항목 보존 테이블 (FUNC-Cor-06)
--    원문 구절 + AI 제안 교정문 + '의미 훼손 의심' 플래그(표시한 경우). 위치 오프셋은 보관하지 않는다.
--    거부는 사유 입력 없이 처리되며 meaning_damage_suspected 만 선택적으로 받는다(미표시 NULL).
--    AI_LEARNING(서비스 개선 분석) 동의자에 한해 INSERT. 90일 후 파기, 철회 시 즉시 삭제.
CREATE TABLE rejected_correction (
    id                       BIGSERIAL    PRIMARY KEY,
    user_id                  BIGINT       NOT NULL REFERENCES users (id),
    receiver_type            VARCHAR(32)  NOT NULL,
    purpose                  VARCHAR(32)  NOT NULL,
    label                    VARCHAR(32)  NOT NULL,
    original_phrase          TEXT         NOT NULL,
    corrected_phrase         TEXT         NOT NULL,
    meaning_damage_suspected BOOLEAN,
    created_at               TIMESTAMP    NOT NULL
);

-- 90일 파기 DELETE 의 범위 스캔 효율.
CREATE INDEX idx_rejected_correction_created_at ON rejected_correction (created_at);
-- 동의 철회 시 user 단위 삭제 효율.
CREATE INDEX idx_rejected_correction_user ON rejected_correction (user_id);

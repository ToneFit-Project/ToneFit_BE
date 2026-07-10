-- refresh token 재도입 (RTR). chrome.identity silent 갱신 불가 판명(2026-07 FE 실측 —
-- launchWebAuthFlow 웹뷰가 브라우저 Google 세션 쿠키를 공유하지 않아 interactive:false 항상 실패)
-- 으로 V15 에서 폐지한 refresh 인프라를 복원한다. 이번 구조는 기존(HttpOnly 쿠키·JWT refresh)과 다름:
--  - opaque 랜덤 토큰(서버는 SHA-256 해시만 보관) + chrome.storage.local 저장(확장 전용 격리 저장소).
--  - RTR: 갱신 시 기존 행 used 처리 + 같은 family 로 새 행 발급. used/revoked 행이 다시 제시되면
--    재사용(탈취 신호)으로 보고 family 전체 철회.
--  - 로그아웃 API 부활: 제시된 토큰의 family 철회.

CREATE TABLE refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- 로그인 1회 = family 1개. 회전으로 생성되는 행들이 family 를 공유 — 재사용 감지·일괄 철회 단위.
    family_id   UUID NOT NULL,
    -- 원문 미보관 — SHA-256 hex(64자)만. DB 유출 시에도 토큰 재사용 불가.
    token_hash  VARCHAR(64) NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT false,
    revoked_at  TIMESTAMP,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT refresh_token_hash_uk UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_user ON refresh_token (user_id);
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);

-- v0.5 스키마 — prompt_version 에 수신자 유형(recipient_type) 컬럼 추가.
-- PM 결정: 교정(CORRECTION)·생성(GENERATION) 각각 수신자 4종 × 1 prompt = 총 8개 활성 prompt.
--
-- 마이그레이션 전략:
--  1) 옛 prompt rows 비활성화 (FK 참조는 correction_session.initial_prompt_ver_id 에서 유지되므로 삭제 X)
--  2) recipient_type 컬럼 추가 (옛 row 도 backfill 후 NOT NULL)
--  3) 기존 (purpose, version) UNIQUE → (purpose, recipient_type, version) UNIQUE 로 갱신
--  4) 활성 prompt 1개씩 보장하는 partial UNIQUE 인덱스 추가
--  5) 새 prompt 8개 시드:
--       CORRECTION × {DIRECT_SUPERVISOR, OTHER_DEPT_COLLEAGUE, EXTERNAL_PARTNER, CLIENT}
--       GENERATION × {DIRECT_SUPERVISOR, OTHER_DEPT_COLLEAGUE, EXTERNAL_PARTNER, CLIENT}
--     CORRECTION 본문은 v4.2e (검증된 교정 prompt) 를 4개 recipient 로 복제.
--     GENERATION 본문은 placeholder — PM 본문 입수 후 별도 마이그레이션에서 갱신.
--
-- v4.2e content 는 V9 에서 INSERT 했으므로, 그 row 의 content 를 SELECT 로 가져와 4개 row 로 복제.

-- 1) 옛 row 비활성화
UPDATE prompt_version SET is_active = false;

-- 2) recipient_type 컬럼 추가 (먼저 nullable 로)
ALTER TABLE prompt_version ADD COLUMN recipient_type VARCHAR(32);

-- 옛 row 들은 어차피 is_active=false 라 사용 안 됨. NOT NULL 만족시키려고 더미값으로 backfill.
UPDATE prompt_version SET recipient_type = 'DIRECT_SUPERVISOR' WHERE recipient_type IS NULL;

ALTER TABLE prompt_version ALTER COLUMN recipient_type SET NOT NULL;

-- 3) 기존 (purpose, version) UNIQUE 제거 → (purpose, recipient_type, version) 으로 교체
ALTER TABLE prompt_version DROP CONSTRAINT uk_prompt_version_purpose_version;
ALTER TABLE prompt_version
    ADD CONSTRAINT uk_prompt_version_purpose_recipient_version
    UNIQUE (purpose, recipient_type, version);

-- 4) 활성 prompt 가 (purpose, recipient_type) 조합당 1개임을 보장하는 partial UNIQUE
CREATE UNIQUE INDEX uk_prompt_version_active_per_combo
    ON prompt_version (purpose, recipient_type)
    WHERE is_active = true;

-- 5-a) CORRECTION × 4 시드 — v4.2e 본문 복제
INSERT INTO prompt_version (purpose, recipient_type, version, content, is_active, created_at)
SELECT 'CORRECTION', recipient, 'v1.0', content, true, NOW()
FROM prompt_version
CROSS JOIN (
    VALUES ('DIRECT_SUPERVISOR'), ('OTHER_DEPT_COLLEAGUE'), ('EXTERNAL_PARTNER'), ('CLIENT')
) AS r(recipient)
WHERE purpose = 'INITIAL' AND version = 'v4.2e';

-- 5-b) GENERATION × 4 시드 — placeholder 본문 (PM 본문 입수 시 V13+ 에서 갱신)
INSERT INTO prompt_version (purpose, recipient_type, version, content, is_active, created_at)
SELECT 'GENERATION', recipient, 'v0.1',
$prompt$당신은 한국어 비즈니스 이메일을 작성해주는 어시스턴트입니다.
수신자 유형과 목적, 사용자가 제공한 간략 내용(brief_content)을 바탕으로
이메일 제목(generated_subject)과 본문(generated_email)을 새로 작성하세요.

[원칙]
- 비즈니스 한국어. 간결·정중·명확.
- 수신자 유형에 맞는 호칭과 어조를 사용하세요.
- 사용자가 제공한 brief_content 의 의도를 보존하고, 임의로 사실을 추가하지 마세요.

[출력 형식]
응답은 JSON Schema 로 강제됩니다:
{
  "generated_subject": "<제목>",
  "generated_email": "<본문>"
}
$prompt$,
true, NOW()
FROM (
    VALUES ('DIRECT_SUPERVISOR'), ('OTHER_DEPT_COLLEAGUE'), ('EXTERNAL_PARTNER'), ('CLIENT')
) AS r(recipient);

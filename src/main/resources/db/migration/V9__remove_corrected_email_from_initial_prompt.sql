-- INITIAL prompt 갱신 (v4.2d → v4.2e).
-- 변경: 출력 구조에서 corrected_email 필드 제거.
-- 이유: 서버가 원문 + changes 로 corrected_email 을 재조립하므로 Gemini 가 별도 출력할 필요 없음.
--       Gemini 출력 토큰 절감 → 응답 시간 단축 + 비용 절감.
-- 방법: v4.2d 본문에서 corrected_email 언급 2개소만 REPLACE 로 surgical 수정. 나머지 본문 동일.

UPDATE prompt_version SET is_active = false WHERE purpose = 'INITIAL';

INSERT INTO prompt_version (purpose, version, content, is_active, created_at)
SELECT
    'INITIAL',
    'v4.2e',
    REPLACE(
        REPLACE(
            content,
            -- JSON 예시에서 corrected_email 라인 제거
            '  "corrected_email": "<교정된 이메일 전체 본문>",
  "changes":',
            '  "changes":'
        ),
        -- "교정 없음 케이스" 설명에서 corrected_email 언급 제거
        'corrected_email 은 원문 그대로, changes 는 빈 배열',
        'changes 는 빈 배열'
    ),
    true,
    NOW()
FROM prompt_version
WHERE purpose = 'INITIAL' AND version = 'v4.2d';

-- 로컬 개발용 기본 사용자 (정식 가입자, 테스트 토큰 발급용)
-- 비밀번호 해시는 BCrypt('test1234') 결과 — 운영에는 절대 사용하지 말 것
INSERT INTO users (
    is_guest, email, password_hash, nickname,
    plan, free_used, credit_balance, status, created_at, updated_at
) VALUES (
    false,
    'test@example.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
    '테스트유저',
    'FREE', 0, 0, 'ACTIVE', NOW(), NOW()
) ON CONFLICT (email) DO NOTHING;

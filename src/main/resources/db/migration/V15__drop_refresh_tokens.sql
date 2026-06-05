-- refresh token 인프라 제거.
-- Extension 은 chrome.identity silent refresh 로 access token 만료 시 새 Google ID token 을 받아
-- /auth/google 을 재호출(재로그인)하므로 자체 refresh token 이 불필요하다.
-- 웹은 데모만(토큰 없음)이라 refresh 사용처가 없다.
-- access token 은 stateless(서버 미보관)라 별도 정리 대상 없음.

DROP TABLE IF EXISTS refresh_tokens;

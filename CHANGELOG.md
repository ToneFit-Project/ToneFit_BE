# Changelog

배포(`deploy/X.Y.Z` 병합) 단위로 재정리한 변경 이력. 항목 표기는 [COMMIT_CONVENTION.md](COMMIT_CONVENTION.md)
형식을 따르되, 실제 과거 커밋 메시지는 소급 수정하지 않는다 (이 문서가 표시용 정본).
최신 버전이 위.

## [0.0.25] - 2026-07-03

- feat: 생성 프롬프트 갱신 반영 — 동료·협력사 해요체 강제 강화 (V23)

## [0.0.24] - 2026-07-02

- feat: AI 폴오버 Phase 1 — 헤지 오케스트레이터 + 차단기 코어
- feat: AI 폴오버 Phase 2 — async transport + OpenAI 생성 클라이언트
- feat: AI 폴오버 Phase 3a — OpenAI 교정 클라이언트 + 교정 후처리 공용 추출
- feat: AI 폴오버 Phase 3b(생성) — Gemini async + Failover 데코레이터 와이어링
- feat: AI 폴오버(교정) — Gemini async 교정 + Failover 데코레이터
- chore: OpenAI 폴오버 모델 확정 — gpt-4.1-mini (yaml 기본값)
- refactor: 폴오버 코드리뷰 지적 4건 반영
- fix: 회신 작성 미답변(빈 답변) 허용 + 검증 실패 로그에 필드명 기록
- fix: 회신 작성 요청 questions 필드명을 파악 응답과 일치(question)로 정정
- fix: 회신 한도 1건당 1회 차감으로 정정 + 분당 한도 임시 상향 (FE 테스트)

## [0.0.23] - 2026-06-29

- chore: 생성·교정 Gemini 호출 지연 계측 (폴오버 기준값 데이터 수집)

## [0.0.22] - 2026-06-23

- feat: 회신 재설계 — 요약을 생성 경로에서 분리·병행 + 한도 차감 기준 통일
- fix: 교정 thinkingConfig 에서 thinkingBudget 제거 (thinkingLevel 만 사용)

## [0.0.21] - 2026-06-23

- feat: 생성·교정 모델 분리 + thinking 설정 (비용 절감 실험)

## [0.0.20] - 2026-06-19

- feat: 응답 보안 헤더 강화 + prod Swagger 비활성화 (QA 제안)
- feat: 교정 프롬프트 v2 — RCP별 분리·갱신 + 원문 보존 (V22)
- fix: 위조·형식 오류 Google id_token 에 500 대신 401 INVALID_ID_TOKEN 응답

## [0.0.19] - 2026-06-16

- feat: 회신 파이프라인 PM 프롬프트 정합 — 요약/파악 분리 + 파악 계약 + RCP 작성 본문
- feat: 교정 CoT — reasoning 선행 필드 + propertyOrdering 으로 과교정 방지
- feat: 생성 프롬프트 본문 시드 — RCP별 + 입력 형식 정렬 (V21)
- feat: 생성 프롬프트 v2 — Purpose enum 정렬 + 줄바꿈 출력 규칙

## [0.0.18] - 2026-06-15

- feat: GET /users/me 선택약관 2건 통합 + 프로필 이미지를 로그인 응답으로 이동 (FE 요청)

## [0.0.17] - 2026-06-14

- ci: Blue/Green 무중단 배포 전환

## [0.0.16] - 2026-06-12

- refactor: 교정 도메인 재설계 — 무상태 전환 + rejected_correction 보존
- feat: 회신 골격 — 파악·작성 2-호출 무상태 엔드포인트
- feat: 회신 파이프라인 — 메일 기계 정리 + Gemini 3-op + 점검·시간 예산
- feat: GET /users/me/terms 약관 동의 현황 조회 (FE 요청)
- feat: 회신 게이트·한도 — 일일 합산 + 분당 3회 + MAIL_READ 동의 + 킬스위치

## [0.0.15] - 2026-06-05

- fix: GOOGLE_OAUTH_CLIENT_IDS 를 AWS Secrets Manager 에서 주입

## [0.0.14] - 2026-06-05

- refactor: 자체 refresh token 인프라 전체 제거 (chrome.identity 대체)
- refactor: is_guest·anonymous_token 레거시 제거 + Extension origin CORS 허용

## [0.0.13] - 2026-06-04

- fix: IP rate limit 을 intervally refill 로 변경 + 한도값 환경변수화

## [0.0.12] - 2026-06-04

- refactor: 익명 토큰·유저 제거 + 데모 생성 public 전환

## [0.0.11] - 2026-06-01

- fix: V10 마이그레이션에서 자체 로그인 폐지 전 기존 정식 계정 정리

## [0.0.10] - 2026-06-01

- feat: 요구사항 갱신 일괄 반영 — Google id_token 로그인 전환(자체 로그인 폐지) + 약관 개편 + 생성 도메인 정비

## [0.0.7 ~ 0.0.9] - 2026-05-12 ~ 2026-05-19

- feat: 서비스 도메인 CORS origin 추가
- chore: 배포 확인용 버전 승격

## [0.0.4 ~ 0.0.6] - 2026-05-11

- chore: CI/CD 파이프라인 검증 — 버전 승격 반복 배포

## 초기 구축 (0.0.4 이전) - 2026-04-18 ~ 2026-05-09

설계 변경이 잦던 구간이라 개별 항목 대신 요약:

- 프로젝트 세팅 · 공통 응답 규격 · 전역 예외 처리 · JWT 인증/보안 설정
- User 프로필 도메인 + Users/Auth API
- AWS Secrets Manager 연동 · prd 프로파일 · CORS 구성
- GitHub Actions CI/CD 구축 (build/deploy 분리 → S3+SSM 배포 → OIDC 전환)

# ToneFit Server

**ToneFit — 한국어 비즈니스 이메일 어시스턴트**의 백엔드입니다. 수신자·상황에 맞는 격식으로
이메일을 **교정(Correction)·생성(Generation)·회신(Reply)** 하는 3가지 AI 기능을 제공합니다.
(이름과 달리 피트니스 서비스가 아닙니다 — "톤(어조)을 맞춘다(fit)"는 의미입니다.)

- **클라이언트**: Chrome Extension(정식, Google OAuth) + 웹 데모(생성 1종, 무인증)
- **AI**: Gemini 주 모델 + GPT 폴오버(헤지+차단기, 기능 플래그) — 프롬프트는 DB(`prompt_version`)로 관리
- **스택**: Java 21 · Spring Boot 4 · PostgreSQL(Flyway) · AWS(EC2/S3/SSM/Secrets Manager)

문서: [CHANGELOG.md](CHANGELOG.md)(배포 단위 변경 이력) · [COMMIT_CONVENTION.md](COMMIT_CONVENTION.md)(커밋·브랜치·배포 규칙 — 기여 전 필독)

## 🏛 Architecture & Package Structure

이 프로젝트는 **도메인 중심 설계(Domain-Driven Design)**와 **관심사 분리(Separation of Concerns)** 원칙을 따릅니다. 모든 코드는 크게 `core`와 `domain` 패키지로 구분됩니다.

### 1. core (Technical Infrastructure)
비즈니스 로직에 종속되지 않는 기술적 인프라와 공통 규격을 관리합니다.
- **core.ai**: AI 폴오버 인프라 (헤지 오케스트레이터, 차단기, async transport, OpenAI 클라이언트 공통)
- **core.config**: 애플리케이션 전역 설정 (Security, AWS Secrets Manager 주입 등)
- **core.dto**: 전역 공통 응답 규격 (`ApiResponse`) 및 공용 DTO
- **core.enums**: 공통으로 사용되는 Enum (ErrorType, Receiver, Purpose, TermsType 등)
- **core.exception**: 전역 예외 처리 클래스 및 핸들러
- **core.security**: 인증/인가·보호 로직 (JWT Provider/Filter, Rate Limit, TextSanitizer)
- **core.web**: 웹 계층 공통 설정

### 2. domain (Business Logic)
서비스의 핵심 비즈니스 기능과 데이터 모델을 담습니다. 각 기능별로 하위 패키지를 구성합니다.
- **domain.correction**: 이메일 교정 — 수신자 격식 기준 3계층(필수/추천/참고) 교정, 거절 항목 보존
- **domain.generation**: 이메일 생성 — 개요 입력 → 완성 메일 (웹 데모는 이 기능만 무인증 제공)
- **domain.reply**: 받은 메일 회신 — 요약·파악·작성 3-호출 무상태 파이프라인 + 내부 점검
- **domain.auth**: Google OAuth 로그인, refresh token(RTR) 갱신/로그아웃
- **domain.user**: 사용자·약관 동의 관리
- **domain.prompt**: AI 프롬프트 버전 관리 (`prompt_version` — 기능×수신자 단위, Flyway 시드)
- **domain.event**: 측정 이벤트 기록 (`event_log` + Amplitude 미러링)

### 💡 설계 원칙
- **기능 기반 패키징**: `service`, `repository`와 같은 계층형 패키징 대신, 도메인(기능)별로 관련 클래스들을 응집시켜 관리합니다.
- **계층 간 의존성**: `domain`은 `core`에 의존할 수 있으나, `core`는 특정 `domain`의 내부 비즈니스 로직을 알아서는 안 됩니다.
- **불변 객체 활용**: DTO 및 공통 응답 규격은 Java 21의 `record`를 적극 활용하여 데이터의 불변성을 보장합니다.

---

## 🚢 배포 방법

프로덕션 배포는 **GitHub Actions**가 자동으로 처리합니다. `main` 브랜치에 직접 push는 불가능하며, 반드시 PR을 통해 머지해야 합니다.

### 배포 트리거 조건

`gradle.properties`의 `version` 값이 변경된 채로 `main`에 머지될 때만 배포가 자동 실행됩니다.
일반 기능 개발 PR은 배포를 트리거하지 않습니다.

### 배포 절차

```
1단계: 기능 개발
  feature/xxx 브랜치 → main PR 머지 (배포 없음)

2단계: 배포
  deploy/x.x.x 브랜치 생성
  └── gradle.properties에서 version 값 올리기 (예: 0.0.2 → 0.0.3)
  └── CHANGELOG.md에 해당 버전 섹션 추가 (직전 배포 이후 커밋 제목 — COMMIT_CONVENTION.md 참조)
  └── main으로 PR 머지
      └── GitHub Actions 자동 실행 → EC2 배포 완료
```

### 배포 브랜치 만들기

```bash
git switch main && git pull
git switch -c deploy/0.0.3

# gradle.properties 수정
# version=0.0.2  →  version=0.0.3

git add gradle.properties
git commit -m "chore: version 0.0.3"
git push origin deploy/0.0.3
# → GitHub에서 main으로 PR 생성 후 머지
```

### 배포 진행 확인

PR 머지 후 GitHub 저장소 → **Actions** 탭에서 진행 상황을 확인할 수 있습니다.
`build` → `deploy` 순서로 실행되며, 두 단계 모두 초록색이면 배포 완료입니다.

### 배포 구조 (참고)

```
GitHub Actions (build)
  └── JAR 빌드 → S3 업로드

GitHub Actions (deploy)
  └── SSM Send Command → EC2 (/app/deploy.sh)
        └── S3에서 JAR 다운로드
        └── Blue/Green 전환 (대기 인스턴스 기동·헬스체크 후 nginx 스위칭 — 무중단)
```

> EC2에 직접 SSH 접속 없이 AWS SSM을 통해 명령이 전달됩니다.

---

## 🚀 시작하기

### 환경 설정
- **Java**: 21
- **Database**: PostgreSQL 16 (Docker)
- **Build**: Gradle

### 실행 방법
1. DB 컨테이너 실행: `docker compose up -d db`
2. 애플리케이션 실행: `./gradlew bootRun` (로컬) 또는 `docker compose up --build -d app` (도커)

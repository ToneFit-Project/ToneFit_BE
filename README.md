# Tonefit Server

Tonefit 서비스의 백엔드 시스템입니다. Java 21과 Spring Boot 4를 기반으로 구축되었습니다.

## 🏛 Architecture & Package Structure

이 프로젝트는 **도메인 중심 설계(Domain-Driven Design)**와 **관심사 분리(Separation of Concerns)** 원칙을 따릅니다. 모든 코드는 크게 `core`와 `domain` 패키지로 구분됩니다.

### 1. core (Technical Infrastructure)
비즈니스 로직에 종속되지 않는 기술적 인프라와 공통 규격을 관리합니다.
- **core.config**: 애플리케이션 전역 설정 (Security, JPA Auditing 등)
- **core.dto**: 전역 공통 응답 규격 (`ApiResponse`)
- **core.enums**: 공통으로 사용되는 Enum (ErrorType, UserStatus 등)
- **core.exception**: 전역 예외 처리 클래스 및 핸들러
- **core.security**: 인증/인가 관련 핵심 로직 (JWT Provider, Filter)

### 2. domain (Business Logic)
서비스의 핵심 비즈니스 기능과 데이터 모델을 담습니다. 각 기능별로 하위 패키지를 구성합니다.
- **domain.user**: 사용자 데이터 모델(Entity) 및 저장소(Repository)
- **domain.auth**: 회원가입, 로그인 등 인증 비즈니스 서비스 및 API 컨트롤러
- **domain.xxxx**: 향후 추가될 비즈니스 도메인들 (운동, 식단 등)

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
  └── gradle.properties에서 version 값만 올리기 (예: 0.0.2 → 0.0.3)
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
  └── SSM Send Command → EC2
        └── S3에서 JAR 다운로드
        └── 서비스 재시작 (systemctl restart tonefit)
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

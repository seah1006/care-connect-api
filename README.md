# care-connect-api

발달장애인 주간활동·방과후활동 제공기관과 보호자·문의자를 잇는 운영 API입니다.

## 왜 만드나

사회복지시설정보시스템(W4C)과 사회서비스 전자바우처는 기관과 행정 사이의 업무(회계, 후원, 결제, 보고)를 처리합니다.
하지만 기관과 보호자, 이용을 문의하는 가족 사이의 접점은 여전히 전화, 메신저, 종이 서류로 처리됩니다.
이 프로젝트는 그 빈 접점을 다룹니다.

## 상태

기획 단계입니다. 실제 기관 인터뷰를 바탕으로 핵심 모듈을 확정하는 중입니다.

- 후보 모듈: 이용 문의·상담·대기 관리, 방학 중 돌봄 운영, 보호자 활동 기록 공유, 월별 활동 기록지 보호자 확인
- 공공 시스템이 이미 하는 일(출결·바우처 결제, 회계, 후원 영수증)은 만들지 않습니다.

## 설계 원칙

- 기관 단위 멀티테넌트: 여러 기관이 같은 서비스를 독립적으로 사용
- 민감정보 보호: 장애·건강 정보 별도 동의, 고유식별정보 암호화, 접속기록 보관
- 실제 개인정보 없이 개발: 저장소와 개발 환경에는 가짜 데이터만 사용

## 문서

- [기술 결정 기록 (ADR)](docs/adr/README.md)
- [불변식 목록](docs/invariants/INVARIANTS.md): 언제나 참이어야 하는 성질과 검증 위치
- [작업 방식](CLAUDE.md): 계획·리뷰는 Claude, 구현은 Codex([규칙](AGENTS.md))

## 기술 스택

- Java 25, Spring Boot 4.1
- PostgreSQL 17 (Flyway 마이그레이션)
- JWT 인증, 역할 기반 권한

## 개발 환경

- JDK 25를 사용합니다. Gradle toolchain이 없으면 foojay resolver로 자동 다운로드합니다. Wrapper 실행에는 Java 17 이상이 필요합니다.
- PostgreSQL 통합 테스트에는 실행 중인 Docker가 필요합니다 (`postgres:17-alpine`).
- 로컬 실행은 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 환경 변수를 설정한 뒤 `./gradlew bootRun`으로 시작합니다.

검증 명령:

- `./gradlew check`: 컴파일, jqwik 성질 테스트, ArchUnit 구조 규칙, 실제 PostgreSQL의 컨텍스트·Flyway·health 통합 테스트를 실행합니다.
- `./gradlew pitest`: 애플리케이션·설정 클래스를 제외한 코드의 변이 점수가 80% 이상인지 검사합니다.
- `./gradlew tlcCheck`: 짝이 되는 `.cfg`가 있는 `docs/spec/*.tla`를 TLC로 검사합니다.

전체 검증은 `./gradlew check pitest tlcCheck`로 실행합니다. Docker에 접근할 수 없는 샌드박스에서만 `./gradlew check -PskipDockerTests`를 사용합니다. 기본 실행과 CI는 Docker 테스트도 반드시 실행합니다.

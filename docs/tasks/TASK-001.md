# TASK-001: 프로젝트 뼈대와 검증 파이프라인

- 작성: Claude
- 날짜: 2026-10-28
- 브랜치: task/TASK-001

## 목표

Spring Boot 프로젝트를 만들고, ADR 0008의 검증 계층(L1~L6)이 **실제로 돌아가는** 상태로 만든다.
이 작업이 끝나면 `./gradlew check pitest tlcCheck` 한 줄로 모든 검증이 실행되고, CI에서도 같은 명령이 돈다.
도메인 기능은 만들지 않는다. 파이프라인이 진짜로 버그를 잡는지 보여줄 최소 예제만 만든다.

## 범위

바꿔도 되는 파일·경로.

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/`, `gradlew`, `gradlew.bat`, `gradle.properties`
- `src/main/java/io/github/seah1006/careconnect/**`
- `src/main/resources/**`
- `src/test/java/io/github/seah1006/careconnect/**`
- `src/test/resources/**`
- `.github/workflows/ci.yml`
- `.gitattributes` (gradlew 줄바꿈 고정용)
- `README.md` 의 "개발 환경" 절 (새로 추가)

## 범위 밖

- 인증, 권한, 멀티테넌트, 에러 형식 등 공통 기능 (TASK-002 이후)
- Spring Security 의존성 추가 (추가하면 모든 엔드포인트가 잠겨 이번 범위가 흐려짐)
- 도메인 엔티티와 테이블

## 영향받는 불변식

| 불변식 ID | 이번 작업에서 할 일 | 검증 계층 |
|---|---|---|
| INV-ARCH-001 | 컨트롤러가 리포지토리에 직접 의존하지 않는다는 ArchUnit 규칙 추가 (권한 검사 위치 부분은 TASK-002) | L4 |
| INV-API-004 | 커서 인코딩·디코딩 왕복 성질 테스트 | L2, L6 |
| INV-SMOKE-001 | `docs/spec/PipelineSmoke.tla` 가 `tlcCheck` 로 검사되는지 확인 | L3 |

## 새로 추가하는 불변식

- INV-API-004: 페이지네이션 커서는 인코딩 후 디코딩하면 항상 원래 값과 같고, 변조된 커서는 항상 거부된다.
  (`docs/invariants/INVARIANTS.md` 에 제안 상태로 추가해 둠)

## 관련 명세

- ADR 0002 (Java 25, Spring Boot, PostgreSQL, Flyway, Testcontainers), ADR 0006 (커서 페이지네이션), ADR 0008 (검증 전략)
- TLA+: `docs/spec/PipelineSmoke.tla`, `docs/spec/PipelineSmoke.cfg` (Claude 작성, 수정 금지)

## 요구사항

### 1. 프로젝트 생성
- Spring Initializr 기준: Gradle Kotlin DSL, Java 25, Spring Boot 4.1.2
- group `io.github.seah1006`, artifact `care-connect-api`, 기본 패키지 `io.github.seah1006.careconnect`
- 의존성: Spring Web, Validation, Spring Data JPA, PostgreSQL Driver, Flyway(PostgreSQL 모듈 포함), Actuator, Testcontainers(PostgreSQL), spring-boot-testcontainers
- Gradle toolchain: `languageVersion = 25`, foojay toolchain resolver 플러그인으로 자동 다운로드 허용
- Gradle wrapper 포함. `gradlew` 는 LF 줄바꿈, 실행 권한(git index 기준) 유지

### 2. DB와 마이그레이션
- Flyway `V1__baseline.sql`: 확장이나 테이블 없이 주석만 있는 기준 마이그레이션
- 로컬 실행용 설정은 환경 변수로 받는다 (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`). 비밀값을 파일에 쓰지 않는다
- 테스트는 Testcontainers PostgreSQL(이미지 `postgres:17-alpine`)을 `@ServiceConnection` 으로 연결

### 3. 검증 도구 (ADR 0008)
- L2: jqwik 1.9.3 (JUnit Platform 엔진으로 실행)
- L4: ArchUnit 1.4.1 (`archunit-junit5`)
- L5: Testcontainers. 애플리케이션 컨텍스트 + Flyway 마이그레이션이 실제 PostgreSQL에서 뜨는 통합 테스트 1개
- L6: PIT, Gradle 플러그인 `info.solidsoft.pitest` 1.15.0, JUnit 5 플러그인 사용
  - 대상: `io.github.seah1006.careconnect.common.*` 와 이후 도메인 패키지 (`io.github.seah1006.careconnect.*`), 설정·애플리케이션 클래스 제외
  - `mutationThreshold = 80`, `failWhenNoMutations = false`
  - 별도 태스크로 두고 `check` 에는 넣지 않는다 (CI에서 따로 실행)
- L3: TLC
  - Gradle 태스크 `tlcCheck` 추가
  - tla2tools.jar v1.7.4 를 `https://github.com/tlaplus/tlaplus/releases/download/v1.7.4/tla2tools.jar` 에서 `build/tools/` 로 내려받고 SHA-256 `936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88` 를 검증한다. 다르면 실패
  - `docs/spec/*.tla` 중 같은 이름의 `.cfg` 가 있는 모든 명세를 `tlc2.TLC -workers auto -deadlock -config <cfg> <tla>` 로 실행, 메타 디렉터리는 `build/tlc/`
  - 하나라도 불변식 위반이나 오류가 나면 태스크 실패. TLC 출력에서 "Model checking completed. No error has been found." 를 확인하는 방식으로 판정
  - 실행 JDK는 toolchain(25)의 java 를 쓴다

### 4. 최소 예제 (파이프라인이 실제로 버그를 잡는지 증명용)
- `io.github.seah1006.careconnect.common.pagination.CursorCodec`
  - `String encode(Cursor)`, `Cursor decode(String)`: 커서는 (정렬 키 문자열, UUID) 쌍
  - URL에 안전한 Base64, 변조 감지를 위해 HMAC-SHA256 서명 포함. 키는 생성자로 주입
  - 형식이 틀리거나 서명이 맞지 않으면 `InvalidCursorException`
- jqwik 성질 테스트 (`@Tag("INV-API-004")`)
  - 임의의 정렬 키(빈 문자열, 유니코드, 구분자 문자 포함)와 UUID에 대해 `decode(encode(c)) == c`
  - 인코딩된 커서의 임의 위치 한 글자를 바꾸면 `decode` 는 항상 예외
  - 다른 키로 서명된 커서는 항상 거부
- ArchUnit 테스트 (`@Tag("INV-ARCH-001")`)
  - `..web..` 패키지 클래스는 `..repository..` 패키지나 `org.springframework.data.repository..` 에 의존하지 않는다
  - 아직 해당 클래스가 없어도 규칙이 통과하도록 `allowEmptyShould(true)` 사용
- 통합 테스트: 컨텍스트 로딩과 Flyway 적용 확인, Actuator health 가 UP

### 5. CI (`.github/workflows/ci.yml`)
- push, pull_request (main) 에서 실행
- ubuntu-latest, Temurin 25, Gradle 캐시
- 단계: `./gradlew check` → `./gradlew pitest` → `./gradlew tlcCheck`
- 실패 시 PIT 리포트와 테스트 리포트를 아티팩트로 업로드

### 6. README "개발 환경" 절
- 필요: JDK 25(또는 toolchain 자동 다운로드), Docker(테스트용)
- 검증 명령 3개와 각각 무엇을 검사하는지 한 줄씩

## API

없음 (Actuator health 만)

## 완료 조건

- [ ] `./gradlew check` 통과 (jqwik, ArchUnit, Testcontainers 통합 테스트 포함)
- [ ] `./gradlew pitest` 통과, `CursorCodec` 변이 점수 80% 이상 (실제 점수 보고)
- [ ] `./gradlew tlcCheck` 통과, PipelineSmoke 검사 결과 보고
- [ ] **음성 확인**: `CursorCodec.decode` 의 서명 검증을 일시적으로 제거하면 성질 테스트가 실패하는지 직접 확인하고 되돌린다. 실패했던 테스트 이름과 jqwik이 보여준 최소 반례를 보고서에 적는다
- [ ] 비밀값, 실제 개인정보 없음
- [ ] 범위 밖 파일 변경 없음

## 실행 환경 메모

- Windows 11, PowerShell. 로컬 JDK 25: `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`
- 네트워크: Gradle 의존성, Gradle 배포판, tla2tools.jar 다운로드에만 사용. Java HTTPS는 되지만 `curl` 은 샌드박스에서 TLS 오류가 난다. 다운로드는 Gradle(또는 Java)로 한다.
- **Docker는 Codex 샌드박스 안에서 접근할 수 없다.** 그래서:
  - Docker가 필요한 테스트는 `@Tag("docker")` 를 붙인다.
  - Gradle 속성 `-PskipDockerTests` 가 있을 때만 `docker` 태그를 제외하도록 `test` 태스크를 설정한다. 기본값(속성 없음)은 전부 실행이다.
  - `disabledWithoutDocker` 처럼 Docker가 없으면 조용히 건너뛰는 설정은 쓰지 않는다. Docker가 없는데 속성도 없으면 실패해야 한다.
  - Codex는 `./gradlew check -PskipDockerTests` 로 검증하고, Docker 테스트는 Claude가 리뷰 때 샌드박스 밖에서 실행한다. 보고서에 "Docker 테스트 미실행(리뷰어 실행 필요)"을 명시한다.
- Gradle 사용자 홈은 기본 위치(`C:\Users\USER\.gradle`)를 쓴다. 쓰기 권한을 추가로 열어 두었다.

## 리뷰 기록

### 1회차 (2026-10-28, Claude)

샌드박스 밖에서 `./gradlew check pitest tlcCheck` (Docker 포함) 실행 결과: **실패**. 지시서 범위, 보호 경로, 테스트 약화는 문제 없음.

고칠 것:

1. `ApplicationIntegrationTest` 실패. health 응답을 문자열 전체로 비교해서 깨졌다.
   - 실제 응답: `{"groups":["liveness","readiness"],"status":"UP"}`
   - 응답을 JSON으로 파싱해서 `status` 필드가 `UP` 인지만 확인한다. 다른 필드가 추가돼도 깨지지 않게 한다.
   - 이 테스트는 샌드박스에서 실행할 수 없으니, 수정 후 컴파일만 확인하고 보고서에 "리뷰어 재실행 필요"를 적는다.
2. `CursorCodec` 생성자가 빈 키나 짧은 키를 받는다. 다음 작업에서 키를 설정값으로 주입할 때 실수를 막기 위해:
   - `null` 이거나 32바이트 미만인 키는 `IllegalArgumentException` 으로 거부한다.
   - 경계값(31, 32바이트)과 `null` 을 확인하는 예제 테스트를 추가한다. `@Tag("INV-API-004")` 유지.
   - PIT 점수 80% 이상 유지.
3. `README.md` "기술 스택 (예정)" 절이 예전 내용이다. ADR 0002 확정 내용으로 바꾼다.
   - 제목을 "기술 스택"으로, DB 줄을 `PostgreSQL 17 (Flyway 마이그레이션)` 로, Java 줄을 `Java 25, Spring Boot 4.1` 로.

리뷰어가 직접 처리한 것:

- `gradlew` 실행 권한을 git index에 등록 (`git add --chmod=+x gradlew`)

### 2회차 (2026-10-28, Claude)

샌드박스 밖에서 `./gradlew clean check pitest tlcCheck` (Docker 포함) 실행 결과: **통과**.

- 테스트: ApplicationIntegrationTest 1, ArchitectureTest 1, CursorCodecProperties 9. 실패·건너뜀 0
- PIT: 변이 26개 중 25개 탐지 (96%), 기준 80%
- TLC: PipelineSmoke 고유 상태 3개, 오류 없음
- 1회차 지적 3건 모두 반영 확인. 범위 밖 변경, 보호 경로 변경, 테스트 약화 없음

판단: 병합.

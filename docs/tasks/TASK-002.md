# TASK-002: 멀티테넌트 격리와 직원 인증 (refresh 토큰 교체)

- 작성: Claude
- 날짜: 2026-10-28
- 브랜치: task/TASK-002

## 목표

기관 단위 데이터 격리(애플리케이션 + DB 두 겹)와 직원 로그인·토큰 교체를 만든다.
이 작업이 끝나면 직원이 특정 기관으로 로그인해 access/refresh 토큰을 받고, refresh 토큰 교체와 재사용 감지가
TLA+ 모델(`docs/spec/RefreshTokenRotation.tla`)과 같은 규칙으로 동작하며, 기관 간 데이터는 DB 수준에서도 섞이지 않는다.

## 범위

- `build.gradle.kts` (아래 허용 의존성 추가만)
- `src/main/java/io/github/seah1006/careconnect/common/tenancy/**`
- `src/main/java/io/github/seah1006/careconnect/common/web/**` (ProblemDetail, 보안 오류 응답)
- `src/main/java/io/github/seah1006/careconnect/common/security/**`
- `src/main/java/io/github/seah1006/careconnect/identity/**`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V2__*.sql` 이후
- `src/test/**` (테스트 전용 마이그레이션은 `src/test/resources/db/testmigration/`)
- `.github/workflows/ci.yml` (액션 버전만)

허용 의존성: `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-security-test`(test). 그 밖의 새 의존성 금지.

## 범위 밖

- 보호자·문의자 인증 (초대 링크, OTP), 로그인 실패 잠금 → 이후 작업
- 권한(역할별 접근 범위), 다른 기관 리소스 404 처리 → 이후 작업
- 민감정보 암호화, 접속기록 → 이후 작업
- 사용자·기관을 만드는 API (테스트는 저장소나 SQL로 직접 만든다)

## 영향받는 불변식

| 불변식 ID | 이번 작업에서 할 일 | 검증 계층 |
|---|---|---|
| INV-TEN-001 | 다른 기관 데이터 조회·수정 불가. Hibernate `@TenantId` + PostgreSQL RLS | L1, L2, L5 |
| INV-TEN-002 | 기관 소유 엔티티는 기관 ID 필수 | L1, L4 |
| INV-TEN-003 | 요청이 끝나면 테넌트 컨텍스트는 비어 있음 | L2 |
| INV-TEN-004 (신규) | access 토큰의 기관 ID는 그 사용자가 실제로 소속된 기관이다 | L2, L5 |
| INV-AUTH-001 | refresh 토큰은 최대 한 번만 교환된다. 한 묶음에 살아 있는 토큰은 최대 하나 | L1, L3(완료), L5 |
| INV-AUTH-002 | 재사용 감지 시 묶음 전체 폐기 | L2, L3(완료), L5 |
| INV-AUTH-003 | 비밀번호와 refresh 토큰은 평문으로 저장되지 않는다 | L2 |
| INV-API-001 (일부) | 인증 관련 오류 응답은 ProblemDetail이며 입력값(아이디, 비밀번호, 토큰)을 담지 않는다 | L2 |

## 관련 명세

- ADR 0001 (멀티테넌트), 0003 (인증), 0006 (API 규칙), 0008 (검증)
- TLA+: `docs/spec/RefreshTokenRotation.tla` / 검사 기록 `docs/spec/RefreshTokenRotation.md` (**반드시 읽을 것**)

## 요구사항

### 1. 테넌트 컨텍스트 (INV-TEN-003)
- `common.tenancy.TenantContext`: Java 25 `ScopedValue<UUID>` 로 현재 기관 ID를 묶는다. `ThreadLocal` 금지.
  - 값은 `ScopedValue.where(...).call/run` 범위 안에서만 존재하므로, 요청이 끝나면 구조적으로 사라진다.
  - `Optional<UUID> current()`, `UUID require()` (없으면 예외), `<T> T callAs(UUID orgId, ...)` 제공.
- 인증된 요청은 access 토큰의 `org` 클레임으로 요청 전체를 `TenantContext` 범위 안에서 실행한다 (서블릿 필터).
- Hibernate `CurrentTenantIdentifierResolver` 는 `TenantContext` 에서 기관 ID를 읽는다. 값이 없으면 기관 소유 엔티티 접근은 실패해야 한다 (조용히 전체 조회 금지).

### 2. 기관 소유 엔티티 (INV-TEN-001, INV-TEN-002)
- 마커 인터페이스 `common.tenancy.OrgOwned`. 구현 엔티티는 `@TenantId UUID orgId` 필드를 가진다.
- 테이블: `org_id uuid not null` + 외래키 + 인덱스.
- PostgreSQL RLS (2차 방어선):
  - 기관 소유 테이블마다 `ENABLE` + `FORCE ROW LEVEL SECURITY`, 정책 `USING (org_id = current_setting('app.current_org', true)::uuid)` 와 같은 `WITH CHECK`.
  - 트랜잭션 시작 시 `select set_config('app.current_org', ?, true)` (트랜잭션 범위, 커넥션 풀로 새지 않음). 컨텍스트가 없으면 설정하지 않아 결과가 0행이 되게 한다.
  - **애플리케이션 DB 계정은 슈퍼유저가 아니고 BYPASSRLS가 없어야 한다.** 마이그레이션은 소유자 계정(Flyway), 애플리케이션은 별도 계정으로 접속하도록 설정을 분리한다 (`spring.flyway.user/password` 와 `spring.datasource.*`). 비밀번호는 환경 변수.
  - 테스트에서도 같은 구조를 재현한다 (Testcontainers 초기화 스크립트로 비슈퍼유저 로그인 계정 생성).
- 이번 작업의 기관 소유 테이블: `membership`. 격리 검증용으로 테스트 전용 테이블 `tenant_probe(id, org_id, label)` 을 테스트 마이그레이션에 둔다.

### 3. 기관·사용자·소속 (identity)
- `organization(id uuid, name)`: 기관 공통, 기관 ID 없음
- `staff_user(id uuid, username unique, password_hash, created_at)`: 사용자는 여러 기관에 속할 수 있으므로 기관 ID 없음
- `membership(id, org_id, user_id, role)`: `role` 은 `ORG_ADMIN`, `STAFF` (CHECK 제약), `(org_id, user_id)` 유일
- 비밀번호는 Spring Security `DelegatingPasswordEncoder` (기본 bcrypt)

### 4. 토큰
- access 토큰: JWT HS256, 수명 15분, 클레임 `sub`(userId), `org`(orgId), `role`, `jti`, `iat`, `exp`. 서명 키는 환경 변수 `JWT_SECRET` (Base64, 디코딩 후 32바이트 이상, 미만이면 시작 실패).
- refresh 토큰: 256비트 난수, URL 안전 Base64. DB에는 SHA-256 해시(hex)만 저장.
- `refresh_token(id, family_id, user_id, org_id, token_hash unique, parent_id, status, issued_at, expires_at, used_at)`
  - `status`: `ACTIVE`, `USED`, `REVOKED` (CHECK). 수명 14일.
  - 묶음당 ACTIVE 최대 1개를 DB가 보장: `create unique index ... on refresh_token(family_id) where status = 'ACTIVE'`
  - `parent_id` 유일 (`where parent_id is not null`): 한 토큰에서 자식이 둘 생길 수 없게 DB가 보장 (INV-AUTH-001 L1)
- **교체 규칙은 TLA+ 모델의 `UseCAS = TRUE` 를 그대로 따른다.**
  1. 해시로 토큰 조회. 없거나 만료면 401
  2. `UPDATE ... SET status='USED', used_at=now() WHERE id=? AND status='ACTIVE'`
  3. 영향 행이 1이면 같은 묶음에 새 ACTIVE 토큰 발급 (부모 = 사용된 토큰), 새 access 토큰 발급
  4. 영향 행이 0이고 현재 상태가 `USED` 면 재사용: 그 묶음의 ACTIVE 토큰을 모두 `REVOKED` 로 바꾸고 401
  5. `REVOKED` 면 401
  - 2~4는 한 트랜잭션. 읽은 값만 믿고 쓰는 구현 금지 (모델에서 반례가 나온 방식).

### 5. API (`/api/v1/auth`, `/api/v1/me`)

| 메서드 | 경로 | 성공 | 실패 |
|---|---|---|---|
| POST | `/api/v1/auth/login` `{organizationId, username, password}` | 200 `{accessToken, tokenType:"Bearer", expiresIn, refreshToken}` | 401 (사용자 없음, 비밀번호 틀림, 소속 없음 모두 **같은 응답**), 422 (형식 오류) |
| POST | `/api/v1/auth/refresh` `{refreshToken}` | 200 (login과 같은 형식, 새 토큰 쌍) | 401 |
| POST | `/api/v1/auth/logout` `{refreshToken}` | 204 (묶음 폐기. 모르는 토큰이어도 204) | 422 |
| GET | `/api/v1/me` (Bearer) | 200 `{userId, organizationId, role}` | 401 |

- 오류는 모두 `application/problem+json`. 401은 인증 진입점, 422는 검증 실패에서 ProblemDetail로 나간다.
- 오류 본문(`detail`, 확장 필드 포함)에 요청의 아이디, 비밀번호, 토큰 값을 넣지 않는다.
- 세션 미사용(STATELESS), CSRF 비활성화(토큰 기반 API).

### 6. 테스트 (불변식 ID를 `@Tag` 로)
- INV-TEN-001 (L5, `docker`): 기관 A·B의 `tenant_probe` 행을 만든 뒤
  - A 컨텍스트에서 JPA 조회와 **WHERE 없는 원시 SQL** 모두 A 행만 보인다
  - 컨텍스트 없이 원시 SQL로 조회하면 0행
  - A 컨텍스트에서 B 기관 ID로 INSERT/UPDATE 시도는 실패
- INV-TEN-001 (L2): 무작위 기관 수·행 배치에 대해 위 성질 (상태 기반 또는 반복 생성. Docker 필요하면 `docker` 태그)
- INV-TEN-002 (L4): ArchUnit
  - `OrgOwned` 구현 클래스는 `@TenantId` 붙은 필드를 가진다
  - `@Entity` 는 `OrgOwned` 를 구현하거나, 허용 목록(`Organization`, `StaffUser`, `RefreshToken`)에 있어야 한다
- INV-TEN-003 (L2): 무작위로 중첩·예외가 섞인 `callAs` 실행 뒤 `current()` 는 항상 비어 있다. 다른 스레드로 값이 새지 않는다
- INV-TEN-004 (L2/L5): 소속이 없는 기관으로 로그인하면 항상 401, 발급된 access 토큰의 `org` 는 항상 소속 기관
- INV-AUTH-001 / 002 (L5, `docker`):
  - 순차 재사용: T1 → T2 교환 후 T1 재제출 → 401, T2도 이후 401 (묶음 폐기)
  - **동시성**: 같은 refresh 토큰으로 8개 스레드가 동시에 refresh. 성공 응답은 최대 1개, 원래 토큰의 자식은 최대 1개, 재사용으로 처리된 요청이 하나라도 있으면 묶음에 ACTIVE 토큰 0개. `CountDownLatch` 등으로 실제로 동시에 출발시킨다. 여러 번(예: 20회) 반복
  - 위 결과가 `RefreshTokenRotation.md` 의 규칙과 같은지 테스트 주석에 대응 관계를 적는다
- INV-AUTH-002 (L2): 무작위 명령 순서(login, refresh(현재 토큰), refresh(옛 토큰), logout)를 적용한 뒤 `INV_AUTH_001_SingleActive`, `INV_AUTH_002` 에 해당하는 성질 확인. DB 없이 돌 수 있도록 저장소를 메모리 구현으로 바꿔 끼우는 방식 허용. 단 교체 로직(서비스)은 실제 코드를 쓴다
- INV-AUTH-003 (L2): 무작위 비밀번호·토큰에 대해 저장값 ≠ 원문, 저장된 refresh 해시는 64자 hex, 비밀번호 해시는 encoder로만 일치 확인 가능
- INV-API-001 (L2): 무작위 아이디·비밀번호·토큰 문자열로 실패 요청을 보내 응답이 `application/problem+json` 이고 본문에 입력값이 포함되지 않음 (MockMvc 허용)

### 7. CI
- 액션 버전 올리기: `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`

## 완료 조건

- [ ] `./gradlew check -PskipDockerTests`, `./gradlew pitest`(80% 이상), `./gradlew tlcCheck` 통과
- [ ] Docker 테스트는 컴파일 확인 후 보고서에 "리뷰어 실행 필요" 명시
- [ ] 음성 확인 2가지를 직접 해 보고 되돌린다
  1. refresh 교체의 조건부 UPDATE에서 `AND status='ACTIVE'` 를 빼면 어떤 테스트가 실패하는지 (Docker 필요 테스트면 "리뷰어 확인 필요"로 적고, Docker 없이 실패하는 테스트가 있으면 이름과 반례)
  2. `TenantContext` 를 쓰지 않고 기관 ID를 고정값으로 바꾸면 어떤 테스트가 실패하는지
- [ ] 비밀값·실제 개인정보 없음 (테스트 비밀값은 합성값, 테스트 코드 안에서만)
- [ ] 범위 밖 변경 없음, 기존 테스트 약화 없음

## 실행 환경 메모

- TASK-001과 같음. Docker는 샌드박스에서 접근 불가 → `docker` 태그 + `-PskipDockerTests`
- Java 25 `ScopedValue` 는 정식 기능 (preview 플래그 불필요)

## 리뷰 기록

(리뷰 후 Claude가 추가)

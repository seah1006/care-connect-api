# TASK-003: 직원 권한 (역할별 범위, 다른 기관 404, 권한 위치 규칙)

- 작성: Claude
- 날짜: 2026-10-31
- 브랜치: task/TASK-003

## 목표

같은 기관 안에서도 역할과 담당 그룹에 따라 볼 수 있는 이용자가 달라지게 한다.
이 작업이 끝나면 기관 관리자는 자기 기관 이용자 전체를, 종사자는 담당 그룹 이용자만(기관 설정으로 전체 보기 허용 가능) 조회한다.
다른 기관 이용자를 요청하면 없는 이용자와 똑같은 404를 받고, 권한 판단은 서비스 계층에서만 일어난다는 규칙을 ArchUnit이 막는다.

## 범위

- `src/main/java/io/github/seah1006/careconnect/common/authz/**` (새 패키지: 현재 사용자, 권한 정책, 권한 예외)
- `src/main/java/io/github/seah1006/careconnect/common/web/**` (403/404 ProblemDetail 처리 추가)
- `src/main/java/io/github/seah1006/careconnect/common/security/**` (현재 사용자 추출에 필요한 최소 변경만)
- `src/main/java/io/github/seah1006/careconnect/participant/**` (새 모듈: 이용자, 그룹, 담당 배정)
- `src/main/resources/db/migration/V3__*.sql`
- `src/test/**` (단, 기존 테스트는 삭제·약화 금지. 새 엔티티를 TenantArchitectureTest 허용 목록에 넣지 말고 `OrgOwned` 로 구현)

허용 의존성: 없음 (새 의존성 추가 금지).

## 범위 밖

- 보호자 권한과 보호자-이용자 관계 (INV-AUTHZ-001): 보호자 로그인(ADR 0003 초대 링크·OTP)이 아직 없어서 보호자 계정을 표현할 테이블이 없다. 보호자 인증 작업에서 함께 한다
- 민감정보 열람 권한 플래그, 컬럼 암호화, 접속기록 (ADR 0004 일부, 0005) → TASK-004
- 이용자·그룹·배정을 만드는 쓰기 API (테스트는 SQL이나 저장소로 직접 만든다)
- 플랫폼 운영자(`PLATFORM_ADMIN`)
- 이용자 필드 확정: 인터뷰 답변 전이라 식별용 최소 필드(표시 이름, 그룹)만 둔다

## 영향받는 불변식

| 불변식 ID | 이번 작업에서 할 일 | 검증 계층 |
|---|---|---|
| INV-AUTHZ-002 | 다른 기관 이용자 요청은 없는 이용자와 구별되지 않는 404 | L2, L5 |
| INV-AUTHZ-003 (신규) | 역할별 조회 범위 (관리자 전체, 종사자 담당 그룹 또는 기관 설정 전체 보기) | L2, L5 |
| INV-AUTHZ-004 (신규) | 담당 배정과 이용자 그룹은 같은 기관 안에서만 연결된다 | L1, L5 |
| INV-AUTHZ-005 (신규) | 권한 판단은 토큰 클레임이 아니라 요청 시점의 DB 소속·배정을 기준으로 한다 | L5 |
| INV-ARCH-001 | 권한 위치 규칙 추가 (남은 부분 완성) | L4 |
| INV-TEN-001 | 새 기관 소유 테이블 3개에 RLS. 기존 검증 유지 | L1, L5 |
| INV-TEN-002 | 새 엔티티 3개가 `OrgOwned` + `@TenantId`. 기존 ArchUnit 통과 | L1, L4 |
| INV-API-001 (일부) | 403/404 응답도 ProblemDetail이며 이용자 정보(표시 이름 등)를 담지 않는다 | L2 |

## 새로 추가하는 불변식

`docs/invariants/INVARIANTS.md` 에 "제안" 상태로 추가했다: INV-AUTHZ-003, INV-AUTHZ-004, INV-AUTHZ-005.

## 관련 명세

- ADR 0001 (멀티테넌트), 0004 (권한), 0006 (API 규칙)
- TLA+: 해당 없음. 이번 흐름은 동시 실행 순서에 따라 결과가 달라지는 상태 전이가 없다 (조회만 있음)

## 요구사항

### 1. 테이블 (V3 마이그레이션)

- `organization` 에 `staff_view_all boolean not null default false` 추가 (기관 설정: 종사자 전체 보기)
- `care_group(id uuid, org_id uuid not null, name varchar(100) not null)`
  - `unique (id, org_id)` (복합 외래키 대상)
- `participant(id uuid, org_id uuid not null, group_id uuid, display_name varchar(100) not null)`
  - `foreign key (group_id, org_id) references care_group(id, org_id)`: 이용자의 그룹은 반드시 같은 기관 그룹 (INV-AUTHZ-004 L1)
  - `group_id` 는 null 허용 (그룹 미배정 이용자). 미배정 이용자는 관리자와 전체 보기 종사자만 본다
- `staff_group_assignment(id uuid, org_id uuid not null, user_id uuid not null, group_id uuid not null)`
  - `foreign key (org_id, user_id) references membership(org_id, user_id)`: 그 기관 소속 직원만 배정 가능
  - `foreign key (group_id, org_id) references care_group(id, org_id)`: 같은 기관 그룹만 배정 가능
  - `unique (group_id, user_id)`
- 세 테이블 모두 `org_id` 외래키(organization), 인덱스, `ENABLE` + `FORCE ROW LEVEL SECURITY`, V2와 같은 형식의 정책
- 애플리케이션 계정 권한: 세 테이블 `select, insert, update, delete`, `organization` 은 기존 권한 유지
- 위 복합 외래키를 위해 `membership` 에 이미 있는 `unique (org_id, user_id)` 를 그대로 쓴다

### 2. 엔티티 (participant 모듈)

- `participant.domain.CareGroup`, `Participant`, `StaffGroupAssignment`: 모두 `OrgOwned`, `@TenantId UUID orgId`
- 저장소는 `participant.repository` 에 둔다. JPA 또는 JDBC 중 TASK-002와 맞는 방식

### 3. 현재 사용자 (common.authz)

- `common.authz.Actor(UUID userId, UUID orgId)` 레코드. 역할은 담지 않는다 (INV-AUTHZ-005: 역할은 DB에서 읽는다)
- `common.authz.CurrentActor`: 보안 컨텍스트의 JWT에서 `sub` 와 `TenantContext.require()` 로 `Actor` 를 만든다. 인증이 없으면 예외
- 컨트롤러는 `Actor` 를 만들거나 넘기지 않는다. 서비스가 `CurrentActor` 를 주입받아 스스로 읽는다 (다른 경로로 서비스를 불러도 검사가 빠지지 않게)

### 4. 권한 정책 (common.authz, 순수 로직)

- `AccessPolicy`: DB 접근 없는 순수 함수. 입력은 사실(facts), 출력은 판단
  - 입력: 요청자의 현재 역할(`ORG_ADMIN` / `STAFF` / 소속 없음), 요청자의 담당 그룹 집합, 기관의 `staff_view_all`, 대상 이용자의 그룹(없을 수 있음)
  - 규칙:
    - 소속 없음 → 거부
    - `ORG_ADMIN` → 허용
    - `STAFF` 이고 `staff_view_all` → 허용
    - `STAFF` 이고 대상 그룹이 담당 그룹 집합에 있음 → 허용
    - 그 밖 → 거부
- 목록 조회는 같은 정책을 쿼리 조건으로 옮긴다. 정책 함수와 쿼리 조건이 같은 결과를 내는지는 L5 성질 테스트로 확인한다 (아래 6번)
- 사실 조회(현재 역할, 담당 그룹, 기관 설정)는 매 요청 DB에서 읽는다. access 토큰의 `role` 클레임을 권한 판단에 쓰지 않는다

### 5. API (`/api/v1/participants`)

| 메서드 | 경로 | 성공 | 실패 |
|---|---|---|---|
| GET | `/api/v1/participants/{id}` | 200 `{id, displayName, groupId}` | 401, 403 (같은 기관이지만 권한 없음), 404 (없음 또는 다른 기관, **같은 응답**) |
| GET | `/api/v1/participants?cursor=&size=` | 200 `{items:[...], nextCursor}` (볼 수 있는 이용자만, `id` 오름차순) | 401, 422 (잘못된 커서, size 1~100 밖) |

- 커서는 기존 `common.pagination.CursorCodec` 을 쓴다
- 404 판단 순서: 대상을 먼저 기관 범위(RLS + `@TenantId`) 안에서 찾는다. 없으면 404. 찾았을 때만 정책을 적용해 403. 그래서 다른 기관 이용자는 존재 여부와 상관없이 정책 단계까지 가지 않는다
- 404 응답 본문은 없는 id와 다른 기관 id에서 바이트 단위로 같아야 한다 (요청 id를 본문에 넣지 않는다. `instance` 필드도 넣지 않거나 같게)
- 403·404 본문에 이용자 표시 이름, 그룹 이름을 넣지 않는다
- 소속이 삭제된 사용자가 아직 유효한 access 토큰으로 요청하면: 단건 403, 목록은 빈 목록이 아니라 403

### 6. 테스트 (불변식 ID를 `@Tag` 로)

- INV-AUTHZ-003 (L2, Docker 없음): `AccessPolicy` 성질 테스트
  - 무작위 역할·담당 그룹 집합·`staff_view_all`·대상 그룹에 대해 위 5개 규칙과 같은 결과
  - 단조성: `STAFF` 의 담당 그룹을 늘리면 허용이 줄지 않는다. `staff_view_all` 을 켜면 허용이 줄지 않는다
  - 소속 없음은 어떤 입력에서도 거부
- INV-AUTHZ-003 (L5, `docker`): 무작위 데이터(기관 2개, 그룹·이용자·직원·배정 무작위)를 DB에 넣고, 직원마다
  - 목록 API 결과 집합 == 모든 이용자 중 `AccessPolicy` 가 허용하는 것의 집합 (쿼리 조건과 정책 함수의 일치)
  - 단건 API는 목록에 있는 이용자에 200, 같은 기관이지만 목록에 없는 이용자에 403
  - 최소 반복 횟수 20회 (jqwik `tries` 또는 반복 생성)
- INV-AUTHZ-002 (L2 + L5, `docker`): 기관 A 직원으로
  - 무작위 UUID(없는 id)와 기관 B의 실제 이용자 id 각각에 대해 단건 조회
  - 두 응답의 상태 코드, `Content-Type`, 본문이 같다. 상태는 404
  - 기관 B 이용자는 A의 목록 어느 페이지에도 나오지 않는다
- INV-AUTHZ-004 (L1 + L5, `docker`): 다음 INSERT가 DB에서 거부된다 (애플리케이션 계정, 같은 기관 컨텍스트 안에서도)
  - 기관 A 직원을 기관 B 그룹에 배정
  - 기관 A 소속이 아닌 사용자를 기관 A 그룹에 배정
  - 기관 A 이용자의 그룹을 기관 B 그룹으로 지정
- INV-AUTHZ-005 (L5, `docker`): 직원이 토큰을 받은 뒤
  - 담당 배정을 삭제하면 같은 토큰의 다음 요청에서 그 이용자는 403, 목록에서 사라짐
  - 소속 역할을 `STAFF` → `ORG_ADMIN` 으로 바꾸면 같은 토큰으로 전체가 보임
  - 소속을 삭제하면 같은 토큰으로 단건·목록 모두 403
- INV-ARCH-001 (L4): 기존 `ArchitectureTest` 에 규칙 추가
  - `..web..` 패키지는 `common.authz.AccessPolicy` 에 의존하지 않는다 (컨트롤러에서 권한 판단 금지)
  - `@PreAuthorize`, `@PostAuthorize`, `@Secured`, `jakarta.annotation.security.RolesAllowed` 를 어떤 클래스·메서드에도 쓰지 않는다
  - `participant.repository` 에 의존하는 `..application..` 클래스는 `common.authz.AccessPolicy` 에도 의존한다
  - `..web..` 패키지는 `common.authz.CurrentActor` 에 의존하지 않는다
- INV-API-001 (L2): 403·404 응답이 `application/problem+json` 이고, 무작위로 만든 이용자 표시 이름·그룹 이름이 본문에 나오지 않는다

## 완료 조건

- [ ] 위 불변식마다 지정된 계층의 테스트가 있고 통과한다
- [ ] `./gradlew check -PskipDockerTests`, `./gradlew pitest`(80% 이상), `./gradlew tlcCheck` 통과
- [ ] Docker 테스트는 컴파일 확인 후 보고서에 "리뷰어 실행 필요" 명시
- [ ] 음성 확인 3가지를 직접 해 보고 되돌린다 (Docker가 필요하면 "리뷰어 확인 필요")
  1. `AccessPolicy` 에서 담당 그룹 검사를 빼고 `STAFF` 를 항상 허용하면 어떤 테스트가 실패하는지
  2. 단건 조회에서 다른 기관 이용자에 403을 돌려주도록 바꾸면 어떤 테스트가 실패하는지
  3. 컨트롤러에서 `AccessPolicy` 를 직접 부르게 바꾸면 ArchUnit이 실패하는지
- [ ] 비밀값·실제 개인정보 없음 (이용자 이름은 합성값)
- [ ] 범위 밖 변경 없음, 기존 테스트 약화 없음

## 실행 환경 메모

- TASK-002와 같음. Docker는 샌드박스에서 접근 불가 → `docker` 태그 + `-PskipDockerTests`
- PIT는 저장소 경로에 한글이 있으면 테스트를 하나도 찾지 못해 0%로 실패한다 (2026-10-31 확인). 저장소는 영문 경로에 둔다

## 리뷰 기록

Claude가 리뷰 후 지적 사항을 회차별로 추가한다.

# 불변식 목록

"이 시스템에서 언제나 참이어야 하는 성질"의 목록이다.
여기에 적힌 불변식만 자동 검증으로 보장된다. 적히지 않은 성질은 보장되지 않는다.

## 상태

- 제안: 정의만 됨, 아직 검증 코드 없음
- 확정: 지정된 계층에서 매 커밋 검증 중
- 폐기: 요구사항 변경으로 더 이상 지키지 않음 (지우지 않고 이유를 남김)

## 검증 계층

L1 DB 제약 · L2 성질 기반 테스트(jqwik) · L3 모델 검사(TLA+/TLC) · L4 구조 규칙(ArchUnit) · L5 동시성 통합 테스트 · L6 변이 테스트(PIT)

---

## 공통 (모듈과 무관)

| ID | 불변식 | 계층 | 근거 | 테스트 위치 | 상태 |
|---|---|---|---|---|---|
| INV-TEN-001 | 어떤 사용자도 자기 소속이 아닌 기관의 데이터를 조회·수정할 수 없다 | L1(RLS), L2, L5 | ADR 0001 | RLS 정책(V2) + `identity/ApplicationIntegrationTest` (WHERE 없는 원시 SQL, 교차 기관 쓰기 거부, 컨텍스트 없을 때 0행) | 확정 (TASK-002) |
| INV-TEN-002 | 기관 소유 엔티티는 모두 기관 ID를 가지며 null일 수 없다 | L1(NOT NULL), L4 | ADR 0001 | `TenantArchitectureTest` + `org_id not null` | 확정 (TASK-002) |
| INV-TEN-003 | 요청이 끝나면 테넌트 컨텍스트는 항상 비어 있다 (다음 요청으로 새지 않는다) | L2, L5 | ADR 0001 | `TenantContextProperties`, `TenantRequestFilterTest` (ScopedValue로 구조적 보장) | 확정 (TASK-002) |
| INV-TEN-004 | access 토큰의 기관 ID는 그 사용자가 실제로 소속된 기관이다 | L2, L5 | ADR 0003, 0004 | `AuthStateProperties`, `AuthServiceTest`, `identity/ApplicationIntegrationTest` | 확정 (TASK-002) |
| INV-AUTH-001 | 하나의 refresh 토큰은 최대 한 번만 새 토큰으로 교환된다 (한 묶음에 살아 있는 토큰은 최대 하나) | L1(UNIQUE/상태), L3, L5 | ADR 0003 | L1: 유니크 인덱스 `refresh_token_single_active`, `refresh_token_single_child` / L3: `RefreshTokenRotation.tla` / L5: 8스레드 x 20묶음 동시성 테스트. 조건 제거 음성 확인 완료 | 확정 (TASK-002) |
| INV-AUTH-002 | 이미 사용된 refresh 토큰이 다시 제출되면, 그 토큰 묶음 전체가 폐기 상태가 된다 | L2, L3, L5 | ADR 0003 | L2: `AuthStateProperties` / L3: `RefreshTokenRotation.tla` / L5: 순차 재사용·동시성 테스트 | 확정 (TASK-002) |
| INV-AUTH-003 | 비밀번호와 refresh 토큰은 DB에 평문으로 저장되지 않는다 | L2, L4 | ADR 0003 | `SecurityTest`, `AuthStateProperties`, `JdbcAuthStoreTest` | 확정 (TASK-002) |
| INV-AUTHZ-001 | 보호자는 관계가 등록된 이용자의 데이터에만 접근한다 | L2, L5 | ADR 0004 | - | 제안 (보호자 인증 작업에서 함께 진행. TASK-003 범위 밖) |
| INV-AUTHZ-002 | 다른 기관 리소스에 대한 요청은 항상 404이며, 존재 여부가 응답으로 드러나지 않는다 | L2, L5 | ADR 0004 | L2: `ParticipantApiTest` (없는 id와 다른 기관 id 응답 바이트 비교) / L5: `participant/ApplicationIntegrationTest` (무작위 배치 20회, 목록에 다른 기관 이용자 없음) | 확정 (TASK-003, 이용자 조회) |
| INV-AUTHZ-003 | 기관 관리자는 자기 기관 이용자 전체를, 종사자는 담당 그룹 이용자만 조회한다 (기관 설정 `staff_view_all` 이 켜진 경우 종사자도 전체). 목록 결과와 단건 판단은 같은 정책을 따른다 | L2, L5 | ADR 0004 | L2: `AccessPolicyProperties` (규칙 일치, 단조성) / L5: `participant/ApplicationIntegrationTest` (목록 쿼리 결과 == 정책 허용 집합, 무작위 배치 20회) | 확정 (TASK-003) |
| INV-AUTHZ-004 | 담당 배정과 이용자 그룹은 같은 기관 안에서만 연결된다 (다른 기관 그룹·비소속 직원 배정 불가) | L1(복합 FK), L5 | ADR 0001, 0004 | V3 복합 FK + `participant/ApplicationIntegrationTest` (애플리케이션 계정으로 교차 기관 배정·그룹 지정 거부). 단일 FK로 바꾸는 음성 확인 완료 | 확정 (TASK-003) |
| INV-AUTHZ-005 | 권한 판단은 access 토큰 클레임이 아니라 요청 시점의 DB 소속·배정을 기준으로 한다 (배정·역할·소속 변경이 다음 요청부터 반영) | L5 | ADR 0004 | `participant/ApplicationIntegrationTest` (토큰 역할을 일부러 반대로 발급, 같은 토큰으로 배정 삭제·승격·소속 삭제·무작위 50단계) | 확정 (TASK-003) |
| INV-SENS-001 | 민감정보로 지정된 컬럼의 DB 저장값은 항상 암호문이다 | L2, L4 | ADR 0005 | - | 제안 |
| INV-SENS-002 | 암호화 → 복호화 결과는 항상 원문과 같다 (모든 입력, 모든 키 버전) | L2 | ADR 0005 | - | 제안 (TASK-004) |
| INV-SENS-003 | 암호문은 만든 자리(기관, 테이블, 컬럼, 행)에서만 복호화되고, 한 바이트라도 바뀌면 복호화에 실패한다 | L2, L5 | ADR 0005 | - | 제안 (TASK-004) |
| INV-SENS-004 | 검색용 HMAC은 같은 기관·같은 정규화 값이면 같고, 기관이 다르면 다르며, 원문을 담지 않는다 | L2 | ADR 0005 | - | 제안 (TASK-004) |
| INV-AUDIT-001 | 개인정보 조회·수정·다운로드는 빠짐없이 접속기록을 남긴다 | L2, L5 | ADR 0005 | - | 제안 (TASK-004: 이용자 조회부터) |
| INV-AUDIT-002 | 접속기록은 추가만 가능하다 (수정·삭제 불가) | L1(권한/트리거), L5 | ADR 0005 | - | 제안 (TASK-004) |
| INV-AUDIT-003 | 접속기록을 남기지 못하면 개인정보는 응답되지 않는다 | L2, L5 | ADR 0005 | - | 제안 (TASK-004) |
| INV-API-001 | 모든 에러 응답은 ProblemDetail 형식이며 개인정보를 포함하지 않는다 | L2, L4 | ADR 0006 | `AuthApiTest`, `SecurityProblemsTest`, `ParticipantApiTest` | 부분 확정 (TASK-002: 인증 오류 / TASK-003: 403·404·422) |
| INV-API-002 | 같은 Idempotency-Key로 반복된 요청은 부수효과가 한 번만 일어나고 같은 응답을 받는다 | L1(UNIQUE), L3, L5 | ADR 0006 | - | 제안 |
| INV-API-003 | If-Match 버전이 현재 버전과 다르면 수정은 일어나지 않는다 | L1(버전 컬럼), L2, L5 | ADR 0006 | - | 제안 |
| INV-API-004 | 페이지네이션 커서는 인코딩 후 디코딩하면 항상 원래 값과 같고, 변조된 커서는 항상 거부된다 | L2, L6 | ADR 0006 | `CursorCodecProperties` (성질 3개 각 1,000회 + 예제 6개), PIT 96% | 확정 (TASK-001) |
| INV-ARCH-001 | 컨트롤러는 리포지토리에 직접 의존하지 않는다. 권한 검사는 서비스 계층에서만 한다 | L4 | ADR 0004 | `ArchitectureTest` (리포지토리 의존, 웹 계층의 AccessPolicy·CurrentActor 의존 금지, 보안 어노테이션 금지, 이용자 저장소를 쓰는 서비스는 AccessPolicy 사용) | 확정 (TASK-001, TASK-003) |
| INV-ARCH-002 | 개인정보 저장소를 쓰는 서비스는 접속기록 기록기에도 의존하고, 웹 계층은 접속기록에 의존하지 않는다 | L4 | ADR 0005 | - | 제안 (TASK-004) |

## 파이프라인 확인용

| ID | 불변식 | 계층 | 근거 | 테스트 위치 | 상태 |
|---|---|---|---|---|---|
| INV-SMOKE-001 | 검증 파이프라인 확인용 최소 명세의 상태는 항상 0..2 범위 | L3 | ADR 0008 | `docs/spec/PipelineSmoke.tla` (`./gradlew tlcCheck`) | 확정 (TASK-001) |

## 모듈별 (인터뷰 후 모듈 확정 시 추가)

후보 모듈에서 예상되는 불변식. 모듈이 확정되면 ID를 붙여 위 표 형식으로 옮긴다.

- 정원: 어떤 프로그램도 확정 인원이 정원을 넘지 않는다 (동시 신청 포함)
- 대기 순번: 한 프로그램 안에서 대기 순번은 중복되지 않고, 앞 순번이 빠지면 다음 순번이 정확히 한 명 승계된다
- 중복 신청: 한 이용자는 같은 프로그램에 활성 신청을 최대 하나만 가진다
- 인계: 등·하원 인계는 지정된 보호자에게만 기록될 수 있다
- 사진 공유: 공개 동의가 유효하지 않은 이용자가 나온 사진은 보호자 화면에 노출되지 않는다
- 보유기간: 보유기간이 끝난 개인정보는 파기 배치 이후 조회되지 않는다

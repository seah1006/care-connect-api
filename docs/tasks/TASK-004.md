# TASK-004: 민감정보 암호화 기반과 접속기록

- 작성: Claude
- 날짜: 2026-10-31
- 브랜치: task/TASK-004

## 목표

어떤 모듈을 고르든 함께 쓸 민감정보 보호 기반을 만든다.
이 작업이 끝나면 컬럼 값을 키 버전이 붙은 AES-GCM 암호문으로 저장하고 읽을 수 있고, 정확히 일치하는 검색용 HMAC 값을 만들 수 있다.
이용자 조회(TASK-003 API)는 허용, 거부, 없음 결과를 빠짐없이 추가 전용 접속기록에 남기며, 기록을 남기지 못하면 이용자 데이터를 돌려주지 않는다.

## 범위

- `src/main/java/io/github/seah1006/careconnect/common/crypto/**` (새 패키지: 키 묶음, 필드 암호화, 검색용 HMAC)
- `src/main/java/io/github/seah1006/careconnect/common/audit/**` (새 패키지: 접속기록)
- `src/main/java/io/github/seah1006/careconnect/participant/application/**` (접속기록 연결만)
- `src/main/java/io/github/seah1006/careconnect/common/web/**` (접속기록 실패 응답이 필요할 때만)
- `src/main/resources/application.yml` (키 설정 항목 추가)
- `src/main/resources/db/migration/V4__*.sql`
- `src/test/**` (테스트 전용 마이그레이션은 `src/test/resources/db/testmigration/`)
- `src/test/java/io/github/seah1006/careconnect/PostgresSupport.java` (합성 키 설정 추가만)

허용 의존성: 없음. AES-GCM과 HMAC은 JDK `javax.crypto` 를 쓴다.

## 범위 밖

- 실제 민감 필드(장애 유형, 알레르기 등)에 암호화 적용 (INV-SENS-001): 인터뷰 후 항목이 정해지면 TASK-005
- 동의 기록(일반, 민감, 사진), 보유기간과 파기 배치: TASK-005
- 키 교체 시 기존 암호문을 새 키로 다시 암호화하는 배치: 읽기는 모든 버전을 지원하고, 재암호화 배치는 이후 작업
- 접속기록 조회 API, 접속기록 2년 경과분 파기 배치
- 클라우드 비밀값 관리 서비스 연동: 배포 방식(ADR 0007)이 정해진 뒤

## 영향받는 불변식

| 불변식 ID | 이번 작업에서 할 일 | 검증 계층 |
|---|---|---|
| INV-SENS-002 | 모든 입력, 모든 키 버전에서 암호화 → 복호화 = 원문 | L2 |
| INV-SENS-003 (신규) | 암호문은 만든 자리(기관, 테이블, 컬럼, 행)에서만 복호화된다. 한 바이트라도 바뀌면 복호화 실패 | L2, L5 |
| INV-SENS-004 (신규) | 검색용 HMAC은 같은 기관·같은 정규화 값이면 같고, 기관이 다르면 다르다. 원문은 들어 있지 않다 | L2 |
| INV-AUDIT-001 | 이용자 조회(단건, 목록)는 결과(허용, 거부, 없음)와 상관없이 접속기록을 남긴다 | L2, L5 |
| INV-AUDIT-002 | 접속기록은 추가만 가능하다 | L1, L5 |
| INV-AUDIT-003 (신규) | 접속기록을 남기지 못하면 이용자 데이터는 응답되지 않는다 | L2, L5 |
| INV-ARCH-002 (신규) | 이용자 저장소를 쓰는 서비스는 접속기록에도 의존한다 | L4 |
| INV-TEN-001, INV-TEN-002 | 접속기록 테이블도 기관 소유. RLS, `org_id not null` | L1, L4, L5 |
| INV-AUTHZ-002, 003, 005 | 기존 동작 유지 (응답 상태와 본문이 바뀌지 않음) | L2, L5 |

## 새로 추가하는 불변식

`docs/invariants/INVARIANTS.md` 에 "제안" 상태로 추가했다: INV-SENS-003, INV-SENS-004, INV-AUDIT-003, INV-ARCH-002.

## 관련 명세

- ADR 0001 (멀티테넌트), 0004 (권한, "권한 판단 결과는 접속기록에 남긴다"), 0005 (민감정보)
- TLA+: 해당 없음. 공유 상태를 두고 경쟁하는 흐름이 없다 (기록은 추가만 한다)
- ADR 0005와 다른 점: ADR은 JPA `AttributeConverter` 를 적었지만, 변환기는 행 ID와 기관을 알 수 없어 암호문을 자리에 묶을 수 없다 (INV-SENS-003). 그래서 저장소가 명시적인 자리 정보와 함께 암호화 API를 부른다. ADR 0005 확정 때 문구를 고친다

## 요구사항

### 1. 키 설정 (common.crypto)

- 환경 변수
  - `FIELD_ENCRYPTION_KEYS`: `버전:Base64키` 를 쉼표로 이어 붙인 값. 예: `1:AAAA...,2:BBBB...`. 키는 디코딩 후 정확히 32바이트 (AES-256)
  - `FIELD_ENCRYPTION_ACTIVE_VERSION`: 새로 암호화할 때 쓸 버전
  - `FIELD_INDEX_KEY`: 검색용 HMAC 키, Base64, 디코딩 후 32바이트 이상. 암호화 키와 같은 값이면 시작 실패
- `application.yml` 에 `security.field-encryption.keys`, `security.field-encryption.active-version`, `security.field-index-key` 로 연결
- 시작 시 검증 (하나라도 어기면 애플리케이션 시작 실패, 오류 메시지에 키 값을 넣지 않는다)
  - 형식 오류, 중복 버전, 버전은 1 이상 255 이하의 정수, 32바이트가 아닌 암호화 키
  - 활성 버전이 키 묶음에 없음
  - 검색용 키가 어떤 암호화 키와 같음
- `PostgresSupport` 와 테스트 설정에는 테스트 코드 안에서만 쓰는 합성 키를 넣는다

### 2. 필드 암호화 (common.crypto.FieldCipher)

- `String seal(String plaintext, FieldSlot slot)`, `String open(String sealed, FieldSlot slot)`
- `FieldSlot(UUID orgId, String table, String column, UUID rowId)`: 암호문을 묶을 자리
- 형식: `v{버전}.` + Base64url(패딩 없음)로 인코딩한 `nonce(12바이트) || 암호문 || 태그(16바이트)`
  - AES/GCM/NoPadding, 태그 128비트, nonce는 `SecureRandom` 으로 매번 새로 만든다
  - AAD = 자리 정보의 정규 인코딩 (기관 ID, 테이블, 컬럼, 행 ID를 길이 접두와 함께 이어 붙인다. 구분자 충돌로 다른 자리가 같은 AAD가 되면 안 된다)
- 복호화는 키 묶음의 모든 버전을 지원한다. 모르는 버전, 형식 오류, 태그 불일치, 자리 불일치는 모두 같은 예외 `FieldDecryptionFailed` (원인 구분 정보를 메시지에 넣지 않는다)
- `boolean needsRotation(String sealed)`: 활성 버전이 아니면 true
- 빈 문자열과 유니코드 전체(서로게이트 쌍 포함)를 지원한다. `null` 은 암호화하지 않고 `null` 로 둔다

### 3. 검색용 HMAC (common.crypto.BlindIndex)

- `String of(UUID orgId, String purpose, String normalizedValue)`: HMAC-SHA256(검색용 키, 기관 ID + 용도 + 값의 정규 인코딩), 결과는 64자 소문자 hex
- 정규화는 호출하는 쪽이 한다. 이번 작업에서는 휴대폰 번호용 `normalizePhone` (숫자만 남김) 하나만 제공한다
- 기관마다 결과가 달라서 기관 간에 같은 사람인지 비교할 수 없다

### 4. 접속기록 테이블 (V4)

- `access_log(id uuid, org_id uuid not null, actor_user_id uuid not null, action varchar(20), resource_type varchar(50), resource_id uuid, outcome varchar(20), occurred_at timestamptz not null, client_ip varchar(45), request_id uuid not null)`
  - `action` CHECK: `READ`, `LIST`, `UPDATE`, `DOWNLOAD`
  - `outcome` CHECK: `ALLOWED`, `DENIED`, `NOT_FOUND`
  - `org_id` 외래키, `(org_id, occurred_at)` 인덱스
  - RLS: V2·V3와 같은 형식 (`ENABLE` + `FORCE`)
- 추가 전용 (INV-AUDIT-002), 두 겹으로 막는다
  - 애플리케이션 계정에는 `select, insert` 만 준다 (`update`, `delete` 없음)
  - 트리거: `UPDATE` 는 항상 거부. `DELETE` 는 `occurred_at` 이 2년이 지나지 않은 행이면 거부 (소유자 계정도 거부됨). `TRUNCATE` 도 거부하는 문장 트리거를 둔다
- 접속기록은 개인정보 자체(이름, 생년월일 등)를 담지 않는다. 누가, 언제, 무엇을(리소스 종류와 ID), 결과, 어디서(IP)만 담는다

### 5. 접속기록 기록기 (common.audit.AccessLog)

- `record(AccessEvent event)`: 별도 트랜잭션(`REQUIRES_NEW`, `identityTransactionManager`)으로 즉시 커밋한다. 호출한 쪽 트랜잭션이 롤백되어도 기록은 남는다 (거부·없음 결과가 예외로 끝나도 기록이 사라지지 않게)
- 시각은 주입된 `Clock`, 요청 ID는 요청마다 하나 (같은 요청에서 남긴 기록은 같은 요청 ID)
- IP는 `HttpServletRequest.getRemoteAddr()` 만 쓴다. `X-Forwarded-For` 는 믿지 않는다 (프록시 설정은 배포 작업에서)
- 기록 실패(DB 오류 등)는 삼키지 않고 예외로 올린다

### 6. 이용자 조회에 연결 (participant.application)

- 단건 조회
  - 없음 또는 다른 기관 → `NOT_FOUND` 기록 후 404 (`resource_id` 는 요청한 ID)
  - 거부 → `DENIED` 기록 후 403
  - 허용 → `ALLOWED` 기록이 **커밋된 뒤에** 데이터를 돌려준다
- 목록 조회
  - 거부(소속 없음) → `LIST`, `DENIED`, `resource_id` 없음 1행
  - 허용 → 응답에 담기는 이용자마다 `LIST`, `ALLOWED` 1행. 빈 목록이면 `resource_id` 없는 1행
- 기록을 남기지 못하면 이용자 데이터 없이 실패한다 (INV-AUDIT-003). 응답은 ProblemDetail 500, 본문에 이용자 정보나 내부 오류 내용을 넣지 않는다
- TASK-003의 응답(상태 코드, 본문)은 바뀌지 않는다. 특히 없는 ID와 다른 기관 ID의 404 본문은 계속 바이트 단위로 같아야 한다

### 7. 테스트 (불변식 ID를 `@Tag` 로)

- INV-SENS-002 (L2): 무작위 문자열(빈 문자열, 서로게이트 쌍, 긴 문자열 포함), 무작위 키 묶음(1~5개 버전), 무작위 활성 버전으로 `open(seal(x)) == x`. 활성 버전을 바꾼 뒤에도 옛 암호문이 열린다. 1,000회 이상
- INV-SENS-003 (L2): 암호문의 아무 바이트 하나를 바꾸면 실패. 자리 정보 중 하나라도 다르면 실패. 다른 키 묶음으로 열면 실패. 실패 예외는 모두 같은 종류이고 메시지가 같다
  - AAD 인코딩: 무작위로 만든 서로 다른 두 자리의 AAD가 같은 경우가 없다 (예: 테이블 `"a_b"` + 컬럼 `"c"` 와 테이블 `"a"` + 컬럼 `"b_c"`)
- INV-SENS-003 (L5, `docker`): 테스트 전용 테이블 `sensitive_probe(id, org_id, secret)` 에 암호문을 저장한 뒤
  - DB 원시 값이 원문을 포함하지 않는다
  - 다른 행으로 암호문을 복사하면 그 행에서 열리지 않는다
- 암호문 성질 (L2): 같은 원문을 두 번 암호화하면 결과가 다르다. 암호문(Base64 디코딩 전후 모두)에 원문이 그대로 나타나지 않는다 (원문 길이 8 이상)
- INV-SENS-004 (L2): 같은 입력 → 같은 결과, 기관만 다르면 결과가 다름, 용도만 다르면 결과가 다름, 결과는 64자 hex, 결과에 원문이 없다. `normalizePhone` 은 하이픈·공백 차이를 없앤다
- 키 설정 (L2): 1번의 시작 실패 조건마다 실패하고, 실패 메시지에 키 값이 나타나지 않는다
- INV-AUDIT-001 (L5, `docker`): TASK-003의 무작위 배치 테스트와 같은 방식으로 직원마다 단건·목록을 호출한 뒤, 요청마다 기대하는 기록(행위, 결과, 리소스 ID)과 실제 기록 집합이 정확히 같다. 다른 기관 ID 조회는 요청한 직원의 기관에 `NOT_FOUND` 로 남는다
- INV-AUDIT-001 (L2): 서비스 단위에서 저장소와 기록기를 메모리 구현으로 바꿔 끼우고, 무작위 사실(역할, 배정, 대상)에 대해 결과마다 기록이 정확히 하나(목록은 응답 이용자 수만큼) 생긴다
- INV-AUDIT-002 (L1 + L5, `docker`)
  - 애플리케이션 계정의 `UPDATE`, `DELETE` 는 권한 오류 (`42501`)
  - 소유자 계정의 `UPDATE`, 2년이 안 된 행 `DELETE`, `TRUNCATE` 는 트리거가 거부
  - 2년이 지난 행(소유자 계정으로 직접 넣은 합성 행)은 소유자 계정으로 삭제할 수 있다
- INV-AUDIT-003 (L2 + L5): 기록기가 실패하게 만들면 (L2: 실패하는 메모리 기록기, L5: 테스트에서 애플리케이션 계정의 `access_log` insert 권한을 잠시 회수) 단건·목록 모두 응답에 이용자 데이터가 없고 상태는 500, 본문은 ProblemDetail. 테스트가 끝나면 권한을 되돌린다
- 기록이 호출한 쪽 롤백과 무관하게 남는지 (L5): 거부(403)와 없음(404) 뒤에도 기록 행이 있다
- INV-ARCH-002 (L4): `participant.repository` 에 의존하는 `..application..` 클래스는 `common.audit.AccessLog` 에도 의존한다. `..web..` 은 `common.audit` 에 의존하지 않는다
- INV-TEN-001 (L5): `access_log` RLS 확인 (기존 방식과 같게)

## 완료 조건

- [ ] 위 불변식마다 지정된 계층의 테스트가 있고 통과한다
- [ ] `./gradlew check -PskipDockerTests`, `./gradlew pitest`(80% 이상), `./gradlew tlcCheck` 통과
- [ ] Docker 테스트는 컴파일 확인 후 보고서에 "리뷰어 실행 필요" 명시
- [ ] 음성 확인 4가지를 직접 해 보고 되돌린다 (Docker가 필요하면 "리뷰어 확인 필요")
  1. AAD를 빼고 암호화하면 어떤 테스트가 실패하는지
  2. nonce를 고정값으로 바꾸면 어떤 테스트가 실패하는지
  3. 단건 조회에서 거부일 때 기록을 빼먹으면 어떤 테스트가 실패하는지
  4. 기록 실패를 삼키고 데이터를 돌려주면 어떤 테스트가 실패하는지
- [ ] 비밀값·실제 개인정보 없음 (테스트 키와 값은 합성값, 테스트 코드 안에서만)
- [ ] 범위 밖 변경 없음, 기존 테스트 약화 없음

## 실행 환경 메모

- TASK-003과 같음. Docker는 샌드박스에서 접근 불가 → `docker` 태그 + `-PskipDockerTests`
- 저장소는 영문 경로(`C:\Users\my\dev\care-connect-api`)에 있다. 한글 경로에서는 PIT가 0%로 실패한다

## 리뷰 기록

Claude가 리뷰 후 지적 사항을 회차별로 추가한다.

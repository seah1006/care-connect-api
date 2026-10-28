# RefreshTokenRotation 검사 기록

명세: `RefreshTokenRotation.tla` / 설정: `RefreshTokenRotation.cfg`
관련: ADR 0003, INV-AUTH-001, INV-AUTH-002

## 결과 (2026-10-28, TLC 1.7.4)

| 설정 | 결과 |
|---|---|
| `UseCAS = TRUE` (조건부 UPDATE) | 위반 없음. 상태 393개 생성, 고유 상태 160개, 탐색 깊이 11 |
| `UseCAS = FALSE` (읽은 값만 믿고 쓰기) | **INV_AUTH_001 위반**. 5단계 반례 |

## 순진한 구현의 반례

클라이언트 c1(정상 사용자)과 c2(토큰을 훔친 공격자)가 같은 토큰 1을 가진 상황.

| 단계 | 일어난 일 | 토큰 상태 | 교환 기록 |
|---|---|---|---|
| 1 | 초기 상태 | 1: active | 없음 |
| 2 | c1이 토큰 1로 refresh 요청, 서버가 상태를 읽음: active | 1: active | 없음 |
| 3 | c2도 토큰 1로 refresh 요청, 서버가 상태를 읽음: active | 1: active | 없음 |
| 4 | c1 요청 처리: 토큰 1을 used로, 토큰 2 발급 | 1: used, 2: active | 1 → {2} |
| 5 | c2 요청 처리: 읽어 둔 값(active)만 믿고 토큰 3 발급 | 1: used, 2: active, 3: active | 1 → {2, 3} |

결과: 토큰 하나가 두 번 교환되고, 살아 있는 토큰이 둘이 된다. 공격자가 정상 사용자와 동시에 자기 토큰 묶음을 확보한다.
재사용 감지도 일어나지 않는다. 두 요청 모두 "active"를 읽었기 때문이다.

## 구현이 지켜야 할 것

- 토큰 사용 처리는 `UPDATE refresh_token SET status = 'USED' ... WHERE token_hash = ? AND status = 'ACTIVE'` 처럼
  **상태 조건이 붙은 한 번의 쓰기**로 한다. 영향받은 행이 1일 때만 새 토큰을 발급한다.
- 영향받은 행이 0이고 현재 상태가 USED이면 재사용으로 보고 묶음 전체를 폐기한다.
- 같은 시나리오를 실제 PostgreSQL과 다중 스레드로 재현하는 동시성 통합 테스트(L5)를 둔다.

## 다시 검사하는 법

```bash
./gradlew tlcCheck
```

반례를 직접 보려면 `.cfg`를 복사해 `UseCAS = FALSE`로 바꾼 뒤 TLC를 실행한다 (저장소의 `.cfg`는 TRUE로 유지).

## 구현 대응 확인 (2026-10-28, TASK-002)

- 구현: `AuthService.refresh` + `JdbcAuthStore.markUsed` (조건부 UPDATE, 묶음 단위 advisory lock 추가)
- L5: 8개 스레드가 같은 토큰으로 동시에 refresh, 20개 묶음 반복. 통과
- 음성 확인: `AND status='ACTIVE'` 를 빼면 동시성 테스트와 순차 재사용 테스트가 실패하고, DB 유니크 인덱스 `refresh_token_single_active` 가 두 번째 ACTIVE 토큰 삽입을 거부했다. 모델의 `UseCAS = FALSE` 반례가 실제 코드에서도 재현되고, L1(DB 제약)이 마지막 방어선으로 동작함을 확인

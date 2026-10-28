---- MODULE RefreshTokenRotation ----
\* refresh 토큰 교체(rotation)와 재사용 감지 모델. ADR 0003, INV-AUTH-001, INV-AUTH-002.
\*
\* 모델링 대상
\* - 토큰 묶음(family) 하나. 처음 발급된 토큰 1을 모든 클라이언트가 가진다
\*   (정상 사용자와, 토큰을 훔친 공격자가 같은 토큰을 가진 상황).
\* - 서버의 refresh 처리는 두 단계로 나뉜다.
\*     Begin  : 토큰 상태를 읽는다 (트랜잭션 시작 후 SELECT)
\*     Finish : 읽은 상태를 바탕으로 쓴다 (UPDATE)
\*   두 단계 사이에 다른 요청이 끼어들 수 있다. 이 끼어들기가 동시성 버그의 원인이다.
\* - UseCAS = TRUE  : Finish의 쓰기가 "status = active 인 경우에만" 성공하는 조건부 UPDATE
\*                    (UPDATE ... WHERE token_hash = ? AND status = 'ACTIVE'), 구현이 따라야 하는 방식
\*   UseCAS = FALSE : 읽은 값만 믿고 무조건 쓰는 순진한 구현.
\*                    TLC가 INV_AUTH_001 위반 반례를 찾는다 (두 요청이 같은 토큰을 각각 교환).
\*
\* 검사 범위: 클라이언트 2명, 토큰 최대 4개. 동시 요청 2개가 같은 토큰을 다투는 최소 상황과
\* 재사용 감지 이후의 요청까지 포함하므로, 이 범위에서 위반이 없으면 쌍 단위 경쟁에 대해 안전하다.

EXTENDS Naturals, FiniteSets

CONSTANTS Clients, MaxTokens, UseCAS

VARIABLES status, holds, children, reqs, nextTok, reuse

vars == <<status, holds, children, reqs, nextTok, reuse>>

Tokens == 1..MaxTokens
Statuses == {"unissued", "active", "used", "revoked"}

TypeOK ==
    /\ status \in [Tokens -> Statuses]
    /\ holds \in [Clients -> SUBSET Tokens]
    /\ children \in [Tokens -> SUBSET Tokens]
    /\ \A r \in reqs : r.c \in Clients /\ r.t \in Tokens /\ r.seen \in Statuses
    /\ nextTok \in 2..(MaxTokens + 1)
    /\ reuse \in BOOLEAN

Init ==
    /\ status = [t \in Tokens |-> IF t = 1 THEN "active" ELSE "unissued"]
    /\ holds = [c \in Clients |-> {1}]
    /\ children = [t \in Tokens |-> {}]
    /\ reqs = {}
    /\ nextTok = 2
    /\ reuse = FALSE

\* 묶음 전체 폐기: 살아 있는 토큰을 모두 revoked로
RevokeFamily ==
    /\ status' = [t \in Tokens |-> IF status[t] = "active" THEN "revoked" ELSE status[t]]
    /\ reuse' = TRUE
    /\ UNCHANGED <<holds, children, nextTok>>

\* 새 토큰 발급과 기존 토큰 사용 처리
Exchange(r) ==
    /\ nextTok <= MaxTokens
    /\ status' = [status EXCEPT ![r.t] = "used", ![nextTok] = "active"]
    /\ children' = [children EXCEPT ![r.t] = @ \cup {nextTok}]
    /\ holds' = [holds EXCEPT ![r.c] = @ \cup {nextTok}]
    /\ nextTok' = nextTok + 1
    /\ UNCHANGED reuse

Begin(c, t) ==
    /\ t \in holds[c]
    /\ ~\E r \in reqs : r.c = c
    /\ reqs' = reqs \cup {[c |-> c, t |-> t, seen |-> status[t]]}
    /\ UNCHANGED <<status, holds, children, nextTok, reuse>>

Finish(r) ==
    /\ r \in reqs
    /\ reqs' = reqs \ {r}
    /\ CASE r.seen = "active" ->
              IF UseCAS
              THEN IF status[r.t] = "active"
                   THEN \/ Exchange(r)
                        \/ (nextTok > MaxTokens /\ UNCHANGED <<status, holds, children, nextTok, reuse>>)
                   ELSE IF status[r.t] = "used"
                        THEN RevokeFamily
                        ELSE UNCHANGED <<status, holds, children, nextTok, reuse>>
              ELSE \/ Exchange(r)
                   \/ (nextTok > MaxTokens /\ UNCHANGED <<status, holds, children, nextTok, reuse>>)
         [] r.seen = "used" -> RevokeFamily
         [] OTHER -> UNCHANGED <<status, holds, children, nextTok, reuse>>

Next ==
    \/ \E c \in Clients, t \in Tokens : Begin(c, t)
    \/ \E r \in reqs : Finish(r)

Spec == Init /\ [][Next]_vars

\* INV-AUTH-001: 하나의 refresh 토큰은 최대 한 번만 새 토큰으로 교환된다
INV_AUTH_001 == \A t \in Tokens : Cardinality(children[t]) <= 1

\* INV-AUTH-001 보조: 한 묶음에 살아 있는 토큰은 언제나 최대 하나
INV_AUTH_001_SingleActive == Cardinality({t \in Tokens : status[t] = "active"}) <= 1

\* INV-AUTH-002: 재사용이 감지되면 그 묶음에 살아 있는 토큰은 하나도 없다 (이후에도 계속)
INV_AUTH_002 == reuse => \A t \in Tokens : status[t] # "active"
====

---- MODULE PipelineSmoke ----
\* 검증 파이프라인(tlcCheck) 동작 확인용 최소 명세.
\* 도메인 의미는 없다. 실제 흐름 명세가 추가되면 지워도 된다.
\* 범위: 상태 3개뿐이라 전수 탐색이 즉시 끝난다.
EXTENDS Naturals

VARIABLE x

Init == x = 0
Next == x' = (x + 1) % 3

INV_SMOKE_001 == x \in 0..2

Spec == Init /\ [][Next]_x
====

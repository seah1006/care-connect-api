# 0002. 기술 스택과 DB: Spring Boot + PostgreSQL

- 상태: 확정 (2026-10-28)
- 날짜: 2026-10-28

## 맥락

백엔드 역량을 보여주는 것이 목표이고, 개발자는 Spring 생태계에 익숙하다.
DB는 멀티테넌트 격리(0001), 민감정보 보호(0005), 동의 기록 같은 반정형 데이터를 다뤄야 한다.

## 선택지

| 항목 | 후보 | 비교 |
|---|---|---|
| 언어·프레임워크 | Java + Spring Boot | 익숙함, Spring Security와 JPA로 인증·권한·영속성을 일관되게 처리 |
| DB | MySQL | 익숙하고 자료가 많음. 행 수준 보안 기능이 없음 |
| DB | PostgreSQL | 행 수준 보안(RLS), 부분 인덱스, JSONB 지원. MySQL보다 덜 익숙함 |

## 결정

- **Java 25 (LTS)** 와 Spring Boot 최신 안정 버전. Spring Boot는 **4.1.2** (2026-10-28 프로젝트 생성 시점의 최신 안정 버전), Gradle Wrapper 9.7.1.
- 빌드: Gradle (Kotlin DSL). JDK는 Gradle toolchain으로 25에 고정하고, 없으면 자동으로 내려받게 한다(foojay toolchain resolver). 개발자 PC에 어떤 JDK가 깔려 있든 빌드 결과가 같아야 하기 때문이다.
- DB: **PostgreSQL**
  - 0001의 2차 방어선인 행 수준 보안을 DB가 직접 지원한다.
  - "대기 중인 신청만" 같은 조건부 인덱스(부분 인덱스)로 대기열 조회를 가볍게 만들 수 있다.
  - 동의 항목처럼 기관마다 조금씩 다른 데이터는 JSONB로 유연하게 저장할 수 있다.
- 마이그레이션: Flyway (스키마 변경 이력을 코드로 관리)
- 테스트: JUnit 5, Testcontainers(실제 PostgreSQL로 통합 테스트)
- 문서: Spring REST Docs 또는 OpenAPI. 0006에서 정한다.

## 결과

- 얻는 것: 격리와 보안 요구를 DB 기능으로 함께 해결한다. Testcontainers로 운영과 같은 DB에서 테스트한다.
- 감수하는 것: PostgreSQL 학습 비용. 개발 PC 기본 JDK(26, LTS 아님)는 그대로 두고, 프로젝트는 toolchain으로 25를 쓴다. IDE도 프로젝트 SDK를 25로 맞춰야 한다.

## 다시 볼 조건

- 배포 환경에서 PostgreSQL 관리형 서비스 비용이 부담될 때
- RLS가 성능이나 개발 속도에 큰 문제를 일으킬 때

# inops-outbox-core

Outbox 패턴 라이브러리. MyBatis / JPA(Hibernate) 환경을 자동 감지하여 DML 이벤트를 캡처하고 폐쇄망으로 파일 동기화합니다.

## 개요

```
내부망 서비스
  └─ DML 실행 (MyBatis Interceptor 또는 JPA PostInsert/Update/Delete 리스너)
       └─ OutboxEventFlusher.capture()
            └─ outbox 테이블 저장 (beforeCommit 훅, 비즈니스 트랜잭션 내)
                 └─ OutboxScheduler 배치
                      └─ sync_*.json.gz 파일 생성
                           └─ 폐쇄망 수신 서버 처리
```

- **자동 감지**: `outbox.tables` 에 등록된 테이블의 INSERT/UPDATE/DELETE 자동 캡처
- **이중 지원**: MyBatis `Executor.update()` 인터셉터 + JPA `PostInsert/Update/Delete` 이벤트 리스너 (둘 다 활성화 가능)
- **트랜잭션 원자성**: `TransactionSynchronization.beforeCommit` 훅으로 비즈니스 트랜잭션과 같은 커밋 단위에 합류
- **이중 트리거**: 건수(`batch.size`) 또는 시간(`batch.time-trigger-ms`) 조건 중 먼저 충족 시 배치 실행
- **gzip 파일 출력**: `sync_{seqFrom}_{seqTo}_{timestamp}.json.gz` 형식으로 압축 저장
- **루프 방지**: `OutboxContext.runSuppressed(...)` 로 폐쇄망 수신 이벤트 재발행 차단

---

## 요구사항

| 항목 | 버전 |
|------|------|
| Java | 17+ |
| Spring Boot | 3.1+ |
| MyBatis Spring Boot Starter | 3.0+ (MyBatis 사용 시) |
| Hibernate | 6.x+ (JPA 사용 시) |
| PostgreSQL / MySQL / MariaDB | (스키마 적용 필요) |

`spring-boot-starter-aop`, `spring-boot-starter-jdbc`, `spring-boot-starter-data-jpa`, `mybatis-spring-boot-starter`, `jackson-databind` 의존성은 모두 `optional`. 사용 환경에 맞게 호스트 애플리케이션 측에서 직접 가져옵니다.

---

## 설치

`pom.xml`에 의존성 추가:

```xml
<dependency>
    <groupId>re.kr.inspace.web</groupId>
    <artifactId>inops-outbox-core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Spring Boot Auto-Configuration(`OutboxAutoConfig`)이 등록되어 있으므로 별도 `@EnableXxx` 없이 자동 활성화됩니다.

---

## DB 스키마

### PostgreSQL

```sql
CREATE SEQUENCE IF NOT EXISTS outbox_seq_seq START 1;

CREATE TABLE IF NOT EXISTS outbox (
    id         BIGSERIAL   PRIMARY KEY,
    seq        BIGINT      NOT NULL DEFAULT NEXTVAL('outbox_seq_seq'),
    domain     VARCHAR(50) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    source     VARCHAR(20) NOT NULL,   -- INTERNAL | CLOSED_NET
    payload    JSONB,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    sent_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_seq     ON outbox (status, seq ASC);
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox (status, created_at);
```

전체 DDL 은 [`src/main/resources/sql/outbox-schema.sql`](src/main/resources/sql/outbox-schema.sql) 참고.

### MySQL / MariaDB

```sql
CREATE TABLE IF NOT EXISTS outbox (
    id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
    domain     VARCHAR(50) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    source     VARCHAR(20) NOT NULL,
    payload    JSON,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME    NOT NULL DEFAULT NOW(),
    sent_at    DATETIME
);

CREATE INDEX idx_outbox_status_id ON outbox (status, id ASC);
```

> MySQL 방언은 별도 `seq` 컬럼을 사용하지 않습니다. `id` 가 시퀀스 역할을 겸하며 배치 픽업·파일 헤더에 사용됩니다.

폐쇄망 수신 서버 측 중복 처리 테이블:

```sql
CREATE TABLE IF NOT EXISTS processed_seq (
    seq          BIGINT    PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## 설정

`application.yml`:

```yaml
outbox:
  # 필수 — 감시할 테이블 목록 (스키마 prefix 제외, 소문자 비교)
  tables:
    - user
    - order_item
    - product_category

  # 필수 — gzip 파일 저장 경로 (기본값은 Windows 경로라 운영 환경에선 반드시 지정)
  file:
    path: /data/outbox

  # 선택 — 미지정 시 DataSource URL 에서 자동 감지 (postgresql | mysql | mariadb)
  # dialect: postgresql
  # sequence-name: outbox_seq_seq   # PostgreSQL 시퀀스명 (기본값)

  # 선택 — 배치 트리거 (모두 기본값 합리적)
  # batch:
  #   size: 1000              # 건수 트리거 임계값
  #   time-trigger-ms: 60000  # 시간 트리거 간격 (60초)
  #   check-interval-ms: 5000 # 스케줄러 폴링 간격 (5초)
```

> **dialect 자동 감지**: `spring.datasource.url` 이 `jdbc:postgresql:` / `jdbc:mysql:` / `jdbc:mariadb:` 로 시작하면 자동으로 해당 방언 선택. 감지 실패 시 `postgresql` 폴백. 명시 설정은 우선 적용.

---

## 어노테이션

### `@OutboxDomain` — 클래스 레벨

```java
// 해당 서비스의 모든 이벤트 캡처 비활성화 (auto-detect 패턴 무시)
@OutboxDomain(enabled = false)
@Service
public class InternalSyncService { ... }

// 도메인명 직접 지정 (auto-detect 가 추론하는 테이블명과 다르게 두고 싶을 때)
@OutboxDomain("USER_MGMT")
@Service
public class McUserService { ... }
```

### `@OutboxEvent` — 메서드 레벨

```java
@Service
public class OrderService {

    // 이 메서드만 캡처에서 제외 (다른 메서드의 이벤트는 유지)
    @OutboxEvent(enabled = false)
    public void internalSync(Order order) { ... }

    // 이벤트 타입 직접 지정 (기본값: SQL 타입에서 CREATED/UPDATED/DELETED 자동 추론)
    @OutboxEvent(eventType = "BULK_UPDATED")
    public void bulkUpdate(List<Order> orders) { ... }
}
```

---

## 캡처 동작

### MyBatis (`OutboxInterceptor`)

`Executor.update()` 를 인터셉트해 mapper 네임스페이스에서 테이블명을 추론합니다.

```
com.example.mapper.OrderItemMapper  → order_item
com.example.mapper.UserMapper       → user
```

규칙: 마지막 패키지의 클래스명 끝에서 `Mapper` 를 제거하고 CamelCase 를 snake_case 로 변환.
이 결과가 `outbox.tables` 에 포함되어 있어야 캡처됩니다.

이벤트 타입: `INSERT → CREATED`, `UPDATE → UPDATED`, `DELETE → DELETED`, 그 외 `CHANGED`.

### JPA / Hibernate (`HibernateOutboxListener`)

`PostInsert/Update/DeleteEvent` 를 받아 `EntityPersister.getTableName()` 으로 테이블명을 얻고 캡처합니다. `OutboxHibernateIntegrator` 가 `HibernatePropertiesCustomizer` 를 통해 `hibernate.integrator_provider` 로 등록됩니다.

> MyBatis 와 JPA 어댑터는 둘 다 활성화할 수 있습니다. 같은 트랜잭션에서 발생한 이벤트는 `OutboxContext`(ThreadLocal) 에 누적되어 `beforeCommit` 시점에 한 번에 INSERT 됩니다.

---

## 커스텀 컨버터

기본 컨버터(`DefaultOutboxConverter`)는 엔티티를 JSON 직렬화하며 민감 필드(`password`, `passwd`, `secret`, `token`, `credential`, `accessToken`, `refreshToken`, `apiKey`, `privateKey`)를 자동 제외합니다.

도메인별로 payload 를 커스터마이징하려면 `OutboxConverter`(spi 패키지)를 구현하고 빈 이름 규칙을 따릅니다.

**빈 이름 규칙**: `{도메인 소문자, 언더스코어 제거}OutboxConverter`

| 도메인 (테이블명 대문자) | 빈 이름 |
|--------------------------|---------|
| `ORDER`                  | `orderOutboxConverter` |
| `ORDER_ITEM`             | `orderitemOutboxConverter` |
| `PRODUCT_CATEGORY`       | `productcategoryOutboxConverter` |

```java
@Component("orderitemOutboxConverter")
public class OrderItemOutboxConverter implements OutboxConverter {

    @Override
    public Outbox convert(Object entity, String domain, String eventType) {
        OrderItem item = (OrderItem) entity;
        return Outbox.builder()
                .domain(domain)
                .eventType(eventType)
                .source("INTERNAL")
                .payload(toJson(item))
                .build();
    }
}
```

매칭되는 빈이 없으면 `defaultOutboxConverter` 로 폴백합니다.

---

## 커스텀 저장소

기본 저장소는 JDBC (`JdbcOutboxStore`) 이며 JPA / MyBatis 어떤 ORM 프로젝트에서도 그대로 동작합니다 — `JdbcTemplate` 이 활성 트랜잭션의 Connection 을 공유하므로 비즈니스 tx 와 같은 commit 에 합류합니다.

별도 영속성 기술(예: 다중 DataSource 라우팅, NoSQL 등)을 쓰려면 `OutboxStore`(spi 패키지)를 구현해 빈으로 등록하면 자동 구성이 비활성화됩니다.

```java
@Component
public class CustomOutboxStore implements OutboxStore {
    // void saveAll(List<Outbox>);
    // long countPending();
    // int processBatch(int limit, Consumer<List<Outbox>> handler);
    // void deleteOldSent();
}
```

> **권장 진입점**: 락+처리+마킹을 한 트랜잭션으로 묶는 `processBatch` 만 호출하면 됨. 락 메커니즘은 구현체가 캡슐화. 사용자는 비즈니스 의도(limit + handler) 만 신경 쓰면 됩니다.

---

## 커스텀 SQL 방언

기본은 DataSource URL 에서 자동 감지(postgresql/mysql/mariadb). `outbox.dialect` 프로퍼티로 명시 override 가능. 직접 `OutboxDialect` 빈을 등록하면 자동 구성이 비활성화됩니다.

```java
@Component
public class OracleDialect implements OutboxDialect {
    @Override public String insertSql() { ... }
    @Override public String selectPendingWithLockSql() { ... }
    @Override public String deleteOldSentSql() { ... }
    // 나머지(count, markSent, markFailed) 는 default 구현 사용 가능
}
```

---

## 루프 방지 (양방향 동기화)

폐쇄망에서 수신한 이벤트를 다시 DB 에 반영할 때 Outbox 가 또 캡처해 무한 발행되는 것을 막으려면 `OutboxContext.runSuppressed(...)` 로 감쌉니다.

```java
public void applyFromClosedNet(OrderPayload payload) {
    OutboxContext.runSuppressed(() -> orderService.upsert(payload));
}
```

블록 내부에서 발생하는 모든 MyBatis/JPA DML 은 Outbox 캡처 대상에서 제외됩니다.

---

## 출력 파일 형식

```json
{
  "meta": {
    "seq_from": 1001,
    "seq_to":   1500,
    "source":   "INTERNAL",
    "created_at": "2026-04-24T10:30:00Z"
  },
  "events": [
    {
      "seq":        1001,
      "domain":     "USER",
      "event_type": "UPDATED",
      "source":     "INTERNAL",
      "payload":    { ... }
    }
  ]
}
```

파일명: `sync_{seqFrom}_{seqTo}_{yyyyMMdd'T'HHmmss'Z'}.json.gz`

---

## Outbox 상태

| 상태 | 설명 |
|------|------|
| `PENDING` | 캡처 완료, 배치 대기 중 |
| `SENT` | 파일 변환 완료 (7일 후 자동 삭제) |
| `FAILED` | 파일 쓰기 실패 |

SENT 상태 레코드는 매일 02:00 (`cron = "0 0 2 * * *"`) 에 7일 초과분 자동 정리됩니다.

---

## ⚠️ 운영 주의 — seq 갭은 정상 동작

**PostgreSQL 시퀀스(`NEXTVAL`)는 트랜잭션 롤백 시 소비된 값을 되돌리지 않습니다.** 따라서 outbox 의 `seq` 값에 빈 칸이 자연스럽게 발생합니다:

```
운영 중 정상 상태:
seq = 1, 2, 3, 5, 8, 9, 12, ...
       ↑     ↑     ↑↑      ← 4, 6, 7, 10, 11 은 롤백된 tx 의 자취
```

(MySQL 도 `AUTO_INCREMENT` 가 동일한 특성. id 가 seq 역할이라 같은 문제.)

### 수신 서버 설계 권장사항

| 잘못된 가정 | 올바른 접근 |
|---|---|
| "seq=N 다음은 N+1 가 와야 함" | seq 는 **단조 증가** 하지만 연속은 아님 |
| seq 누락으로 누락 감지 | `created_at` 시간 윈도우로 감지 (예: "최근 30분간 새 이벤트 없음" 알람) |
| seq 비교로 중복 판단 | `processed_seq` 테이블 + `INSERT ... ON CONFLICT DO NOTHING` 으로 멱등 처리 |
| seq=마지막+1 대기 | 항상 `seq > last_processed_seq` 조건으로 새 이벤트 픽업 |

### 수신측 멱등 처리 예시

```sql
-- 수신측 outbox 처리 로직
BEGIN;

-- 1. 중복 방지 (이미 처리한 seq 면 무시)
INSERT INTO processed_seq (seq) VALUES (:seq) ON CONFLICT DO NOTHING;

-- 2. 비즈니스 데이터 멱등 upsert
INSERT INTO orders (id, name, updated_at) VALUES (:id, :name, :updatedAt)
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name, updated_at = EXCLUDED.updated_at
WHERE orders.updated_at < EXCLUDED.updated_at;   -- 더 최신일 때만 덮어씀

COMMIT;
```

같은 이벤트를 두 번 받아도 안전. seq 갭이 있어도 무관.

### "정말 누락" 인지 어떻게 알지?

운영 모니터링 (예시):

```sql
-- 최근 30분 동안 새 이벤트가 없으면 알람
SELECT COUNT(*) FROM outbox
WHERE created_at > NOW() - INTERVAL '30 minutes';
-- 결과가 0 이면 발신측 문제 (수신측이 못 받는 게 아님)

-- 발신측에 PENDING 이 30분 이상 정체되어 있으면 알람
SELECT COUNT(*), MIN(created_at) FROM outbox
WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '30 minutes';
```

→ **seq 연속성을 알람 기준으로 쓰지 말 것**.

---

## 핵심 구현 상세

### TransactionSynchronization — 트랜잭션 원자성 보장

DML 감지 즉시 DB 에 쓰지 않고 Spring 의 `TransactionSynchronization` 을 등록해 비즈니스 트랜잭션과 같은 커밋 단위로 합류합니다.

```
비즈니스 로직 실행
  └─ DML 감지 → 이벤트를 ThreadLocal(OutboxContextData) 에 적재
       └─ beforeCommit() 호출 → outbox 테이블에 batch INSERT
            └─ 비즈니스 트랜잭션 커밋
                 └─ 비즈니스 데이터 + outbox 동시 커밋 (원자적)
```

트랜잭션 컨텍스트 밖에서 DML 이 발생하면 즉시 flush 합니다(단, 비-트랜잭션 호출은 비정상 경로로 권장하지 않음).

### FOR UPDATE SKIP LOCKED — 다중 인스턴스 배치 중복 방지

```sql
-- PostgreSQL: ORDER BY seq
SELECT ... FROM outbox WHERE status = 'PENDING'
ORDER BY seq ASC LIMIT :limit FOR UPDATE SKIP LOCKED

-- MySQL: ORDER BY id
SELECT ... FROM outbox WHERE status = 'PENDING'
ORDER BY id  ASC LIMIT :limit FOR UPDATE SKIP LOCKED
```

인스턴스를 여러 개 띄워도 각 인스턴스가 겹치지 않는 배치를 가져가므로 별도 분산 락 없이 수평 확장이 가능합니다.

---

## 패키지 구조

```
io.github.ahnyeongjun.outbox
├── annotation     @OutboxDomain, @OutboxEvent
├── capture        OutboxAspect, OutboxContext, OutboxEventFlusher, DefaultOutboxConverter
│   ├── mybatis    OutboxInterceptor                          (이벤트 캡처 — MyBatis)
│   └── jpa        HibernateOutboxListener, OutboxHibernateIntegrator  (이벤트 캡처 — JPA)
├── store          JdbcOutboxStore, OutboxDialect, PostgreSQLDialect, MySQLDialect
├── publish        OutboxScheduler, OutboxFileWriter
├── model          Outbox, OutboxStatus
├── spi            OutboxStore, OutboxConverter   ← 사용자 확장 포인트
└── config         OutboxAutoConfig, OutboxProperties
```

**책임별로 분리**:
- `capture/*` — "어떻게 outbox 이벤트를 잡아내는가" (MyBatis 인터셉터 / JPA Hibernate 리스너)
- `store/` — "어디에/어떻게 영속화하는가" (JDBC + dialect)
- `publish/` — "outbox 를 어떻게 외부로 내보내는가" (스케줄러 + gzip 파일)
- `spi/` — 사용자 확장 인터페이스 (커스텀 컨버터/스토어)

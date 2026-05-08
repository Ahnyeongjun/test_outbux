# outbox-core

Outbox 패턴 라이브러리. MyBatis / JPA(Hibernate) 환경을 자동 감지하여 DML 이벤트를 캡처하고 폐쇄망으로 파일 동기화합니다.

## 개요

```
내부망 서비스
  └─ DML 실행 (MyBatis Interceptor 또는 JPA Event Listener)
       └─ outbox 테이블 저장 (beforeCommit 훅, 비즈니스 트랜잭션 내)
            └─ OutboxScheduler 배치
                 └─ sync_*.json.gz 파일 생성
                      └─ 폐쇄망 수신 서버 처리
```

- **자동 감지**: `outbox.tables`에 등록된 테이블의 INSERT/UPDATE/DELETE 자동 캡처
- **이중 지원**: MyBatis `Executor.update()` 인터셉터 + JPA `PostInsert/Update/Delete` 이벤트 리스너
- **트랜잭션 보장**: `beforeCommit` 훅으로 비즈니스 트랜잭션과 원자적으로 저장
- **이중 트리거**: 건수(`batch.size`) 또는 시간(`batch.time-trigger-ms`) 조건 중 먼저 충족 시 배치 실행
- **gzip 파일 출력**: `sync_{seqFrom}_{seqTo}_{timestamp}.json.gz` 형식으로 압축 저장

---

## 요구사항

| 항목 | 버전 |
|------|------|
| Java | 17+ |
| Spring Boot | 3.1+ |
| MyBatis Spring Boot Starter | 3.0+ (MyBatis 사용 시) |
| Hibernate | 6.x+ (JPA 사용 시) |
| PostgreSQL / MySQL / MariaDB | (스키마 적용 필요) |

---

## 설치

`pom.xml`에 의존성 추가:

```xml
<dependency>
    <groupId>io.github.ahnyeongjun</groupId>
    <artifactId>outbox-core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Spring Boot Auto-Configuration으로 별도 `@EnableXxx` 없이 자동 등록됩니다.

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
    source     VARCHAR(20) NOT NULL,
    payload    JSONB,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    sent_at    TIMESTAMP
);
```

### MySQL / MariaDB

```sql
CREATE TABLE IF NOT EXISTS outbox (
    id         BIGINT      PRIMARY KEY AUTO_INCREMENT,
    seq        BIGINT      NOT NULL AUTO_INCREMENT,
    domain     VARCHAR(50) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    source     VARCHAR(20) NOT NULL,
    payload    JSON,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME    NOT NULL DEFAULT NOW(),
    sent_at    DATETIME
);
```

폐쇄망 수신 서버에는 중복 처리용 테이블도 필요합니다:

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
  # 감시할 테이블 목록 (스키마 prefix 제외)
  tables:
    - user
    - order_item
    - product_category

  dialect: postgresql     # postgresql(기본값) | mysql | mariadb
  sequence-name: outbox_seq_seq  # PostgreSQL 시퀀스명 (기본값)

  file:
    path: /data/outbox    # gzip 파일 저장 경로 (기본값: D:/files/outbox)

  batch:
    size: 1000            # 건수 트리거 임계값 (기본값: 1000)
    time-trigger-ms: 60000   # 시간 트리거 간격 ms (기본값: 60초)
    check-interval-ms: 5000  # 스케줄러 폴링 간격 ms (기본값: 5초)
```

---

## 어노테이션

### `@OutboxDomain` — 클래스 레벨

```java
// 해당 서비스의 모든 이벤트 캡처 비활성화
@OutboxDomain(enabled = false)
@Service
public class InternalSyncService { ... }
```

### `@OutboxEvent` — 메서드 레벨

```java
@Service
public class OrderService {

    // 이 메서드는 Outbox 캡처에서 제외
    @OutboxEvent(enabled = false)
    public void internalSync(Order order) { ... }

    // 이벤트 타입 직접 지정 (기본값: SQL 타입에서 자동 추론)
    @OutboxEvent(eventType = "BULK_UPDATED")
    public void bulkUpdate(List<Order> orders) { ... }
}
```

---

## 커스텀 컨버터

기본 컨버터(`DefaultOutboxConverter`)는 엔티티를 JSON 직렬화하며 민감 필드(password, token 등)를 자동 제외합니다.  
도메인별로 payload를 커스터마이징하려면 `OutboxConverter`(spi 패키지)를 구현하고 빈 이름 규칙을 따릅니다.

**빈 이름 규칙**: `{도메인 소문자}OutboxConverter`

```java
// domain = "ORDER" → bean name = "orderOutboxConverter"
@Component("orderOutboxConverter")
public class OrderOutboxConverter implements OutboxConverter {

    @Override
    public Outbox convert(Object entity, String domain, String eventType) {
        Order order = (Order) entity;
        return Outbox.builder()
                .domain(domain)
                .eventType(eventType)
                .source("INTERNAL")
                .payload(toJson(order))
                .build();
    }
}
```

커스텀 컨버터가 없으면 `defaultOutboxConverter`로 폴백합니다.

---

## 커스텀 저장소

기본 저장소는 JDBC(`JdbcOutboxStore`)입니다. JPA, R2DBC 등 다른 영속성 기술을 사용하려면 `OutboxStore`(spi 패키지)를 구현해 빈으로 등록합니다.

```java
@Component
public class JpaOutboxStore implements OutboxStore {
    // OutboxStore 인터페이스 구현
}
```

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

SENT 상태 레코드는 매일 02:00에 7일 초과분 자동 정리됩니다.

---

## 핵심 구현 상세

### TransactionSynchronization — 트랜잭션 원자성 보장

DML 감지 즉시 DB에 쓰지 않고 Spring의 `TransactionSynchronization`을 등록합니다.

```
비즈니스 로직 실행
  └─ DML 감지 → 이벤트를 ThreadLocal(OutboxContextData)에 적재
       └─ beforeCommit() 호출 → outbox 테이블에 batchInsert
            └─ 비즈니스 트랜잭션 커밋
                 └─ 비즈니스 데이터 + outbox 동시 커밋 (원자적)
```

트랜잭션 외부에서는 DML 직후 즉시 flush합니다.

### FOR UPDATE SKIP LOCKED — 다중 인스턴스 배치 중복 방지

```sql
SELECT ... FROM outbox
WHERE status = 'PENDING'
ORDER BY seq ASC
LIMIT #{limit}
FOR UPDATE SKIP LOCKED
```

인스턴스를 여러 개 띄워도 각 인스턴스가 겹치지 않는 배치를 가져가므로 별도 분산 락 없이 수평 확장이 가능합니다.

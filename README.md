# inops-outbox-core

**MyBatis 전용** Outbox 패턴 라이브러리.  
MyBatis `Executor.update()` 인터셉터로 DML 이벤트를 자동 캡처하여 폐쇄망으로 파일 동기화합니다.

> JPA / Hibernate 환경에서는 동작하지 않습니다.

## 개요

```
내부망 서비스
  └─ MyBatis DML 실행
       └─ OutboxInterceptor 감지
            └─ outbox 테이블 저장 (트랜잭션 내)
                 └─ OutboxScheduler 배치
                      └─ sync_*.json.gz 파일 생성
                           └─ 폐쇄망 수신 서버 처리
```

- **자동 감지**: `outbox.tables`에 등록된 테이블의 INSERT/UPDATE/DELETE를 자동 캡처
- **트랜잭션 보장**: `beforeCommit` 훅으로 비즈니스 트랜잭션과 원자적으로 저장
- **이중 트리거**: 건수(`batch.size`) 또는 시간(`batch.time-trigger-ms`) 조건 중 먼저 충족 시 배치 실행
- **gzip 파일 출력**: `sync_{seqFrom}_{seqTo}_{timestamp}.json.gz` 형식으로 압축 저장

---

## 요구사항

| 항목 | 버전 |
|------|------|
| Java | 17+ |
| Spring Boot | 3.1+ |
| MyBatis Spring Boot Starter | 3.0+ (**필수**) |
| PostgreSQL | (스키마 적용 필요) |

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

Spring Boot Auto-Configuration으로 별도 `@EnableXxx` 없이 자동 등록됩니다.

---

## DB 스키마

```sql
-- src/main/resources/sql/outbox-schema.sql 참고
CREATE SEQUENCE IF NOT EXISTS outbox_seq_seq START 1;

CREATE TABLE IF NOT EXISTS outbox (
    id         BIGSERIAL   PRIMARY KEY,
    seq        BIGINT      NOT NULL DEFAULT NEXTVAL('outbox_seq_seq'),
    domain     VARCHAR(50) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    source     VARCHAR(20) NOT NULL,  -- INTERNAL | CLOSED_NET
    payload    JSONB,
    status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    sent_at    TIMESTAMP
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
  # 감시할 테이블 목록 (스키마 prefix 제외, Debezium include.list와 동일 형식)
  tables:
    - user
    - order_item
    - product_category

  file:
    path: /data/outbox          # gzip 파일 저장 경로 (기본값: D:/files/outbox)

  batch:
    size: 1000                  # 건수 트리거 임계값 (기본값: 1000)
    time-trigger-ms: 60000      # 시간 트리거 간격 ms (기본값: 60초)
    check-interval-ms: 5000     # 스케줄러 폴링 간격 ms (기본값: 5초)
```

---

## 어노테이션

### `@OutboxDomain` — 클래스 레벨

서비스 클래스에 붙여 Outbox 동작을 명시적으로 제어합니다.

```java
// 도메인명 직접 지정 (기본값: 테이블명으로 자동 추론)
@OutboxDomain("USER_MGMT")
@Service
public class UserService { ... }

// 해당 서비스의 모든 이벤트 캡처 비활성화
@OutboxDomain(enabled = false)
@Service
public class InternalSyncService { ... }
```

`auto-detect` 모드에서는 `outbox.tables`에 등록된 테이블을 사용하는 Mapper가 속한 Service를 자동 감지합니다.

### `@OutboxEvent` — 메서드 레벨

`@OutboxDomain` 서비스 내에서 메서드 단위로 세부 제어합니다.

```java
@Service
@OutboxDomain
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

기본 컨버터(`DefaultOutboxConverter`)는 파라미터 객체를 JSON 직렬화합니다.  
도메인별로 payload를 커스터마이징하려면 `OutboxConverter`를 구현하고 빈 이름 규칙을 따릅니다.

**빈 이름 규칙**: `{도메인 소문자}OutboxConverter`

```java
// domain = "ORDER" → bean name = "orderOutboxConverter"
@Component("orderOutboxConverter")
public class OrderOutboxConverter implements OutboxConverter {

    @Override
    public Outbox convert(Object result, String domain, String eventType) {
        Order order = (Order) result;
        return Outbox.builder()
                .domain(domain)
                .eventType(eventType)
                .source("INTERNAL")
                .payload(toJson(order))
                .build();
    }
}
```

등록된 커스텀 컨버터가 없으면 `defaultOutboxConverter`로 폴백합니다.

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

## 테이블명 자동 추론

Mapper 클래스명에서 테이블명을 CamelCase → snake_case 변환으로 추론합니다.

| Mapper 클래스 | 추론된 테이블명 |
|---|---|
| `UserMapper` | `user` |
| `OrderItemMapper` | `order_item` |
| `ProductCategoryMapper` | `product_category` |
| `McAuthGrpMenuFuncMpnMapper` | `mc_auth_grp_menu_func_mpn` |

추론된 테이블명이 `outbox.tables`에 포함된 경우에만 이벤트를 캡처합니다.

---

## Outbox 상태

| 상태 | 설명 |
|------|------|
| `PENDING` | 캡처 완료, 배치 대기 중 |
| `SENT` | 파일 변환 완료 (7일 후 자동 삭제) |
| `FAILED` | 파일 쓰기 실패 |

SENT 상태 레코드는 매일 02:00에 7일 초과분 자동 정리됩니다.

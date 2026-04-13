---
name: outbox-sync
description: >
  내부망 ↔ 폐쇄망 데이터 동기화 아키텍처 구현 스킬.
  파일서버 기반 단방향/양방향 데이터 동기화가 필요할 때 사용.
  Outbox 패턴, AOP 기반 이벤트 캡처, 배치 파일 생성, 중복 처리, 루프 방지를 다룸.
  Debezium 대체, CDC 불안정 문제 해결, 폐쇄망 환경 동기화 설계 시 반드시 사용할 것.
---

# Outbox 기반 내부망 ↔ 폐쇄망 동기화

## 전체 흐름

```
내부망
서비스 → (AOP) → Outbox 테이블
                      ↓ 배치 스케줄러
                  파일 생성 (seq_no + 압축)
                      ↓
                  파일서버 (유일한 접점)
                      ↓
폐쇄망            수신 큐 (inbox)
                      ↓ 중복·순서 검증
                  증분 적용 (upsert, idempotent)
                      ↓
                  대상 DB
```

양방향이면 폐쇄망에도 동일 구조 구성. 루프 방지는 `source` 필드로 처리.

---

## 1. DB 구성

**메인 DB와 같은 PostgreSQL 인스턴스 사용 권장.**

별도 DB로 분리하면 트랜잭션을 공유할 수 없어 Outbox 패턴의 핵심 장점(유실 없음)이 깨짐.
모듈 분리는 코드 분리이고 DB 분리는 인프라 분리 → 멀티 모듈이어도 같은 DB 써도 무방.

```
PostgreSQL (단일 인스턴스)
├── orders, products, ...   ← 메인 테이블
└── outbox                  ← 테이블만 추가
```

관리 분리가 필요하면 스키마로 구분:

```sql
CREATE SCHEMA outbox;
CREATE TABLE outbox.events ( ... );
```

`outbox-core` 모듈은 메인 DataSource를 그대로 주입받아 사용. 별도 DataSource 설정 불필요.

```java
// outbox-core 모듈
@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;  // 메인 DataSource 그대로 사용

    @Transactional  // 메인 트랜잭션과 공유
    public void save(Outbox outbox) { ... }
}
```

---

## 2. Outbox 테이블

```sql
CREATE TABLE outbox (
    id         BIGSERIAL PRIMARY KEY,
    seq        BIGINT NOT NULL,
    domain     VARCHAR(50) NOT NULL,       -- 'ORDER', 'PRODUCT' 등
    event_type VARCHAR(20) NOT NULL,       -- 'CREATED', 'UPDATED', 'DELETED'
    source     VARCHAR(20) NOT NULL,       -- 'INTERNAL', 'CLOSED_NET'
    payload    JSONB,
    status     VARCHAR(20) DEFAULT 'PENDING', -- PENDING / SENT / FAILED
    created_at TIMESTAMP DEFAULT NOW(),
    sent_at    TIMESTAMP
);
```

---

## 3. AOP 기반 이벤트 캡처

도메인이 많을 때 AOP로 서비스 코드 침투 최소화.

### 어노테이션

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OutboxEvent {
    String domain();
    String eventType();
    boolean enabled() default true;
}
```

### Aspect

```java
@Aspect
@Component
@RequiredArgsConstructor
public class OutboxAspect {

    private final Map<String, OutboxConverter> converters;
    private final OutboxRepository outboxRepository;

    @AfterReturning(
        pointcut = "@annotation(outboxEvent)",
        returning = "result"
    )
    public void captureOutbox(JoinPoint jp,
                               Object result,
                               OutboxEvent outboxEvent) {
        if (!outboxEvent.enabled()) return;

        // 외부에서 온 이벤트면 재발행 금지 (루프 방지)
        if (result instanceof OutboxPayload p
                && "CLOSED_NET".equals(p.getSource())) return;

        OutboxConverter converter = converters.get(
            outboxEvent.domain().toLowerCase() + "OutboxConverter"
        );
        if (converter == null) {
            log.warn("No converter for domain: {}", outboxEvent.domain());
            return;
        }

        outboxRepository.save(converter.convert(result, outboxEvent));
    }
}
```

### 서비스 사용

```java
// 도메인 추가 시 어노테이션 하나만 붙이면 끝
@OutboxEvent(domain = "ORDER", eventType = "UPDATED")
@Transactional
public void saveOrder(Order order) { ... }

// 특정 도메인 임시 중단
@OutboxEvent(domain = "LEGACY", eventType = "UPDATED", enabled = false)
@Transactional
public void saveLegacy(Legacy legacy) { ... }
```

### 도메인별 컨버터

```java
@Component
public class OrderOutboxConverter implements OutboxConverter {
    @Override
    public Outbox convert(Object result, OutboxEvent meta) {
        Order order = (Order) result;
        return Outbox.builder()
            .domain(meta.domain())
            .eventType(meta.eventType())
            .source("INTERNAL")
            .payload(sanitize(order))   // 민감 필드 제거
            .build();
    }
}
```

새 도메인 추가 = 컨버터 1개 + 어노테이션 1개.

---

## 4. 배치 스케줄러 (파일 생성 트리거)

시간 기반 단독 사용 시 트래픽 폭증에 취약. 건수 기반 단독 사용 시 저트래픽에서 파일 지연.
**둘 다 걸어서 먼저 걸리는 조건으로 파일 생성.**

```java
@Scheduled(fixedDelay = 5_000)
@Transactional
public void processOutbox() {
    long pendingCount = outboxRepository.countPending();

    boolean timeTriggered = Duration.between(lastFlush, Instant.now())
                                    .toMillis() >= 60_000;
    boolean sizeTriggered = pendingCount >= 1_000;

    if (!timeTriggered && !sizeTriggered) return;

    List<Outbox> batch = outboxRepository.findPendingWithLock(1_000);
    if (batch.isEmpty()) return;

    fileWriter.write(batch);                        // 파일 생성
    outboxRepository.markSent(batch);               // SENT 마킹
    lastFlush = Instant.now();
}
```

### 배치 픽업 쿼리

```sql
SELECT * FROM outbox
WHERE status = 'PENDING'
ORDER BY seq ASC
LIMIT 1000
FOR UPDATE SKIP LOCKED;   -- 동시 실행 방어
```

---

## 5. 파일 포맷

```json
{
  "meta": {
    "seq_from": 1001,
    "seq_to": 2000,
    "source": "INTERNAL",
    "created_at": "2026-04-10T10:00:00Z"
  },
  "events": [
    {
      "seq": 1001,
      "domain": "ORDER",
      "event_type": "UPDATED",
      "source": "INTERNAL",
      "payload": { "id": 1, "name": "상품A", "updated_at": "2026-04-10T09:59:00Z" }
    }
  ]
}
```

파일명: `sync_1001_2000_20260410T100000Z.json.gz`

---

## 6. 중복 데이터 처리 (수신측)

### 처리한 seq 기록

```sql
CREATE TABLE processed_seq (
    seq        BIGINT PRIMARY KEY,
    processed_at TIMESTAMP DEFAULT NOW()
);

-- 이미 처리한 seq면 스킵
INSERT INTO processed_seq (seq) VALUES (:seq)
ON CONFLICT DO NOTHING;
```

### Idempotent upsert

```sql
INSERT INTO orders (id, name, updated_at, ...)
VALUES (:id, :name, :updatedAt, ...)
ON CONFLICT (id) DO UPDATE
SET name       = EXCLUDED.name,
    updated_at = EXCLUDED.updated_at
WHERE orders.updated_at < EXCLUDED.updated_at;  -- 더 최신일 때만 덮어씀
```

같은 파일 두 번 처리돼도 안전.

---

## 7. 양방향 루프 방지

`source` 필드로 처리. ThreadLocal 불필요.

```
내부망 수정 → source: INTERNAL → Outbox 저장 O → 파일서버 전달
폐쇄망 수신 → source: INTERNAL 확인 → 적용만, Outbox 재발행 X
```

수신측 적용 코드:

```java
public void applyFromFile(OutboxPayload payload) {
    // source가 자기 자신이면 Outbox 재발행 안 함
    // AOP에서 source 체크로 자동 처리됨
    orderService.saveOrder(payload);
}
```

---

## 8. Outbox 상태 관리

| 상태   | 설명              | 처리                        |
|--------|-------------------|-----------------------------|
| PENDING | 발행 대기         | 배치 스케줄러 픽업           |
| SENT   | 파일서버 전달 완료 | 7일 후 자동 삭제            |
| FAILED | 전송 실패         | 자동 삭제 금지, 알람 + 수동  |

```sql
-- SENT 7일 후 삭제 (매일 새벽)
DELETE FROM outbox
WHERE status = 'SENT'
  AND sent_at < NOW() - INTERVAL '7 days';

-- FAILED 모니터링
SELECT COUNT(*), MIN(created_at)
FROM outbox
WHERE status = 'FAILED';

-- PENDING 30분 이상 체류 시 알람
SELECT COUNT(*) FROM outbox
WHERE status = 'PENDING'
  AND created_at < NOW() - INTERVAL '30 minutes';
```

---

## 9. 모듈 구조 (멀티 모듈 적용 시)

```
your-project
├── core-domain          ← 엔티티, OutboxConverter 인터페이스
├── outbox-core          ← AOP, 스케줄러, 상태관리 (core-domain만 의존)
├── outbox-converter     ← 도메인별 컨버터 구현체
├── file-transfer        ← 파일 생성, 압축, 파일서버 전송
└── app                  ← 모듈 조합, Spring Boot 실행
```

**주의:** `outbox-core`는 인터페이스만 의존. 구현체는 `outbox-converter`에.
순환참조 방지: `core-domain → outbox-core` 단방향 유지.

---

## 10. 체크리스트

- [ ] Outbox는 메인 PostgreSQL 같은 인스턴스 사용 확인 (트랜잭션 공유)
- [ ] Outbox 테이블 seq 인덱스 추가
- [ ] `FOR UPDATE SKIP LOCKED` 배치 픽업 적용
- [ ] 파일명에 seq_from / seq_to / timestamp 포함
- [ ] 수신측 `processed_seq` 테이블로 중복 방지
- [ ] upsert에 `updated_at` 비교 조건 추가
- [ ] FAILED 상태 알람 연동
- [ ] PENDING 장기 체류 알람 연동
- [ ] 양방향 시 source 필드 검증 로직 확인

package io.github.ahnyeongjun.outbox.model;

/**
 * 무한 루프 방지 마커 인터페이스.
 * 폐쇄망 수신 이벤트를 처리하는 서비스의 결과 타입에 구현.
 * source == "CLOSED_NET" 이면 OutboxAspect가 발행을 차단.
 */
public interface OutboxPayload {
    String getSource();
}

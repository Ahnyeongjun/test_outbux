package io.github.ahnyeongjun.outbox.context;

/**
 * 트랜잭션 내 Outbox 이벤트 적재 및 루프 방지, suppress 관리.
 *
 * <p>폐쇄망 수신 이벤트 재사용 시 무한 발행 방지:
 * <pre>
 * OutboxContext.runSuppressed(() -> orderService.updateOrder(payload));
 * </pre>
 */
public final class OutboxContext {

    private static final ThreadLocal<OutboxContextData> holder = new ThreadLocal<>();

    private OutboxContext() {}

    /** 컨텍스트가 없으면 생성 (인터셉터가 최초 이벤트 캡처 시 호출) */
    public static OutboxContextData getOrCreate() {
        if (holder.get() == null) holder.set(new OutboxContextData());
        return holder.get();
    }

    public static OutboxContextData get() {
        return holder.get();
    }

    public static boolean isSuppressed() {
        OutboxContextData ctx = holder.get();
        return ctx != null && ctx.isSuppressed();
    }

    public static void clear() {
        holder.remove();
    }

    /**
     * 블록 내 모든 mapper 쓰기 호출의 Outbox 캡처를 차단.
     * 폐쇄망 수신 이벤트를 DB에 반영할 때 사용.
     */
    public static <T> T runSuppressed(java.util.concurrent.Callable<T> action) throws Exception {
        OutboxContextData ctx = getOrCreate();
        ctx.suppress();
        try {
            return action.call();
        } finally {
            clear();
        }
    }

    public static void runSuppressed(Runnable action) {
        OutboxContextData ctx = getOrCreate();
        ctx.suppress();
        try {
            action.run();
        } finally {
            clear();
        }
    }
}

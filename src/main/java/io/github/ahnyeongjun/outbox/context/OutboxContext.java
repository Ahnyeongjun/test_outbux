package io.github.ahnyeongjun.outbox.context;

/**
 * ?¸ëœ??…˜ ??Outbox ?´ë²¤???„ì  ë°?ë£¨í”„ ë°©ì? suppress ê´€ë¦?
 *
 * <p>?ì‡„ë§??˜ì‹  ?´ë²¤???ìš© ???¬ë°œ??ë°©ì?:
 * <pre>
 * OutboxContext.runSuppressed(() -> orderService.updateOrder(payload));
 * </pre>
 */
public final class OutboxContext {

    private static final ThreadLocal<OutboxContextData> holder = new ThreadLocal<>();

    private OutboxContext() {}

    /** ì»¨í…?¤íŠ¸ê°€ ?†ìœ¼ë©??ì„± (?¸í„°?‰í„°ê°€ ìµœì´ˆ ?´ë²¤??ìº¡ì²˜ ???¸ì¶œ) */
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
     * ë¸”ë¡ ??ëª¨ë“  mapper ?°ê¸° ?¸ì¶œ??Outbox ìº¡ì²˜ë¥?ì°¨ë‹¨.
     * ?ì‡„ë§??˜ì‹  ?´ë²¤?¸ë? DB???ìš©?????¬ìš©.
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

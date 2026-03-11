package com.apiagent.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 에이전트 루프 턴 카운팅.
 */
public class TurnTracker {

    private final AtomicInteger turnCount = new AtomicInteger(0);
    private final int maxTurns;

    public TurnTracker(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int increment() {
        return turnCount.incrementAndGet();
    }

    public int current() {
        return turnCount.get();
    }

    public boolean hasRemaining() {
        return turnCount.get() < maxTurns;
    }

    public String getContext() {
        return "turn %d/%d".formatted(turnCount.get(), maxTurns);
    }
}

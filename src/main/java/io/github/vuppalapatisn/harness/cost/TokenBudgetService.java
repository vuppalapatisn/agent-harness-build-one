package io.github.vuppalapatisn.harness.cost;

import io.github.vuppalapatisn.harness.config.HarnessProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-user daily token budget (article: {@code limit: { requests: 5000000, unit: Day }}).
 * Requests over budget are rejected with HTTP 429. In-memory; back it with Redis for multi-replica.
 */
@Component
public class TokenBudgetService {

    private record DailyUsage(LocalDate day, long tokens) {}

    private final Map<String, DailyUsage> usage = new ConcurrentHashMap<>();
    private final long dailyLimit;
    private final Clock clock;

    public TokenBudgetService(HarnessProperties properties, Clock clock) {
        this.dailyLimit = properties.getLimits().getDailyTokenBudgetPerUser();
        this.clock = clock;
    }

    public void ensureAvailable(String userId) {
        if (remaining(userId) <= 0) {
            throw new BudgetExceededException("Daily token budget of " + dailyLimit + " exhausted for user " + userId);
        }
    }

    public void record(String userId, long tokens) {
        LocalDate today = LocalDate.now(clock);
        usage.merge(userId, new DailyUsage(today, tokens), (old, add) ->
                old.day().equals(today) ? new DailyUsage(today, old.tokens() + add.tokens()) : add);
    }

    public long used(String userId) {
        DailyUsage u = usage.get(userId);
        return u == null || !u.day().equals(LocalDate.now(clock)) ? 0 : u.tokens();
    }

    public long remaining(String userId) {
        return Math.max(0, dailyLimit - used(userId));
    }

    public long dailyLimit() {
        return dailyLimit;
    }
}

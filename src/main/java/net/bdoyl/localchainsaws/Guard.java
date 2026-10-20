package net.bdoyl.localchainsaws;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one of our server-side actions so that an exception (ours, or another mod's code reacting to what we did: a kill,
 * a broken block) is logged instead of crashing the server. The full stack trace is logged the first time for each
 * action, after that one line per 100 failures.
 */
final class Guard {
    private static final Logger LOG = LoggerFactory.getLogger("localchainsaws");
    private static final Map<String, Integer> FAILURES = new HashMap<>();

    private Guard() {}

    /** True if {@code action} finished normally. */
    static boolean run(String what, Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException | LinkageError e) {
            int n = FAILURES.merge(what, 1, Integer::sum);
            if (n == 1) LOG.error("[localchainsaws] {} failed (stopped it instead of crashing the server)", what, e);
            else if (n % 100 == 0) LOG.error("[localchainsaws] {} failed {} times so far, latest: {}", what, n, e.toString());
            return false;
        }
    }

    static int failures(String what) {
        return FAILURES.getOrDefault(what, 0);
    }
}

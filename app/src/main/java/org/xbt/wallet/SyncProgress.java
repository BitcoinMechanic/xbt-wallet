package org.xbt.wallet;

import java.util.regex.*;

/** Startup-only progress from LND's own log, before GetInfo becomes available. */
final class SyncProgress {
    long target, headers, filters;
    private static final Pattern TARGET = Pattern.compile("Syncing to block height (\\d+) from peer");
    private static final Pattern HEADERS = Pattern.compile("Processed .*blocks.*\\(height (\\d+),");
    private static final Pattern FILTERS = Pattern.compile("Verified .*filter headers.*\\(height (\\d+),");
    private static final Pattern FILTER_WAIT = Pattern.compile("cfheaders sync from height (\\d+)");
    private static final Pattern CHECKPOINT = Pattern.compile("Verified downloaded block header against checkpoint at height (\\d+)/");
    void accept(String line) {
        target = matched(TARGET, line, target);
        headers = matched(HEADERS, line, headers);
        headers = matched(CHECKPOINT, line, headers);
        filters = matched(FILTERS, line, filters);
        filters = matched(FILTER_WAIT, line, filters);
    }
    private long matched(Pattern pattern, String line, long previous) {
        Matcher match = pattern.matcher(line);
        return match.find() ? Math.max(previous, Long.parseLong(match.group(1))) : previous;
    }
    int percent(long value) { return target == 0 ? 0 : (int)Math.min(100, value * 100 / target); }
}

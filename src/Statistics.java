import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared statistics written by many threads, read by the ATC at the end.
 *  - passengersBoarded : AtomicInteger  -> a single atomic read-modify-write, no lock needed.
 *  - the wait-time figures change TOGETHER (max/min/total/count), so they need one lock.
 *    A FAIR ReentrantLock is used (FIFO hand-over, as in the notes' single-lane-bridge pattern),
 *    always unlocked in finally.
 */
public class Statistics {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final AtomicInteger passengersBoarded = new AtomicInteger(0);
    private final List<String> perPlaneLines = new ArrayList<>();

    private int planesServed = 0;
    private long totalWaitMs = 0;
    private long maxWaitMs = Long.MIN_VALUE;
    private long minWaitMs = Long.MAX_VALUE;

    public void addBoarded(int count) {
        passengersBoarded.addAndGet(count);          // atomic statement
    }

    public void recordPlane(String plane, long landingWaitMs, long takeoffWaitMs) {
        long total = landingWaitMs + takeoffWaitMs;
        lock.lock();
        try {
            planesServed++;
            totalWaitMs += total;
            maxWaitMs = Math.max(maxWaitMs, total);
            minWaitMs = Math.min(minWaitMs, total);
            perPlaneLines.add(String.format("%s: landing wait %.2fs + take-off wait %.2fs = %.2fs",
                    plane, landingWaitMs / 1000.0, takeoffWaitMs / 1000.0, total / 1000.0));
        } finally {
            lock.unlock();
        }
    }

    public int getPassengersBoarded() { return passengersBoarded.get(); }

    public int getPlanesServed() {
        lock.lock();
        try { return planesServed; } finally { lock.unlock(); }
    }

    public long getMaxWaitMs() {
        lock.lock();
        try { return planesServed == 0 ? 0 : maxWaitMs; } finally { lock.unlock(); }
    }

    public long getMinWaitMs() {
        lock.lock();
        try { return planesServed == 0 ? 0 : minWaitMs; } finally { lock.unlock(); }
    }

    public double getAverageWaitMs() {
        lock.lock();
        try { return planesServed == 0 ? 0 : (double) totalWaitMs / planesServed; } finally { lock.unlock(); }
    }

    public List<String> getPerPlaneLines() {
        lock.lock();
        try { return new ArrayList<>(perPlaneLines); } finally { lock.unlock(); }
    }
}
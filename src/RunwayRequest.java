import java.util.concurrent.Semaphore;

/**
 * One request to use the runway (landing OR take-off). Created by a plane thread,
 * decided by the ATC thread.
 *
 * The 'granted' semaphore starts with 0 permits and is used purely as a SIGNAL
 * (notes: "any thread can release a semaphore - ideal for signalling protocols"):
 *   plane  -> granted.acquire()   blocks until the ATC says yes
 *   ATC    -> granted.release()   wakes exactly that plane
 *
 * Mutable fields (sequence, grantNanos, assignedGate, flags) are only written by the ATC
 * while it holds its monitor; the plane reads them after granted.acquire(), and the
 * release/acquire pair gives the required happens-before ordering.
 */
public class RunwayRequest {
    public enum Type { LANDING, TAKEOFF }

    final String planeName;
    final Type type;
    final boolean emergency;                    // fuel-shortage priority
    final long requestNanos = System.nanoTime();
    final Semaphore granted = new Semaphore(0, true);

    long sequence;                              // global FIFO ticket, set by ATC under its monitor
    long grantNanos;
    int assignedGate = -1;                      // 1..3, only for landings
    boolean denialAnnounced = false;            // ATC printed "denied / hold" once already
    boolean conflictAnnounced = false;          // ATC printed the landing-vs-take-off decision once

    public RunwayRequest(String planeName, Type type, boolean emergency) {
        this.planeName = planeName;
        this.type = type;
        this.emergency = emergency;
    }

    /** Time between asking and being granted. Call only AFTER granted.acquire(). */
    public long waitedMillis() {
        return (grantNanos - requestNanos) / 1_000_000;
    }
}
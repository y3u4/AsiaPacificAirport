import java.util.ArrayList;
import java.util.List;

/**
 * The ATC runs as its OWN thread ("ATC") and is the ONLY thread that decides who may use the
 * runway, which gate is assigned, and how many aircraft are on the ground.
 *
 * ---------------- Safety design (explained in the report) ----------------
 * 1. MONITOR: all shared state (pending requests, gates, runwayBusy, groundCount ...) is guarded by
 *    ONE private lock object using synchronized + wait()/notifyAll(). (We do not synchronize on
 *    'this' because a Thread object's own monitor is also used by Thread.join().)
 *    -> no race condition: every read-modify-write of the state happens inside the monitor.
 * 2. NO DOUBLE LOCKING: the only blocking waits are lock.wait() (which releases the monitor) and the
 *    per-request Semaphore that the PLANE waits on OUTSIDE the monitor. We never block on a semaphore
 *    while holding the monitor, so there is no lock-ordering hazard.
 * 3. Lock order: ATC monitor -> Logger lock (leaf). Nothing ever acquires them in the other order.
 *
 * ---------------- Scheduling policy (assumptions) ----------------
 * - Every runway request (landing or take-off) gets a global FIFO ticket when it is submitted.
 * - EMERGENCY (fuel shortage) landing outranks every normal request: it cuts the queue.
 * - Among requests that can be served RIGHT NOW the highest priority wins (emergency, then lowest ticket).
 * - A landing can be served only if the runway is free AND fewer than 3 aircraft are on the ground.
 *   A take-off needs only the runway. So when the airport is full, a take-off that arrived LATER than a
 *   waiting landing is served first - not unfairness but DEADLOCK AVOIDANCE: the landing is waiting for a
 *   slot that only a departure can free. (If the head-of-queue landing blocked the take-off we would
 *   have circular wait: landing waits for departure, departure waits behind landing.)
 */
public class AirTrafficControl extends Thread {

    private final Object lock = new Object();                       // the monitor
    private final List<RunwayRequest> pending = new ArrayList<>();  // guarded by lock
    private final String[] gateOwner = new String[AirportConfig.GATE_COUNT]; // null = empty; guarded by lock
    private final Statistics stats;

    private long nextSequence = 0;     // guarded by lock
    private boolean runwayBusy = false;
    private int groundCount = 0;       // aircraft on the grounds (runway included)
    private int peakGroundCount = 0;
    private int departed = 0;
    private int runwayViolations = 0;  // should stay 0 - used by the sanity check

    public AirTrafficControl(Statistics stats) {
        super("ATC");
        this.stats = stats;
    }

    // =====================================================================
    //  Methods called by OTHER threads (planes / simulator)
    // =====================================================================

    /** A plane asks to land or take off. Returns immediately; the plane then waits on request.granted. */
    public RunwayRequest requestRunway(String planeName, RunwayRequest.Type type, boolean emergency) {
        RunwayRequest request = new RunwayRequest(planeName, type, emergency);
        synchronized (lock) {
            request.sequence = nextSequence++;   // FIFO ticket - atomic because we are inside the monitor
            pending.add(request);
            lock.notifyAll();                    // wake the ATC (and anyone waiting for congestion)
        }
        return request;
    }

    /** Plane finished LANDING and has cleared the runway. */
    public void runwayReleased() {
        synchronized (lock) {
            runwayBusy = false;
            lock.notifyAll();
        }
    }

    /** Plane has undocked: its gate is free again. */
    public void gateReleased(int gateNumber) {
        synchronized (lock) {
            gateOwner[gateNumber - 1] = null;
            lock.notifyAll();
        }
    }

    /** Plane finished TAKING OFF: runway is free and the aircraft has left the grounds. */
    public void planeDeparted() {
        synchronized (lock) {
            runwayBusy = false;
            groundCount--;
            departed++;
            lock.notifyAll();
        }
    }

    /**
     * Used by the Simulator thread to build the congested scenario: blocks until the airport is full
     * AND at least 2 planes are already circling. Uses wait(timeout) so it can never hang forever.
     */
    public boolean awaitCongestion(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (!isCongested()) {                       // always wait in a loop (guards spurious wake-ups)
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    // =====================================================================
    //  The ATC thread itself
    // =====================================================================

    @Override
    public void run() {
        Logger.log("Control tower open: 1 runway, " + AirportConfig.GATE_COUNT + " gates, max "
                + AirportConfig.MAX_AIRCRAFT_ON_GROUND + " aircraft on the ground.");
        synchronized (lock) {
            while (departed < AirportConfig.TOTAL_PLANES) {
                announceDenials();                       // tell blocked planes to hold
                RunwayRequest next = selectNext();       // best request that can be served now
                if (next == null) {
                    try {
                        lock.wait();                     // releases the monitor while sleeping
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Logger.log("Interrupted - control tower closing early.");
                        return;
                    }
                } else {
                    grant(next);
                }
            }
        }
        printFinalReport();                              // all planes gone -> sanity checks + statistics
    }

    // ---- everything below is called ONLY while holding 'lock' (except printFinalReport) ----

    /** Priority order: emergency first, then FIFO ticket. Negative = a is served before b. */
    private static int priorityOrder(RunwayRequest a, RunwayRequest b) {
        if (a.emergency != b.emergency) {
            return a.emergency ? -1 : 1;
        }
        return Long.compare(a.sequence, b.sequence);
    }

    /** Why can this request NOT be served right now? null means it can be served. */
    private String blockedReason(RunwayRequest r) {
        if (r.type == RunwayRequest.Type.LANDING && groundCount >= AirportConfig.MAX_AIRCRAFT_ON_GROUND) {
            return "Airport Full";
        }
        if (runwayBusy) {
            return "Runway occupied";
        }
        return null;
    }

    private RunwayRequest selectNext() {
        RunwayRequest best = null;
        for (RunwayRequest r : pending) {
            if (blockedReason(r) != null) {
                continue;                                // cannot be served now
            }
            if (best == null || priorityOrder(r, best) < 0) {
                best = r;
            }
        }
        return best;
    }

    private int queuePosition(RunwayRequest r) {
        int position = 1;
        for (RunwayRequest other : pending) {
            if (other != r && other.type == r.type && priorityOrder(other, r) < 0) {
                position++;
            }
        }
        return position;
    }

    private void announceDenials() {
        boolean changed = false;
        for (RunwayRequest r : pending) {
            String reason = blockedReason(r);
            if (reason == null || r.denialAnnounced) {
                continue;
            }
            r.denialAnnounced = true;
            changed = true;
            String who = r.planeName;
            if (r.type == RunwayRequest.Type.LANDING) {
                if (r.emergency) {
                    Logger.log("Landing Permission Denied for " + who + ", " + reason + ". EMERGENCY (fuel shortage) acknowledged: "
                            + who + " CUTS THE QUEUE and is first in line for the next free slot.");
                } else {
                    Logger.log("Landing Permission Denied for " + who + ", " + reason + ". "
                            + who + " joins the holding queue at position " + queuePosition(r) + " (FIFO).");
                }
            } else {
                Logger.log("Taking-off for " + who + " must wait: " + reason + ". " + who + " holds short of the runway.");
            }
        }
        if (changed) {
            lock.notifyAll();                            // e.g. wake awaitCongestion()
        }
    }

    /** Explains the "one plane lands, one plane departs" decision (once per losing request). */
    private void announceRunwayConflicts(RunwayRequest chosen) {
        for (RunwayRequest other : pending) {
            if (other == chosen || other.type == chosen.type || other.conflictAnnounced) {
                continue;
            }
            other.conflictAnnounced = true;
            String reason;
            if (other.type == RunwayRequest.Type.LANDING && groundCount >= AirportConfig.MAX_AIRCRAFT_ON_GROUND) {
                reason = "airport is full so the landing cannot be accepted yet; this departure frees a slot (prevents deadlock)";
            } else if (chosen.emergency) {
                reason = "emergency priority (fuel shortage)";
            } else {
                reason = "FIFO - " + chosen.planeName + " requested the runway first";
            }
            Logger.log("RUNWAY CONFLICT: " + describe(chosen) + " and " + describe(other)
                    + " both want the runway. " + chosen.planeName + " goes first: " + reason + ".");
        }
    }

    private static String describe(RunwayRequest r) {
        return r.planeName + " (" + (r.type == RunwayRequest.Type.LANDING ? "landing" : "take-off") + ")";
    }

    private void grant(RunwayRequest r) {
        announceRunwayConflicts(r);

        if (runwayBusy) {
            runwayViolations++;                          // can never happen; recorded for the sanity check
        }
        runwayBusy = true;
        pending.remove(r);
        r.grantNanos = System.nanoTime();

        if (r.type == RunwayRequest.Type.LANDING) {
            groundCount++;
            peakGroundCount = Math.max(peakGroundCount, groundCount);
            int gate = firstFreeGate();
            gateOwner[gate] = r.planeName;               // reserve the gate NOW so nobody else gets it
            r.assignedGate = gate + 1;

            int stillWaiting = 0;
            for (RunwayRequest o : pending) {
                if (o.type == RunwayRequest.Type.LANDING) {
                    stillWaiting++;
                }
            }
            if (r.emergency) {
                Logger.log("Landing permission granted for " + r.planeName + " - EMERGENCY, ahead of "
                        + stillWaiting + " waiting plane(s).");
            } else {
                Logger.log("Landing permission granted for " + r.planeName + ".");
            }
            Logger.log("Gate-" + r.assignedGate + " assigned for " + r.planeName + ".");
        } else {
            Logger.log("Taking-off is granted for " + r.planeName + ". Runway is free.");
        }
        r.granted.release();                             // signal AFTER logging so ATC's line prints first
        lock.notifyAll();
    }

    private int firstFreeGate() {
        for (int i = 0; i < gateOwner.length; i++) {
            if (gateOwner[i] == null) {
                return i;
            }
        }
        // Impossible: groundCount < 3 guarantees a free gate. Fail loudly instead of corrupting state.
        throw new IllegalStateException("No free gate although ground count allowed a landing");
    }

    private boolean isCongested() {
        int circling = 0;
        for (RunwayRequest r : pending) {
            if (r.type == RunwayRequest.Type.LANDING && r.denialAnnounced) {
                circling++;
            }
        }
        return groundCount >= AirportConfig.MAX_AIRCRAFT_ON_GROUND
                && circling >= AirportConfig.PLANES_WAITING_FOR_CONGESTION;
    }

    // =====================================================================
    //  End-of-run sanity checks + statistics (printed by the ATC thread)
    // =====================================================================
    private void printFinalReport() {
        Logger.log("================ END OF RUN: SANITY CHECKS ================");
        boolean gatesEmpty = true;
        boolean runwayFree;
        int ground;
        int peak;
        int violations;
        synchronized (lock) {
            for (int i = 0; i < gateOwner.length; i++) {
                boolean empty = (gateOwner[i] == null);
                gatesEmpty &= empty;
                Logger.log("Gate-" + (i + 1) + ": " + (empty ? "EMPTY" : "STILL OCCUPIED by " + gateOwner[i]));
            }
            runwayFree = !runwayBusy;
            ground = groundCount;
            peak = peakGroundCount;
            violations = runwayViolations;
        }
        Logger.log("Check - all gates empty: " + (gatesEmpty ? "PASS" : "FAIL"));
        Logger.log("Check - runway free and no aircraft on ground: " + ((runwayFree && ground == 0) ? "PASS" : "FAIL"));
        Logger.log("Check - never more than " + AirportConfig.MAX_AIRCRAFT_ON_GROUND + " aircraft on ground (peak was "
                + peak + "): " + (peak <= AirportConfig.MAX_AIRCRAFT_ON_GROUND ? "PASS" : "FAIL"));
        Logger.log("Check - runway never double-booked (violations = " + violations + "): " + (violations == 0 ? "PASS" : "FAIL"));
        Logger.log("Check - all planes served (" + stats.getPlanesServed() + "/" + AirportConfig.TOTAL_PLANES + "): "
                + (stats.getPlanesServed() == AirportConfig.TOTAL_PLANES ? "PASS" : "FAIL"));

        Logger.log("================ END OF RUN: STATISTICS ================");
        for (String line : stats.getPerPlaneLines()) {
            Logger.log("  " + line);
        }
        Logger.log(String.format("Waiting time (landing hold + take-off hold): max %.2fs | average %.2fs | min %.2fs",
                stats.getMaxWaitMs() / 1000.0, stats.getAverageWaitMs() / 1000.0, stats.getMinWaitMs() / 1000.0));
        Logger.log("Planes served: " + stats.getPlanesServed() + " | Passengers boarded: " + stats.getPassengersBoarded());
    }
}
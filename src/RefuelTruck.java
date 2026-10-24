import java.util.ArrayList;
import java.util.List;

/**
 * THE one refuelling truck: a shared, single resource modelled as its own thread ("Refuel-Truck").
 * Planes only queue a request; the truck serves ONE request at a time, so mutual exclusion is
 * guaranteed by design (only one thread ever refuels).
 *
 * Producer-consumer with a monitor: planes = producers (requestRefuel), truck = consumer.
 * The truck sleeps in wait() while the queue is empty (0% CPU, like queue.take() in the notes).
 *
 * Priority: emergency plane first, then FIFO.
 * The slow work (the refuel itself) is done OUTSIDE the monitor so planes can keep queueing
 * (same idea as the notes' "approach phase is unlocked" bridge pattern).
 */
public class RefuelTruck extends Thread {
    private final Object lock = new Object();
    private final List<RefuelRequest> queue = new ArrayList<>();   // guarded by lock
    private long nextSequence = 0;                                 // guarded by lock
    private boolean shuttingDown = false;                          // guarded by lock

    public RefuelTruck() {
        super("Refuel-Truck");
    }

    /** Called by a plane thread. */
    public void requestRefuel(RefuelRequest request) {
        synchronized (lock) {
            request.sequence = nextSequence++;
            queue.add(request);
            lock.notifyAll();
        }
    }

    /** Called by the simulator when every plane has gone home. */
    public void shutdown() {
        synchronized (lock) {
            shuttingDown = true;
            lock.notifyAll();
        }
    }

    @Override
    public void run() {
        Logger.log("Standing by - the only refuelling truck at the airport.");
        try {
            while (true) {
                RefuelRequest job;
                synchronized (lock) {
                    while (queue.isEmpty() && !shuttingDown) {
                        lock.wait();
                    }
                    if (queue.isEmpty()) {
                        break;                              // shutting down and nothing left to do
                    }
                    job = pickNext();
                    queue.remove(job);
                    boolean jumped = false;
                    for (RefuelRequest other : queue) {
                        if (other.sequence < job.sequence) {
                            jumped = true;
                        }
                    }
                    Logger.log("Next in line: " + job.planeName
                            + (job.emergency && jumped ? " (EMERGENCY - cut the refuelling queue)" : "")
                            + ". Plane(s) still waiting: " + queue.size() + ".");
                }
                // ---- refuel outside the monitor ----
                Logger.log("Refuelling " + job.planeName + "...");
                Thread.sleep(AirportConfig.REFUEL_MS);
                Logger.log("Finished refuelling " + job.planeName + ".");
                job.done.countDown();                       // tell the plane's turnaround latch
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.log("Interrupted - truck going back to the depot.");
        }
        Logger.log("Back at the depot.");
    }

    // caller holds lock
    private RefuelRequest pickNext() {
        RefuelRequest best = null;
        for (RefuelRequest r : queue) {
            if (best == null || before(r, best)) {
                best = r;
            }
        }
        return best;
    }

    private static boolean before(RefuelRequest a, RefuelRequest b) {
        if (a.emergency != b.emergency) {
            return a.emergency;
        }
        return a.sequence < b.sequence;
    }
}
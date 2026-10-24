import java.util.concurrent.CountDownLatch;

/**
 * The passengers of ONE plane, running as their own thread ("Plane-N's Passengers").
 * Disembarking happens at the same time as cleaning and refuelling (separate threads).
 * Boarding may only start when all three services are finished -> CountDownLatch.
 */
public class PassengerGroup extends Thread {
    private final String planeName;
    private final int disembarking;
    private final int boarding;
    private final CountDownLatch servicesDone;   // 3 = disembark + cleaning + refuel
    private final CountDownLatch boardingDone;   // 1 = plane waits for this
    private final Statistics stats;

    public PassengerGroup(String planeName, int disembarking, int boarding,
                          CountDownLatch servicesDone, CountDownLatch boardingDone, Statistics stats) {
        super(planeName + "'s Passengers");
        this.planeName = planeName;
        this.disembarking = disembarking;
        this.boarding = boarding;
        this.servicesDone = servicesDone;
        this.boardingDone = boardingDone;
        this.stats = stats;
    }

    @Override
    public void run() {
        try {
            Logger.log(disembarking + " passengers disembarking from " + planeName + ".");
            Thread.sleep(AirportConfig.DISEMBARK_MS);
            Logger.log("All " + disembarking + " passengers have left " + planeName + ".");
            servicesDone.countDown();                // my part is done
            servicesDone.await();                    // wait for cleaning crew + refuel truck

            Logger.log(boarding + " passengers embarking onto " + planeName + ".");
            Thread.sleep(AirportConfig.BOARD_MS);
            Logger.log("Boarding complete: " + boarding + " passengers are on " + planeName + ".");
            stats.addBoarded(boarding);              // atomic counter
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            boardingDone.countDown();                // never leave the plane waiting forever
        }
    }
}
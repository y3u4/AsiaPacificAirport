import java.util.concurrent.CountDownLatch;

/** Cleans the cabin and restocks food/supplies of ONE plane, concurrently with passengers and refuelling. */
public class CleaningCrew extends Thread {
    private final String planeName;
    private final CountDownLatch servicesDone;

    public CleaningCrew(String planeName, CountDownLatch servicesDone) {
        super(planeName + "'s Cleaning Crew");
        this.planeName = planeName;
        this.servicesDone = servicesDone;
    }

    @Override
    public void run() {
        try {
            Logger.log("Cleaning the cabin and restocking food/supplies on " + planeName + ".");
            Thread.sleep(AirportConfig.CLEAN_MS);
            Logger.log("Cleaning and restocking of " + planeName + " finished.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            servicesDone.countDown();
        }
    }
}
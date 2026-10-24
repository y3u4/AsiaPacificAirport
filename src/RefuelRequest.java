import java.util.concurrent.CountDownLatch;

/** A plane's request for the single refuelling truck. */
public class RefuelRequest {
    final String planeName;
    final boolean emergency;
    final CountDownLatch done;   // the truck counts this down when the plane is refuelled
    long sequence;               // FIFO ticket, set by the truck under its monitor

    public RefuelRequest(String planeName, boolean emergency, CountDownLatch done) {
        this.planeName = planeName;
        this.emergency = emergency;
        this.done = done;
    }
}
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simulator entry point. It only CREATES the threads and waits for them (its own name is "Simulator",
 * so it never speaks for the ATC or a plane).
 *
 * Arrival rule: gap before each new plane = rand.nextInt(2000) ms (0..1999 ms).
 * Plane 6 is the fuel-emergency plane. To GUARANTEE the congested scenario, it is released only once
 * the airport is full and 2 planes are already circling (with a timeout as a safety net).
 */
public class Main {
    public static void main(String[] args) throws InterruptedException {
        Thread.currentThread().setName("Simulator");

        Statistics stats = new Statistics();
        AirTrafficControl atc = new AirTrafficControl(stats);
        RefuelTruck truck = new RefuelTruck();
        Random random = new Random();
        long start = System.currentTimeMillis();

        atc.start();
        truck.start();

        List<Plane> planes = new ArrayList<>();
        for (int id = 1; id <= AirportConfig.TOTAL_PLANES; id++) {
            boolean emergency = (id == AirportConfig.TOTAL_PLANES);
            if (emergency) {
                Logger.log("Holding back the last aircraft until the airport is congested "
                        + "(full + " + AirportConfig.PLANES_WAITING_FOR_CONGESTION + " planes circling).");
                boolean congested = atc.awaitCongestion(AirportConfig.CONGESTION_TIMEOUT_MS);
                Logger.log(congested
                        ? "Congested scenario is set. A fuel-emergency aircraft now arrives."
                        : "Timeout: congestion was not reached; the last aircraft arrives anyway.");
            } else if (id > 1) {
                int gap = random.nextInt(AirportConfig.MAX_ARRIVAL_GAP_MS);   // 0..1999 ms
                Logger.log("Next aircraft arrives in " + gap + " ms.");
                Thread.sleep(gap);
            }
            Plane plane = new Plane(id, emergency, atc, truck, stats);
            planes.add(plane);
            plane.start();
        }

        for (Plane plane : planes) {
            plane.join();
        }
        atc.join();            // ATC prints the sanity checks and statistics, then ends
        truck.shutdown();
        truck.join();
        Logger.log(String.format("Simulation finished in %.1f seconds.", (System.currentTimeMillis() - start) / 1000.0));
    }
}
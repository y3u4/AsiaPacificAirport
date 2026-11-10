import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Simulator entry point. It only CREATES the threads and waits for them (its own name is "Simulator",
 * so it never speaks for the ATC or a plane).
 *
 * Randomisation (new every run):
 *  - The plane numbers 1..6 are SHUFFLED into a random arrival order.
 *  - The emergency plane is whichever plane arrives 5th or 6th (chosen at random).
 *  - Gap before each new arrival = rand.nextInt(2000) ms (0..1999 ms).
 * To GUARANTEE the congested scenario, the emergency plane is released only once the airport is full
 * and every earlier extra arrival is circling (1 if it is the 5th arrival, 2 if it is the 6th),
 * with a timeout as a safety net.
 */
public class Main {
    public static void main(String[] args) throws InterruptedException {
        Thread.currentThread().setName("Simulator");

        Statistics stats = new Statistics();
        AirTrafficControl atc = new AirTrafficControl(stats);
        RefuelTruck truck = new RefuelTruck();
        Random random = new Random();
        long start = System.currentTimeMillis();

        // ---- random arrival order + random emergency position (5th or 6th arrival) ----
        List<Integer> arrivalOrder = new ArrayList<>();
        for (int id = 1; id <= AirportConfig.TOTAL_PLANES; id++) {
            arrivalOrder.add(id);
        }
        Collections.shuffle(arrivalOrder, random);
        int span = AirportConfig.LAST_EMERGENCY_POSITION - AirportConfig.FIRST_EMERGENCY_POSITION + 1;
        int emergencyPosition = AirportConfig.FIRST_EMERGENCY_POSITION + random.nextInt(span);  // 1-based
        Logger.log("Arrival order this run: " + planeList(arrivalOrder)
                + " | fuel emergency will be arrival #" + emergencyPosition
                + " (Plane-" + arrivalOrder.get(emergencyPosition - 1) + ").");

        atc.start();
        truck.start();

        List<Plane> planes = new ArrayList<>();
        for (int position = 1; position <= AirportConfig.TOTAL_PLANES; position++) {
            int id = arrivalOrder.get(position - 1);
            boolean emergency = (position == emergencyPosition);
            if (emergency) {
                // (position - 1) planes arrived before it; 3 of them fill the airport, the rest must be circling
                int mustBeCircling = (position - 1) - AirportConfig.MAX_AIRCRAFT_ON_GROUND;   // 1 or 2
                Logger.log("Holding back the next aircraft until the airport is congested (full + "
                        + mustBeCircling + " plane(s) circling).");
                boolean congested = atc.awaitCongestion(AirportConfig.CONGESTION_TIMEOUT_MS, mustBeCircling);
                Logger.log(congested
                        ? "Congested scenario is set. A fuel-emergency aircraft now arrives."
                        : "Timeout: congestion was not reached; the emergency aircraft arrives anyway.");
            } else if (position > 1) {
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

    private static String planeList(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append("Plane-").append(ids.get(i));
        }
        return sb.toString();
    }
}
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * One aircraft = one thread ("Plane-N"). It prints ONLY its own events.
 * Life cycle: request landing -> (wait for ATC) -> land -> coast -> dock -> turnaround
 *             -> undock -> coast -> request take-off -> (wait for ATC) -> take off.
 *
 * The plane never touches ATC state directly; it only calls the ATC's synchronized methods
 * and then blocks on its own request's semaphore (outside any monitor => no deadlock risk).
 */
public class Plane extends Thread {
    private final boolean fuelEmergency;
    private final AirTrafficControl atc;
    private final RefuelTruck truck;
    private final Statistics stats;
    private final Random random = new Random();
    private final int arrivingPassengers;
    private final int departingPassengers;

    public Plane(int id, boolean fuelEmergency, AirTrafficControl atc, RefuelTruck truck, Statistics stats) {
        super("Plane-" + id);
        this.fuelEmergency = fuelEmergency;
        this.atc = atc;
        this.truck = truck;
        this.stats = stats;
        this.arrivingPassengers = 1 + random.nextInt(AirportConfig.MAX_PASSENGERS);   // 1..50
        this.departingPassengers = 1 + random.nextInt(AirportConfig.MAX_PASSENGERS);  // 1..50
    }

    @Override
    public void run() {
        try {
            // ---------- 1. ask for permission to land ----------
            if (fuelEmergency) {
                Logger.log("Requesting Landing. MAYDAY - fuel shortage, requesting EMERGENCY landing!");
            } else {
                Logger.log("Requesting Landing.");
            }
            RunwayRequest landing = atc.requestRunway(getName(), RunwayRequest.Type.LANDING, fuelEmergency);
            landing.granted.acquire();                     // blocks (circling) until ATC grants
            int gate = landing.assignedGate;
            long landingWait = landing.waitedMillis();

            // ---------- 2. land, coast, dock ----------
            Logger.log("Landing.");
            Thread.sleep(AirportConfig.LAND_MS);
            Logger.log("Landed.");
            atc.runwayReleased();                          // runway is now free for the next request
            Logger.log("Coasting to Gate-" + gate + ".");
            Thread.sleep(AirportConfig.COAST_MS);
            Logger.log("Docked at Gate-" + gate + ".");
            Thread.sleep(AirportConfig.DOCK_MS);

            // ---------- 3. turnaround: three activities run CONCURRENTLY ----------
            CountDownLatch servicesDone = new CountDownLatch(3);   // disembark + cleaning + refuel
            CountDownLatch boardingDone = new CountDownLatch(1);
            PassengerGroup passengers = new PassengerGroup(getName(), arrivingPassengers, departingPassengers,
                    servicesDone, boardingDone, stats);
            CleaningCrew crew = new CleaningCrew(getName(), servicesDone);
            passengers.start();
            crew.start();
            Logger.log("Requesting the refuelling truck" + (fuelEmergency ? " (EMERGENCY priority)." : "."));
            truck.requestRefuel(new RefuelRequest(getName(), fuelEmergency, servicesDone));

            boardingDone.await();                          // all services done AND new passengers on board
            passengers.join();
            crew.join();

            // ---------- 4. undock, coast, take off ----------
            Logger.log("Turnaround complete. Undocking from Gate-" + gate + ".");
            Thread.sleep(AirportConfig.UNDOCK_MS);
            atc.gateReleased(gate);
            Logger.log("Undocked. Coasting to the runway.");
            Thread.sleep(AirportConfig.COAST_MS);

            Logger.log("Requesting Taking off.");
            RunwayRequest takeoff = atc.requestRunway(getName(), RunwayRequest.Type.TAKEOFF, false);
            takeoff.granted.acquire();
            long takeoffWait = takeoff.waitedMillis();
            Logger.log("Taking off.");
            Thread.sleep(AirportConfig.TAKEOFF_MS);
            Logger.log("Airborne - left the airport.");

            stats.recordPlane(getName(), landingWait, takeoffWait);   // BEFORE departing, so ATC's report is complete
            atc.planeDeparted();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Logger.log("Interrupted - aborting.");
        }
    }
}
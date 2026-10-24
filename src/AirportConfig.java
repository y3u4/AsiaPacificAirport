/**
 * Central place for every tunable number in the simulation.
 * All values are constants, so they are safely visible to every thread (no sharing problem).
 */
public final class AirportConfig {
    private AirportConfig() { }

    // ---- Fixed airport rules (from the brief) ----
    public static final int TOTAL_PLANES = 6;            // planes that try to land
    public static final int GATE_COUNT = 3;              // Gate-1 .. Gate-3
    public static final int MAX_AIRCRAFT_ON_GROUND = 3;  // runway included
    public static final int MAX_PASSENGERS = 50;         // per plane
    public static final int MAX_ARRIVAL_GAP_MS = 2000;   // gap = rand.nextInt(2000) -> 0..1999 ms

    // ---- Congested scenario ----
    public static final int PLANES_WAITING_FOR_CONGESTION = 2;   // 2 planes circling
    public static final long CONGESTION_TIMEOUT_MS = 20_000;     // safety net so Main can never hang

    // ---- How long each step takes (ms). "Each step must take some time." ----
    public static final int LAND_MS = 1000;
    public static final int COAST_MS = 1000;
    public static final int DOCK_MS = 500;
    public static final int DISEMBARK_MS = 2000;
    public static final int CLEAN_MS = 2500;
    public static final int REFUEL_MS = 2000;
    public static final int BOARD_MS = 2000;
    public static final int UNDOCK_MS = 500;
    public static final int TAKEOFF_MS = 1000;
}
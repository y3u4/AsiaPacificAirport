/**
 * Single console writer. Every line is prefixed with the NAME OF THE THREAD THAT PRINTS IT,
 * so a thread can never speak on behalf of another one (objects are not processes!).
 * The method is synchronized so two lines can never interleave (see "Scrambled Output" in the notes).
 * Lock order: this lock is a LEAF lock - it never calls out while held, so it cannot cause deadlock.
 */
public final class Logger {
    private static final long START_MS = System.currentTimeMillis();

    private Logger() { }

    public static synchronized void log(String message) {
        double seconds = (System.currentTimeMillis() - START_MS) / 1000.0;
        System.out.printf("[%6.2fs] %s: %s%n", seconds, Thread.currentThread().getName(), message);
    }
}
package android.os;

/** Unit tests only, in place of the stub of android.jar: a clock the test moves ({@link Handler#advanceTo}). */
public final class SystemClock {
    public static volatile long now = 1_000_000L;

    public static long elapsedRealtime() {
        return now;
    }

    public static long uptimeMillis() {
        return now;
    }
}

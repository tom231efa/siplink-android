package android.os;

/** Unit tests only, in place of the stub of android.jar: the one looper of {@link Handler}. */
public final class Looper {
    private static final Looper MAIN = new Looper();

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return MAIN;
    }
}

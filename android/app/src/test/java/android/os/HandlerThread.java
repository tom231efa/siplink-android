package android.os;

/** Unit tests only, in place of the stub of android.jar: a thread whose looper is the one of {@link Handler}. */
public class HandlerThread extends Thread {
    public HandlerThread(String name) {
        super(name);
    }

    @Override
    public synchronized void start() {}

    public Looper getLooper() {
        return Looper.getMainLooper();
    }
}

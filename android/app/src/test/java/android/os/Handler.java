package android.os;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests only, in place of the stub of android.jar: one queue for every handler, run by hand
 * with the clock of {@link SystemClock} ({@link #advanceTo}).
 */
public class Handler {
    private static final class Task {
        final Handler owner;
        final Runnable runnable;
        final long at;
        final long order;

        Task(Handler owner, Runnable runnable, long at, long order) {
            this.owner = owner;
            this.runnable = runnable;
            this.at = at;
            this.order = order;
        }
    }

    private static final List<Task> QUEUE = new ArrayList<>();
    private static long order = 0;

    public Handler() {}

    public Handler(Looper looper) {}

    public final boolean post(Runnable runnable) {
        return postDelayed(runnable, 0);
    }

    public final boolean postDelayed(Runnable runnable, long delayMillis) {
        synchronized (QUEUE) {
            QUEUE.add(new Task(this, runnable, SystemClock.now + delayMillis, order++));
        }
        return true;
    }

    public final void removeCallbacks(Runnable runnable) {
        synchronized (QUEUE) {
            QUEUE.removeIf(task -> task.owner == this && task.runnable == runnable);
        }
    }

    public final void removeCallbacksAndMessages(Object token) {
        synchronized (QUEUE) {
            QUEUE.removeIf(task -> task.owner == this);
        }
    }

    /** Moves the clock to {@code to}, running every task that is due on the way, in order. */
    public static void advanceTo(long to) {
        while (true) {
            Task next = null;
            synchronized (QUEUE) {
                for (Task task : QUEUE) {
                    if (task.at <= to && (next == null || task.at < next.at || (task.at == next.at && task.order < next.order))) next = task;
                }
                if (next != null) QUEUE.remove(next);
            }
            if (next == null) break;
            if (next.at > SystemClock.now) SystemClock.now = next.at;
            next.runnable.run();
        }
        SystemClock.now = to;
    }

    public static void reset() {
        synchronized (QUEUE) {
            QUEUE.clear();
        }
    }
}

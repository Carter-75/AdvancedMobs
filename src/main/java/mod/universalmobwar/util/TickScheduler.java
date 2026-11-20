package mod.universalmobwar.util;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal tick-based delayed task scheduler for server work.
 *
 * Tasks are stored with the server tick they should run on and are executed
 * from the END_SERVER_TICK handler in UniversalMobWarMod. This replaces a
 * pattern where delayed work was deferred with server.execute(...) plus a
 * Thread.sleep(...) that ran on the server thread, freezing the whole
 * server for the length of the sleep.
 */
public final class TickScheduler {

    private record ScheduledTask(long runAtTick, Runnable task) {
    }

    private static final List<ScheduledTask> TASKS = new ArrayList<>();

    private TickScheduler() {
    }

    /**
     * Schedules a task to run after the given number of server ticks.
     * A delay of 0 runs the task on the next scheduler pass (end of the
     * current tick), matching the old execute(...) deferral.
     */
    public static void schedule(MinecraftServer server, int delayTicks, Runnable task) {
        if (server == null || task == null) {
            return;
        }
        long runAt = server.getTicks() + Math.max(0, delayTicks);
        synchronized (TASKS) {
            TASKS.add(new ScheduledTask(runAt, task));
        }
    }

    /**
     * Runs every task whose tick has arrived. Called once per server tick.
     */
    public static void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        List<Runnable> due = null;
        long now = server.getTicks();
        synchronized (TASKS) {
            Iterator<ScheduledTask> it = TASKS.iterator();
            while (it.hasNext()) {
                ScheduledTask scheduled = it.next();
                if (scheduled.runAtTick() <= now) {
                    if (due == null) {
                        due = new ArrayList<>();
                    }
                    due.add(scheduled.task());
                    it.remove();
                }
            }
        }
        if (due == null) {
            return;
        }
        for (Runnable task : due) {
            try {
                task.run();
            } catch (Exception ignored) {
                // A failing delayed task must not break the tick loop.
            }
        }
    }

    /**
     * Drops all pending tasks. Called when the server stops so stale tasks
     * referencing the old server's worlds can never fire.
     */
    public static void clear() {
        synchronized (TASKS) {
            TASKS.clear();
        }
    }
}

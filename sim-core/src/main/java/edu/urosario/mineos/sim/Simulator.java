package edu.urosario.mineos.sim;

import edu.urosario.mineos.mem.ChunkCache;
import edu.urosario.mineos.mem.Chunks;
import edu.urosario.mineos.mem.DiskModel;
import edu.urosario.mineos.mem.ReplacementPolicy;
import edu.urosario.mineos.model.Job;
import edu.urosario.mineos.model.TaskSpec;
import edu.urosario.mineos.model.TaskType;
import edu.urosario.mineos.sched.TickScheduler;
import edu.urosario.mineos.trace.Trace;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Motor de simulación del bucle de ticks del servidor, en tiempo simulado (microsegundos).
 *
 * <p>Modelo:
 * <ul>
 *   <li>Un solo hilo de CPU ejecuta las tareas de cada tick según el {@link TickScheduler}.</li>
 *   <li>Antes de ejecutarse, una tarea necesita su chunk en RAM ({@link ChunkCache}). Si no está,
 *       hay un page fault: con E/S asíncrona la tarea se bloquea y la CPU sigue con otras; con E/S
 *       síncrona el hilo entero espera al disco.</li>
 *   <li>El tick <i>k</i> arranca en {@code max(fin del anterior, k·50 ms)}. Si el trabajo excede 50 ms
 *       el tick se alarga (MSPT &gt; 50) y el TPS cae, como en un servidor real.</li>
 *   <li>Con presupuesto activo, el tick se corta a los {@code budgetUs}; lo pendiente pasa al
 *       siguiente tick. Si una entidad generaría una tarea nueva mientras la anterior sigue
 *       pendiente, la nueva se descarta (la entidad se actualiza "menos veces").</li>
 * </ul>
 */
public final class Simulator {
    private Simulator() {
    }

    public static Result run(Trace trace, String scenarioName, String configName, SystemConfig cfg,
                             TickScheduler sched, ReplacementPolicy repl) {
        DiskModel disk = new DiskModel(cfg.ioThreads(), cfg.diskLatencyUs());
        ChunkCache cache = new ChunkCache(cfg.frames(), repl, disk);

        Set<Long> pending = new HashSet<>();
        // Desempate por uid para que la simulación sea reproducible aunque dos E/S terminen a la vez.
        PriorityQueue<Job> blocked = new PriorityQueue<>(
                Comparator.comparingLong((Job j) -> j.readyAtUs).thenComparingLong(j -> j.spec.uid()));
        Map<Integer, Long> lastPlayerChunk = new HashMap<>();

        Map<TaskType, LongBuf> latBuf = new EnumMap<>(TaskType.class);
        Map<TaskType, Long> generated = new EnumMap<>(TaskType.class);
        Map<TaskType, Long> skipped = new EnumMap<>(TaskType.class);
        for (TaskType t : TaskType.values()) {
            latBuf.put(t, new LongBuf());
            generated.put(t, 0L);
            skipped.put(t, 0L);
        }
        LongBuf mspt = new LongBuf();

        int totalTicks = trace.tickCount();
        int warm = Math.min(cfg.warmupTicks(), Math.max(0, totalTicks - 1));
        long now = 0;
        long cpuBusy = 0;
        long measureStart = 0;
        long overruns = 0;

        for (int k = 0; k < totalTicks; k++) {
            long tickStart = Math.max(now, (long) k * cfg.tickUs());
            now = tickStart;
            boolean measure = k >= warm;
            if (k == warm) {
                cache.resetStats();
                disk.resetBusy();
                cpuBusy = 0;
                measureStart = tickStart;
            }

            // ---- Llegan las tareas del tick ----
            for (TaskSpec spec : trace.tick(k)) {
                TaskType type = spec.type();
                if (measure) {
                    generated.merge(type, 1L, Long::sum);
                }
                if (!pending.add(spec.uid())) {
                    if (measure) {
                        skipped.merge(type, 1L, Long::sum);
                    }
                    continue;
                }
                sched.admit(new Job(spec, now, k));
                if (type == TaskType.PLAYER && cfg.prefetchDepth() > 0) {
                    prefetchAhead(cache, cfg, lastPlayerChunk, spec, now);
                }
            }
            sched.onTickStart(k);

            long budgetEnd = cfg.enforceBudget() ? tickStart + cfg.budgetUs() : Long.MAX_VALUE;

            // ---- Bucle de CPU del tick ----
            while (true) {
                while (!blocked.isEmpty() && blocked.peek().readyAtUs <= now) {
                    Job b = blocked.poll();
                    b.chunkReady = true;
                    cache.touch(b.chunkKey);
                    sched.admit(b);
                }
                if (now >= budgetEnd) {
                    break;
                }
                Job j = sched.next();
                if (j == null) {
                    if (blocked.isEmpty()) {
                        break; // todo el trabajo del tick terminó
                    }
                    long wakeAt = blocked.peek().readyAtUs;
                    if (wakeAt >= budgetEnd) {
                        now = budgetEnd;
                        break;
                    }
                    now = wakeAt; // CPU ociosa esperando al disco
                    continue;
                }

                if (!j.chunkReady) {
                    long ready = cache.access(j.chunkKey, now);
                    if (ready > now) {
                        if (cfg.asyncIo()) {
                            j.readyAtUs = ready;
                            blocked.add(j);
                            continue;
                        }
                        now = ready; // E/S síncrona: el hilo principal espera
                        if (now >= budgetEnd) {
                            j.chunkReady = true;
                            sched.onPreempt(j, false);
                            break;
                        }
                    }
                    j.chunkReady = true;
                }

                long quantum = sched.quantumUs(j);
                long slice = Math.min(Math.min(quantum, j.remainingUs), budgetEnd - now);
                now += slice;
                cpuBusy += slice;
                j.remainingUs -= slice;

                if (j.remainingUs == 0) {
                    pending.remove(j.spec.uid());
                    if (j.createdTick >= warm) {
                        latBuf.get(j.type()).add(now - j.createdAtUs);
                    }
                } else {
                    sched.onPreempt(j, slice >= quantum);
                }
            }

            if (measure) {
                long duration = now - tickStart;
                mspt.add(duration);
                if (duration > cfg.tickUs()) {
                    overruns++;
                }
            }
        }

        // ---- Métricas ----
        long endTime = Math.max(now, (long) totalTicks * cfg.tickUs());
        long elapsed = Math.max(1, endTime - measureStart);
        int measured = mspt.size();
        LatencyStats ms = LatencyStats.of(mspt.raw(), measured);
        double tps = Math.min(20.0, measured / (elapsed / 1_000_000.0));

        Map<TaskType, LatencyStats> lat = new EnumMap<>(TaskType.class);
        for (TaskType t : TaskType.values()) {
            LongBuf b = latBuf.get(t);
            lat.put(t, LatencyStats.of(b.raw(), b.size()));
        }
        return new Result(scenarioName, configName, sched.name(), repl.name(), measured,
                ms.meanMs(), ms.p95Ms(), ms.p99Ms(),
                measured == 0 ? 0 : 100.0 * overruns / measured, tps,
                (double) cpuBusy / elapsed,
                (double) disk.busyUs() / ((double) elapsed * disk.ioThreads()),
                cache.hits(), cache.faults(), cache.inflightWaits(), cache.evictions(),
                cache.prefetchLoads(), cache.prefetchUseful(), cache.prefetchWasted(),
                lat, generated, skipped);
    }

    /**
     * Prefetch por localidad espacial: cuando un jugador cambia de chunk, se precargan los chunks
     * que están en su dirección de avance (extrapolada de su último cambio de chunk).
     */
    private static void prefetchAhead(ChunkCache cache, SystemConfig cfg, Map<Integer, Long> lastChunk,
                                      TaskSpec player, long now) {
        long cur = Chunks.key(player.cx(), player.cz());
        Long prev = lastChunk.put(player.key(), cur);
        if (prev == null || prev == cur) {
            return;
        }
        int dx = Integer.signum(player.cx() - Chunks.cx(prev));
        int dz = Integer.signum(player.cz() - Chunks.cz(prev));
        int lx = -dz;
        int lz = dx; // vector lateral (perpendicular a la dirección)
        for (int d = 1; d <= cfg.prefetchDepth(); d++) {
            for (int l = -cfg.prefetchWidth(); l <= cfg.prefetchWidth(); l++) {
                int cx = player.cx() + dx * d + lx * l;
                int cz = player.cz() + dz * d + lz * l;
                cache.prefetch(Chunks.key(cx, cz), now, cfg.prefetchMaxBacklogUs());
            }
        }
    }
}

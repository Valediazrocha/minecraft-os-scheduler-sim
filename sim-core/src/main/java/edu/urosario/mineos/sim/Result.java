package edu.urosario.mineos.sim;

import edu.urosario.mineos.model.TaskType;

import java.util.Locale;
import java.util.Map;

/**
 * Resultado de una corrida. Todas las métricas excluyen los ticks de calentamiento.
 *
 * @param msptAvg     duración media de un tick (ms); vanilla se considera sano por debajo de 50
 * @param msptP95     percentil 95 de la duración de un tick
 * @param msptP99     percentil 99 de la duración de un tick
 * @param overrunPct  porcentaje de ticks que superaron los 50 ms
 * @param tps         ticks por segundo efectivos (máximo 20)
 * @param cpuUtil     fracción del tiempo en que la CPU ejecutó tareas
 * @param ioUtil      fracción del tiempo en que el disco estuvo ocupado (promedio entre hilos de E/S)
 * @param latency     latencia (creación → fin) por tipo de tarea, en ms
 * @param generated   tareas generadas por tipo
 * @param skipped     tareas descartadas porque la anterior de la misma entidad seguía pendiente
 */
public record Result(String scenario, String config, String scheduler, String replacement,
                     int measuredTicks, double msptAvg, double msptP95, double msptP99,
                     double overrunPct, double tps, double cpuUtil, double ioUtil,
                     long hits, long faults, long inflightWaits, long evictions,
                     long prefetchLoads, long prefetchUseful, long prefetchWasted,
                     Map<TaskType, LatencyStats> latency,
                     Map<TaskType, Long> generated, Map<TaskType, Long> skipped) {

    public double hitRatio() {
        long total = hits + faults + inflightWaits;
        return total == 0 ? 1.0 : (double) hits / total;
    }

    /** Porcentaje de actualizaciones de un tipo que se perdieron por ir atrasado. */
    public double skipPct(TaskType t) {
        long g = generated.getOrDefault(t, 0L);
        return g == 0 ? 0 : 100.0 * skipped.getOrDefault(t, 0L) / g;
    }

    public static String csvHeader() {
        return "scenario,config,scheduler,replacement,ticks,mspt_avg,mspt_p95,mspt_p99,overrun_pct,tps,"
                + "cpu_util,io_util,hits,faults,inflight,hit_ratio,evictions,prefetch_loads,prefetch_useful,"
                + "prefetch_wasted,player_p50_ms,player_p99_ms,redstone_p99_ms,entity_p99_ms,chunktick_p99_ms,"
                + "player_skip_pct,redstone_skip_pct,entity_skip_pct,chunktick_skip_pct";
    }

    public String toCsvRow() {
        return String.format(Locale.ROOT,
                "%s,%s,%s,%s,%d,%.3f,%.3f,%.3f,%.2f,%.3f,%.4f,%.4f,%d,%d,%d,%.4f,%d,%d,%d,%d,"
                        + "%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f",
                scenario, config, scheduler, replacement, measuredTicks, msptAvg, msptP95, msptP99,
                overrunPct, tps, cpuUtil, ioUtil, hits, faults, inflightWaits, hitRatio(), evictions,
                prefetchLoads, prefetchUseful, prefetchWasted,
                lat(TaskType.PLAYER).p50Ms(), lat(TaskType.PLAYER).p99Ms(),
                lat(TaskType.REDSTONE).p99Ms(), lat(TaskType.ENTITY).p99Ms(), lat(TaskType.CHUNK_TICK).p99Ms(),
                skipPct(TaskType.PLAYER), skipPct(TaskType.REDSTONE), skipPct(TaskType.ENTITY),
                skipPct(TaskType.CHUNK_TICK));
    }

    public LatencyStats lat(TaskType t) {
        return latency.getOrDefault(t, LatencyStats.EMPTY);
    }
}

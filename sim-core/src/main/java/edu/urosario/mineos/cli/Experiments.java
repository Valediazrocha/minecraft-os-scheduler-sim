package edu.urosario.mineos.cli;

import edu.urosario.mineos.mem.ReplacementPolicy;
import edu.urosario.mineos.model.TaskType;
import edu.urosario.mineos.sched.FcfsScheduler;
import edu.urosario.mineos.sched.MfqScheduler;
import edu.urosario.mineos.sched.PriorityScheduler;
import edu.urosario.mineos.sched.TickScheduler;
import edu.urosario.mineos.sim.Result;
import edu.urosario.mineos.sim.Simulator;
import edu.urosario.mineos.sim.SystemConfig;
import edu.urosario.mineos.trace.Scenario;
import edu.urosario.mineos.trace.Trace;
import edu.urosario.mineos.trace.WorkloadGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Corre la matriz de experimentos: escenario × configuración × scheduler × reemplazo de chunks,
 * imprime tablas comparativas y guarda todos los resultados en CSV.
 *
 * <pre>
 * Uso:
 *   java -cp sim-core/target/classes edu.urosario.mineos.cli.Experiments [opciones]
 *     --scenario light|medium|heavy|explorers|all   (por defecto: all)
 *     --trace archivo.csv      reproduce una traza real (grabada por el plugin) en vez de generar una
 *     --export-trace archivo   guarda la traza sintética del escenario elegido y termina
 *     --frames N               marcos de RAM (por defecto depende del escenario)
 *     --ticks N                duración de la traza sintética
 *     --sweep-frames           en vez de la matriz, barre el tamaño de RAM (curva de thrashing)
 *     --out directorio         dónde escribir results.csv (por defecto: results)
 * </pre>
 */
public final class Experiments {

    static final String[] SCHEDULERS = {"FCFS", "PRIORITY", "MFQ"};
    static final String[] REPLACEMENTS = {"FIFO", "LRU", "CLOCK"};
    static final String[] CONFIGS = {"vanilla", "async", "budget", "prefetch-naive", "prefetch-throttled"};

    private Experiments() {
    }

    public static void main(String[] args) throws IOException {
        String scenarioArg = "all";
        String traceFile = null;
        String exportFile = null;
        Integer framesArg = null;
        Integer ticksArg = null;
        boolean sweep = false;
        Path outDir = Path.of("results");

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--scenario" -> scenarioArg = need(args, ++i);
                case "--trace" -> traceFile = need(args, ++i);
                case "--export-trace" -> exportFile = need(args, ++i);
                case "--frames" -> framesArg = Integer.parseInt(need(args, ++i));
                case "--ticks" -> ticksArg = Integer.parseInt(need(args, ++i));
                case "--sweep-frames" -> sweep = true;
                case "--out" -> outDir = Path.of(need(args, ++i));
                default -> throw new IllegalArgumentException("Opción desconocida: " + args[i]);
            }
        }

        List<Result> all = new ArrayList<>();
        if (traceFile != null) {
            Trace trace = Trace.readCsv(Path.of(traceFile));
            int frames = framesArg != null ? framesArg : 2000;
            System.out.printf("Traza real: %s (%d ticks, %d tareas), %d marcos%n",
                    traceFile, trace.tickCount(), trace.taskCount(), frames);
            all.addAll(runScenario("trace", trace, frames));
        } else {
            List<Scenario> scenarios = new ArrayList<>();
            if (scenarioArg.equalsIgnoreCase("all")) {
                scenarios.addAll(Scenario.all());
            } else {
                scenarios.add(Scenario.byName(scenarioArg));
            }
            for (Scenario s : scenarios) {
                if (ticksArg != null) {
                    s = s.withTicks(ticksArg);
                }
                Trace trace = WorkloadGenerator.generate(s);
                if (exportFile != null) {
                    trace.writeCsv(Path.of(exportFile));
                    System.out.printf("Traza de '%s' guardada en %s (%d ticks, %d tareas)%n",
                            s.name(), exportFile, trace.tickCount(), trace.taskCount());
                    return;
                }
                int frames = framesArg != null ? framesArg : defaultFrames(s);
                if (sweep) {
                    sweepFrames(s, trace, frames);
                    continue;
                }
                System.out.printf("%n==================== Escenario '%s': %d jugadores, %d marcos, %d tareas/tick en promedio ====================%n",
                        s.name(), s.players(), frames, trace.taskCount() / Math.max(1, trace.tickCount()));
                all.addAll(runScenario(s.name(), trace, frames));
            }
        }

        if (sweep) {
            return;
        }
        Files.createDirectories(outDir);
        Path csv = outDir.resolve("results.csv");
        List<String> lines = new ArrayList<>();
        lines.add(Result.csvHeader());
        for (Result r : all) {
            lines.add(r.toCsvRow());
        }
        Files.write(csv, lines);
        System.out.printf("%nResultados completos (%d corridas) en %s%n", all.size(), csv);
    }

    /** Marcos de RAM por escenario: lo bastante justos como para que la política de reemplazo importe. */
    static int defaultFrames(Scenario s) {
        return switch (s.name()) {
            case "light" -> 400;
            case "medium" -> 1500;
            case "heavy" -> 3000;
            case "explorers" -> 1800;
            default -> 2000;
        };
    }


    /** Barre el tamaño de RAM de 40 % a 160 % del valor por defecto para ver el precipicio del thrashing. */
    static void sweepFrames(Scenario s, Trace trace, int baseFrames) {
        System.out.printf("%n==== Barrido de RAM, escenario '%s' (MFQ, E/S asíncrona; base = %d marcos) ====%n",
                s.name(), baseFrames);
        System.out.printf("%-8s %-6s | %-10s %8s %8s %6s %8s%n", "frames", "%base", "repl", "hit%", "mspt", "tps", "io%");
        int[] pct = {40, 60, 80, 100, 130, 160};
        for (int p : pct) {
            int frames = Math.max(1, baseFrames * p / 100);
            for (String repl : REPLACEMENTS) {
                Result r = runOne(s.name(), trace, frames, "async", "MFQ", repl);
                System.out.printf(Locale.ROOT, "%-8d %5d%% | %-10s %7.2f%% %8.2f %6.2f %7.1f%%%n",
                        frames, p, repl, 100 * r.hitRatio(), r.msptAvg(), r.tps(), 100 * r.ioUtil());
            }
        }
    }

    static List<Result> runScenario(String name, Trace trace, int frames) {
        List<Result> results = new ArrayList<>();
        for (String cfgName : CONFIGS) {
            for (String sched : SCHEDULERS) {
                for (String repl : REPLACEMENTS) {
                    results.add(runOne(name, trace, frames, cfgName, sched, repl));
                }
            }
        }
        printSchedulerTable("A. Efecto del PLANIFICADOR  (E/S asíncrona, sin presupuesto, reemplazo LRU)",
                results, "async", "LRU");
        printSchedulerTable("B. Efecto del PLANIFICADOR con presupuesto de 50 ms  (reemplazo LRU)",
                results, "budget", "LRU");
        printReplacementTable("C. Efecto del REEMPLAZO de chunks  (E/S asíncrona, planificador MFQ)",
                results, "async", "MFQ");
        printConfigTable("D. Efecto de la ARQUITECTURA  (MFQ + LRU)", results, "MFQ", "LRU");
        return results;
    }

    public static Result runOne(String scenario, Trace trace, int frames, String cfgName,
                                String sched, String repl) {
        SystemConfig cfg = switch (cfgName) {
            case "vanilla" -> SystemConfig.vanilla(frames);
            case "async" -> SystemConfig.async(frames);
            case "budget" -> SystemConfig.budget(frames);
            case "prefetch-naive" -> SystemConfig.naivePrefetch(frames);
            case "prefetch-throttled" -> SystemConfig.full(frames);
            default -> throw new IllegalArgumentException("Configuración desconocida: " + cfgName);
        };
        return Simulator.run(trace, scenario, cfgName, cfg, newScheduler(sched),
                ReplacementPolicy.create(repl, frames));
    }

    public static TickScheduler newScheduler(String name) {
        return switch (name.toUpperCase()) {
            case "FCFS" -> new FcfsScheduler();
            case "PRIORITY" -> new PriorityScheduler();
            case "MFQ" -> new MfqScheduler();
            default -> throw new IllegalArgumentException("Planificador desconocido: " + name);
        };
    }

    // ------------------------------------------------------------------ tablas

    private static void printSchedulerTable(String title, List<Result> rs, String cfg, String repl) {
        System.out.printf("%n%s%n", title);
        System.out.printf("%-9s %8s %8s %6s %7s | %9s %9s | %9s %9s %9s | %7s %7s%n",
                "sched", "mspt", "mspt_p99", "tps", "cpu%", "plyr_p50", "plyr_p99", "rs_p99", "ent_p99",
                "chk_p99", "ent_skp", "chk_skp");
        for (String s : SCHEDULERS) {
            Result r = find(rs, cfg, s, repl);
            System.out.printf(Locale.ROOT, "%-9s %8.2f %8.2f %6.2f %6.1f%% | %9.2f %9.2f | %9.2f %9.2f %9.2f | %6.1f%% %6.1f%%%n",
                    s, r.msptAvg(), r.msptP99(), r.tps(), 100 * r.cpuUtil(),
                    r.lat(TaskType.PLAYER).p50Ms(), r.lat(TaskType.PLAYER).p99Ms(),
                    r.lat(TaskType.REDSTONE).p99Ms(), r.lat(TaskType.ENTITY).p99Ms(),
                    r.lat(TaskType.CHUNK_TICK).p99Ms(),
                    r.skipPct(TaskType.ENTITY), r.skipPct(TaskType.CHUNK_TICK));
        }
        System.out.println("(tiempos en ms; plyr/rs/ent/chk = latencia de jugador/redstone/entidad/chunk-tick; skp = % de actualizaciones perdidas)");
    }

    private static void printReplacementTable(String title, List<Result> rs, String cfg, String sched) {
        System.out.printf("%n%s%n", title);
        System.out.printf("%-7s %9s %9s %9s %9s %8s %8s %8s%n",
                "repl", "faults", "inflight", "hit_ratio", "evict", "mspt", "tps", "io%");
        for (String p : REPLACEMENTS) {
            Result r = find(rs, cfg, sched, p);
            System.out.printf(Locale.ROOT, "%-7s %9d %9d %8.2f%% %9d %8.2f %8.2f %7.1f%%%n",
                    p, r.faults(), r.inflightWaits(), 100 * r.hitRatio(), r.evictions(),
                    r.msptAvg(), r.tps(), 100 * r.ioUtil());
        }
    }

    private static void printConfigTable(String title, List<Result> rs, String sched, String repl) {
        System.out.printf("%n%s%n", title);
        System.out.printf("%-19s %8s %8s %8s %6s %8s %9s %9s %12s%n",
                "config", "mspt", "mspt_p99", "over>50", "tps", "faults", "plyr_p99", "ent_skp", "prefetch u/w");
        for (String c : CONFIGS) {
            Result r = find(rs, c, sched, repl);
            System.out.printf(Locale.ROOT, "%-19s %8.2f %8.2f %7.1f%% %6.2f %8d %9.2f %8.1f%% %6d/%-5d%n",
                    c, r.msptAvg(), r.msptP99(), r.overrunPct(), r.tps(), r.faults(),
                    r.lat(TaskType.PLAYER).p99Ms(), r.skipPct(TaskType.ENTITY),
                    r.prefetchUseful(), r.prefetchWasted());
        }
    }

    private static Result find(List<Result> rs, String cfg, String sched, String repl) {
        for (Result r : rs) {
            if (r.config().equals(cfg) && r.scheduler().equals(sched) && r.replacement().equals(repl)) {
                return r;
            }
        }
        throw new IllegalStateException("No hay resultado para " + cfg + "/" + sched + "/" + repl);
    }

    private static String need(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Falta el valor de " + args[i - 1]);
        }
        return args[i];
    }
}

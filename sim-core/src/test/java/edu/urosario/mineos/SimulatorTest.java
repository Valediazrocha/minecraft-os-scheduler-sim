package edu.urosario.mineos;

import edu.urosario.mineos.cli.Experiments;
import edu.urosario.mineos.mem.ReplacementPolicy;
import edu.urosario.mineos.model.TaskSpec;
import edu.urosario.mineos.model.TaskType;
import edu.urosario.mineos.sched.FcfsScheduler;
import edu.urosario.mineos.sim.Result;
import edu.urosario.mineos.sim.Simulator;
import edu.urosario.mineos.sim.SystemConfig;
import edu.urosario.mineos.trace.Scenario;
import edu.urosario.mineos.trace.Trace;
import edu.urosario.mineos.trace.WorkloadGenerator;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SimulatorTest {

    /** 1 tick, 0 de calentamiento, sin presupuesto, disco de 1500 µs. */
    static SystemConfig tiny(boolean asyncIo, int ioThreads) {
        return new SystemConfig(50_000, 0, false, 50_000, asyncIo, 10, 1_500, ioThreads, 0, 0, 0);
    }

    /** A (jugador, 1000 µs, chunk 0,0) y B (entidad, 500 µs, chunk 1,0), ambos en el tick 0. */
    static Trace twoTasks() {
        return new Trace(List.of(List.of(
                new TaskSpec(0, 1, TaskType.PLAYER, 0, 0, 1000),
                new TaskSpec(0, 2, TaskType.ENTITY, 1, 0, 500))));
    }

    @Test
    public void synchronousIoStallsTheWholeThread() {
        // t=0: A falla -> el hilo espera hasta 1500, corre A hasta 2500 (latencia 2.5 ms).
        // t=2500: B falla -> espera hasta 4000, corre B hasta 4500 (latencia 4.5 ms). El tick dura 4.5 ms.
        Result r = Simulator.run(twoTasks(), "t", "sync", tiny(false, 1),
                new FcfsScheduler(), ReplacementPolicy.create("LRU", 10));
        assertEquals(2.5, r.lat(TaskType.PLAYER).maxMs(), 1e-9);
        assertEquals(4.5, r.lat(TaskType.ENTITY).maxMs(), 1e-9);
        assertEquals(4.5, r.msptAvg(), 1e-9);
        assertEquals(2, r.faults());
    }

    @Test
    public void asynchronousIoOverlapsDiskWithCpu() {
        // Un hilo de E/S: A lista a 1500, B lista a 3000. La CPU queda ociosa hasta 1500, corre A hasta 2500,
        // espera a 3000 y corre B hasta 3500. El tick dura 3.5 ms (vs 4.5 ms en síncrono).
        Result r = Simulator.run(twoTasks(), "t", "async", tiny(true, 1),
                new FcfsScheduler(), ReplacementPolicy.create("LRU", 10));
        assertEquals(2.5, r.lat(TaskType.PLAYER).maxMs(), 1e-9);
        assertEquals(3.5, r.lat(TaskType.ENTITY).maxMs(), 1e-9);
        assertEquals(3.5, r.msptAvg(), 1e-9);
    }

    @Test
    public void twoIoThreadsLoadBothChunksInParallel() {
        // Con 2 hilos de E/S ambos chunks llegan a 1500: A corre 1500-2500 y B 2500-3000.
        Result r = Simulator.run(twoTasks(), "t", "async2", tiny(true, 2),
                new FcfsScheduler(), ReplacementPolicy.create("LRU", 10));
        assertEquals(2.5, r.lat(TaskType.PLAYER).maxMs(), 1e-9);
        assertEquals(3.0, r.lat(TaskType.ENTITY).maxMs(), 1e-9);
    }

    @Test
    public void budgetCutsTheTickAndCarriesWorkOver() {
        // Dos tareas de 30 ms en el mismo chunk, presupuesto de 50 ms: el tick 0 se corta a los 50 ms,
        // la segunda tarea termina en el tick 1 (la traza tiene 2 ticks vacíos de relleno).
        TaskSpec a = new TaskSpec(0, 1, TaskType.ENTITY, 0, 0, 30_000);
        TaskSpec b = new TaskSpec(0, 2, TaskType.ENTITY, 0, 0, 30_000);
        Trace trace = new Trace(List.of(List.of(a, b), List.of(), List.of()));
        SystemConfig cfg = new SystemConfig(50_000, 0, true, 50_000, true, 10, 0, 1, 0, 0, 0);
        Result r = Simulator.run(trace, "t", "budget", cfg, new FcfsScheduler(), ReplacementPolicy.create("LRU", 10));
        assertEquals(50.0, r.msptP99(), 1e-9);              // el tick 0 duró exactamente el presupuesto
        assertEquals(2, r.lat(TaskType.ENTITY).count());    // ambas terminaron
        assertEquals(60.0, r.lat(TaskType.ENTITY).maxMs(), 1e-9); // 30 + 30, la segunda cruzó de tick
    }

    @Test
    public void staleUpdatesOfTheSameEntityAreDropped() {
        // La entidad 1 genera una tarea de 70 ms en cada tick; el presupuesto es 50 ms, así que
        // la del tick 1 llega cuando la del tick 0 sigue pendiente y se descarta.
        TaskSpec t0 = new TaskSpec(0, 1, TaskType.ENTITY, 0, 0, 70_000);
        TaskSpec t1 = new TaskSpec(1, 1, TaskType.ENTITY, 0, 0, 70_000);
        Trace trace = new Trace(List.of(List.of(t0), List.of(t1), List.of()));
        SystemConfig cfg = new SystemConfig(50_000, 0, true, 50_000, true, 10, 0, 1, 0, 0, 0);
        Result r = Simulator.run(trace, "t", "budget", cfg, new FcfsScheduler(), ReplacementPolicy.create("LRU", 10));
        assertEquals(1L, (long) r.skipped().get(TaskType.ENTITY));
        assertEquals(2L, (long) r.generated().get(TaskType.ENTITY));
        assertEquals(50.0, r.skipPct(TaskType.ENTITY), 1e-9);
    }

    // ------------------------------------------------------------ propiedades sobre el workload sintético

    @Test
    public void generatorIsDeterministicPerSeed() {
        Scenario s = Scenario.light().withTicks(60);
        Trace a = WorkloadGenerator.generate(s);
        Trace b = WorkloadGenerator.generate(s);
        assertEquals(a, b);
        Trace c = WorkloadGenerator.generate(s.withSeed(s.seed() + 1));
        assertNotEquals(a, c);
    }

    @Test
    public void csvRoundTripPreservesTheTrace() throws IOException {
        Trace original = WorkloadGenerator.generate(Scenario.light().withTicks(40));
        Path f = Files.createTempFile("trace", ".csv");
        try {
            original.writeCsv(f);
            Trace back = Trace.readCsv(f);
            assertEquals(original.tickCount(), back.tickCount());
            assertEquals(original.taskCount(), back.taskCount());
            for (int k = 0; k < original.tickCount(); k++) {
                assertEquals(original.tick(k), back.tick(k));
            }
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test
    public void simulationIsReproducible() {
        Trace trace = WorkloadGenerator.generate(Scenario.medium().withTicks(300));
        Result a = Experiments.runOne("m", trace, 1500, "async", "MFQ", "LRU");
        Result b = Experiments.runOne("m", trace, 1500, "async", "MFQ", "LRU");
        assertEquals(a, b);
    }

    @Test
    public void withoutBudgetNoWorkIsLostAndEverythingCompletes() {
        Trace trace = WorkloadGenerator.generate(Scenario.medium().withTicks(300));
        for (String sched : new String[]{"FCFS", "PRIORITY", "MFQ"}) {
            Result r = Experiments.runOne("m", trace, 1500, "async", sched, "LRU");
            for (TaskType t : TaskType.values()) {
                long completed = r.lat(t).count();
                long expected = r.generated().get(t) - r.skipped().get(t);
                assertEquals(sched + "/" + t, expected, completed);
                assertEquals(sched + "/" + t + " no debe perder actualizaciones sin presupuesto",
                        0L, (long) r.skipped().get(t));
            }
        }
    }

    @Test
    public void withoutMemoryPressureSchedulersDoTheSameTotalWork() {
        // Con RAM de sobra casi no hay page faults: el orden no cambia cuánto trabajo hay, así que
        // sin presupuesto el MSPT medio apenas varía entre políticas (son "work-conserving").
        Trace trace = WorkloadGenerator.generate(Scenario.medium().withTicks(400));
        double fcfs = Experiments.runOne("m", trace, 4000, "async", "FCFS", "LRU").msptAvg();
        double prio = Experiments.runOne("m", trace, 4000, "async", "PRIORITY", "LRU").msptAvg();
        double mfq = Experiments.runOne("m", trace, 4000, "async", "MFQ", "LRU").msptAvg();
        assertEquals(fcfs, prio, fcfs * 0.02);
        assertEquals(fcfs, mfq, fcfs * 0.02);
    }

    @Test
    public void deferringFaultingTasksExposesDiskWaitAtTheEndOfTheTick() {
        // Con presión de memoria, PRIORITY deja para el final los CHUNK_TICK (los que más fallan): sus
        // lecturas se piden cuando la CPU ya no tiene otra cosa que hacer, y el disco no se solapa con
        // el cómputo. Mismos page faults, pero la CPU queda más tiempo ociosa y el tick se alarga.
        Trace trace = WorkloadGenerator.generate(Scenario.medium().withTicks(400));
        Result fcfs = Experiments.runOne("m", trace, 1500, "async", "FCFS", "LRU");
        Result prio = Experiments.runOne("m", trace, 1500, "async", "PRIORITY", "LRU");
        assertEquals((double) fcfs.faults(), (double) prio.faults(), fcfs.faults() * 0.01);
        assertTrue("cpu FCFS=" + fcfs.cpuUtil() + " PRIORITY=" + prio.cpuUtil(),
                prio.cpuUtil() < fcfs.cpuUtil() - 0.02);
        assertTrue(prio.msptAvg() > fcfs.msptAvg());
    }

    @Test
    public void priorityGivesPlayersMuchLowerLatencyThanFcfs() {
        Trace trace = WorkloadGenerator.generate(Scenario.medium().withTicks(400));
        double fcfs = Experiments.runOne("m", trace, 1500, "async", "FCFS", "LRU").lat(TaskType.PLAYER).p99Ms();
        double prio = Experiments.runOne("m", trace, 1500, "async", "PRIORITY", "LRU").lat(TaskType.PLAYER).p99Ms();
        assertTrue("PRIORITY p99 " + prio + " debería ser < la mitad de FCFS " + fcfs, prio < fcfs / 2);
    }

    @Test
    public void budgetCapsMsptAtFiftyMilliseconds() {
        Trace trace = WorkloadGenerator.generate(Scenario.heavy().withTicks(300));
        for (String sched : new String[]{"FCFS", "PRIORITY", "MFQ"}) {
            Result r = Experiments.runOne("h", trace, 3000, "budget", sched, "LRU");
            assertTrue(sched + " mspt p99=" + r.msptP99(), r.msptP99() <= 50.0 + 1e-9);
            assertEquals(20.0, r.tps(), 1e-6);
        }
        Result vanilla = Experiments.runOne("h", trace, 3000, "async", "FCFS", "LRU");
        assertTrue("sin presupuesto el servidor sobrecargado supera 50 ms", vanilla.msptAvg() > 50.0);
        assertTrue(vanilla.tps() < 20.0);
    }

    @Test
    public void synchronousIoIsNeverFasterThanAsynchronous() {
        Trace trace = WorkloadGenerator.generate(Scenario.explorers().withTicks(300));
        Result sync = Experiments.runOne("e", trace, 1800, "vanilla", "FCFS", "LRU");
        Result async = Experiments.runOne("e", trace, 1800, "async", "FCFS", "LRU");
        assertTrue("sync " + sync.msptAvg() + " vs async " + async.msptAvg(), sync.msptAvg() > async.msptAvg());
    }

    @Test
    public void lruNeverFaultsMoreThanFifoOnTheMemoryStressedScenario() {
        Trace trace = WorkloadGenerator.generate(Scenario.explorers().withTicks(400));
        Result fifo = Experiments.runOne("e", trace, 1800, "async", "MFQ", "FIFO");
        Result lru = Experiments.runOne("e", trace, 1800, "async", "MFQ", "LRU");
        assertTrue(lru.faults() < fifo.faults());
    }
}

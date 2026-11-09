package edu.urosario.mineos;

import edu.urosario.mineos.model.Job;
import edu.urosario.mineos.model.TaskSpec;
import edu.urosario.mineos.model.TaskType;
import edu.urosario.mineos.sched.FcfsScheduler;
import edu.urosario.mineos.sched.MfqScheduler;
import edu.urosario.mineos.sched.PriorityScheduler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SchedulerTest {

    static Job job(TaskType type, int key, int cost) {
        return new Job(new TaskSpec(0, key, type, 0, 0, cost), 0, 0);
    }

    @Test
    public void fcfsServesInArrivalOrderAndNeverPreempts() {
        FcfsScheduler s = new FcfsScheduler();
        Job a = job(TaskType.CHUNK_TICK, 1, 100);
        Job b = job(TaskType.PLAYER, 2, 100);
        s.admit(a);
        s.admit(b);
        assertEquals(Long.MAX_VALUE, s.quantumUs(a));
        assertSame(a, s.next()); // llegó primero aunque sea menos importante
        assertSame(b, s.next());
        assertNull(s.next());
    }

    @Test
    public void fcfsResumesCutJobFirst() {
        FcfsScheduler s = new FcfsScheduler();
        Job a = job(TaskType.ENTITY, 1, 100);
        Job b = job(TaskType.ENTITY, 2, 100);
        s.admit(a);
        s.admit(b);
        Job first = s.next();
        s.onPreempt(first, false); // cortado por el presupuesto del tick
        assertSame(a, s.next());
    }

    @Test
    public void priorityServesHigherLevelFirst() {
        PriorityScheduler s = new PriorityScheduler();
        Job chunk = job(TaskType.CHUNK_TICK, 1, 50);
        Job entity = job(TaskType.ENTITY, 2, 50);
        Job redstone = job(TaskType.REDSTONE, 3, 50);
        Job player = job(TaskType.PLAYER, 4, 50);
        s.admit(chunk);
        s.admit(entity);
        s.admit(redstone);
        s.admit(player);
        assertSame(player, s.next());
        assertSame(redstone, s.next());
        assertSame(entity, s.next());
        assertSame(chunk, s.next());
    }

    @Test
    public void priorityAgingPromotesStarvedJobs() {
        PriorityScheduler s = new PriorityScheduler(2_000, 3);
        Job chunk = job(TaskType.CHUNK_TICK, 1, 50); // nivel 3
        s.admit(chunk);
        assertEquals(3, chunk.level);
        for (int t = 0; t < 3; t++) {
            s.onTickStart(t);
        }
        assertEquals("tras 3 ticks esperando sube un nivel", 2, chunk.level);
        for (int t = 3; t < 9; t++) {
            s.onTickStart(t);
        }
        assertEquals("tras 6 ticks más llega al nivel 0", 0, chunk.level);
    }

    @Test
    public void priorityDoesNotAgeJobsThatGetCpu() {
        PriorityScheduler s = new PriorityScheduler(2_000, 3);
        Job entity = job(TaskType.ENTITY, 1, 50);
        s.admit(entity);
        s.onTickStart(0);
        s.onTickStart(1);
        assertSame(entity, s.next()); // recibe CPU: su espera se reinicia
        s.onPreempt(entity, true);
        s.onTickStart(2);
        s.onTickStart(3);
        assertEquals(2, entity.level);
    }

    @Test
    public void mfqDemotesAfterFullQuantumAndKeepsLevelOtherwise() {
        MfqScheduler s = new MfqScheduler(500, 2_000, 20);
        Job a = job(TaskType.REDSTONE, 1, 5_000);
        s.admit(a);
        assertEquals(0, a.level);
        assertEquals(500, s.quantumUs(a));
        s.next();
        s.onPreempt(a, true);
        assertEquals(1, a.level);
        assertEquals(2_000, s.quantumUs(a));
        s.next();
        s.onPreempt(a, true);
        assertEquals(2, a.level);
        assertEquals(Long.MAX_VALUE, s.quantumUs(a));
        s.next();
        s.onPreempt(a, true); // ya está en el último nivel
        assertEquals(2, a.level);

        Job b = job(TaskType.ENTITY, 2, 5_000);
        s.admit(b);
        s.next(); // sale b
        s.onPreempt(b, false); // cortado por el presupuesto, no por quantum: conserva el nivel
        assertEquals(0, b.level);
    }

    @Test
    public void mfqServesLightJobsBeforeDemotedOnes() {
        MfqScheduler s = new MfqScheduler(500, 2_000, 20);
        Job heavy = job(TaskType.REDSTONE, 1, 5_000);
        s.admit(heavy);
        s.next();
        s.onPreempt(heavy, true); // heavy baja al nivel 1
        Job light = job(TaskType.PLAYER, 2, 100);
        s.admit(light);
        assertSame(light, s.next());
        assertSame(heavy, s.next());
    }

    @Test
    public void mfqBoostSendsEverythingBackToTopLevel() {
        MfqScheduler s = new MfqScheduler(500, 2_000, 20);
        Job a = job(TaskType.REDSTONE, 1, 9_000);
        s.admit(a);
        s.next();
        s.onPreempt(a, true);
        s.next();
        s.onPreempt(a, true);
        assertEquals(2, a.level);
        s.onTickStart(19);
        assertEquals(2, a.level);
        s.onTickStart(20);
        assertEquals(0, a.level);
    }
}

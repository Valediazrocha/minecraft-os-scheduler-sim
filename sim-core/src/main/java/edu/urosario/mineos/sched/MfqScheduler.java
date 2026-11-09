package edu.urosario.mineos.sched;

import edu.urosario.mineos.model.Job;

import java.util.ArrayDeque;

/**
 * Multi-Level Feedback Queue de 3 niveles. No sabe qué tipo de tarea es cada una: todas nacen en
 * el nivel 0 (quantum corto) y se degradan si agotan su quantum, de modo que las tareas pequeñas y
 * "interactivas" (jugadores, mobs) terminan antes que las pesadas (redstone, granjas) sin que nadie
 * lo declare. Una tarea que se bloquea por E/S conserva su nivel. Cada {@code boostEveryTicks}
 * ticks, todo vuelve al nivel 0 (boost) para evitar inanición.
 *
 * <p>Último nivel: FCFS sin expropiación (quantum infinito).
 */
public final class MfqScheduler implements TickScheduler {
    private final ArrayDeque<Job>[] queues;
    private final long[] quantums;
    private final int boostEveryTicks;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public MfqScheduler(long q0Us, long q1Us, int boostEveryTicks) {
        this.quantums = new long[]{q0Us, q1Us, Long.MAX_VALUE};
        this.boostEveryTicks = boostEveryTicks;
        this.queues = new ArrayDeque[quantums.length];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ArrayDeque<>();
        }
    }

    public MfqScheduler() {
        this(500, 2_000, 20);
    }

    @Override
    public String name() {
        return "MFQ";
    }

    @Override
    public void admit(Job job) {
        if (job.level < 0) {
            job.level = 0;
        }
        queues[job.level].addLast(job);
    }

    @Override
    public Job next() {
        for (ArrayDeque<Job> q : queues) {
            Job j = q.pollFirst();
            if (j != null) {
                return j;
            }
        }
        return null;
    }

    @Override
    public long quantumUs(Job job) {
        return quantums[job.level];
    }

    @Override
    public void onPreempt(Job job, boolean usedFullQuantum) {
        if (usedFullQuantum) {
            if (job.level < queues.length - 1) {
                job.level++;
            }
            queues[job.level].addLast(job);
        } else {
            queues[job.level].addFirst(job);
        }
    }

    @Override
    public void onTickStart(int tick) {
        if (boostEveryTicks > 0 && tick > 0 && tick % boostEveryTicks == 0) {
            for (int lvl = 1; lvl < queues.length; lvl++) {
                Job j;
                while ((j = queues[lvl].pollFirst()) != null) {
                    j.level = 0;
                    queues[0].addLast(j);
                }
            }
        }
    }

    @Override
    public int size() {
        int n = 0;
        for (ArrayDeque<Job> q : queues) {
            n += q.size();
        }
        return n;
    }
}

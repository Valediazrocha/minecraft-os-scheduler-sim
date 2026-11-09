package edu.urosario.mineos.sched;

import edu.urosario.mineos.model.Job;

import java.util.ArrayDeque;

/**
 * First-Come First-Served, sin expropiación: es el comportamiento del servidor vanilla
 * (un hilo, una cola, cada tarea corre hasta terminar). Es la línea base.
 */
public final class FcfsScheduler implements TickScheduler {
    private final ArrayDeque<Job> queue = new ArrayDeque<>();

    @Override
    public String name() {
        return "FCFS";
    }

    @Override
    public void admit(Job job) {
        queue.addLast(job);
    }

    @Override
    public Job next() {
        return queue.pollFirst();
    }

    @Override
    public long quantumUs(Job job) {
        return Long.MAX_VALUE;
    }

    @Override
    public void onPreempt(Job job, boolean usedFullQuantum) {
        // Solo puede ocurrir por corte del presupuesto: retoma primero, como si nunca se hubiera ido.
        queue.addFirst(job);
    }

    @Override
    public void onTickStart(int tick) {
    }

    @Override
    public int size() {
        return queue.size();
    }
}

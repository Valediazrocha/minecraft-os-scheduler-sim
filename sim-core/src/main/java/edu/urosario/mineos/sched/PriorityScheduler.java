package edu.urosario.mineos.sched;

import edu.urosario.mineos.model.Job;
import edu.urosario.mineos.model.TaskType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Colas de prioridad múltiples con 4 niveles fijos por tipo de tarea (jugador &gt; redstone &gt;
 * entidades &gt; ticks de chunk), Round Robin dentro de cada nivel y <b>aging</b>: una tarea que lleva
 * {@code agingTicks} ticks sin recibir CPU sube un nivel, para evitar inanición.
 *
 * <p>Necesita conocer de antemano qué es importante (prioridad estática por tipo).
 */
public final class PriorityScheduler implements TickScheduler {
    private final ArrayDeque<Job>[] queues;
    private final long quantumUs;
    private final int agingTicks;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public PriorityScheduler(long quantumUs, int agingTicks) {
        this.quantumUs = quantumUs;
        this.agingTicks = agingTicks;
        this.queues = new ArrayDeque[TaskType.LEVELS];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ArrayDeque<>();
        }
    }

    public PriorityScheduler() {
        this(2_000, 3);
    }

    @Override
    public String name() {
        return "PRIORITY";
    }

    @Override
    public void admit(Job job) {
        if (job.level < 0) {
            job.level = job.type().priority;
        }
        job.waitTicks = 0;
        queues[job.level].addLast(job);
    }

    @Override
    public Job next() {
        for (ArrayDeque<Job> q : queues) {
            Job j = q.pollFirst();
            if (j != null) {
                j.waitTicks = 0;
                return j;
            }
        }
        return null;
    }

    @Override
    public long quantumUs(Job job) {
        return quantumUs;
    }

    @Override
    public void onPreempt(Job job, boolean usedFullQuantum) {
        if (usedFullQuantum) {
            queues[job.level].addLast(job);
        } else {
            queues[job.level].addFirst(job);
        }
    }

    @Override
    public void onTickStart(int tick) {
        // Se recorre del nivel 1 hacia abajo: un ascendido va al nivel anterior, que ya fue procesado
        // en esta llamada, así que no se le cuenta un tick de espera de más.
        for (int lvl = 1; lvl < queues.length; lvl++) {
            List<Job> promoted = new ArrayList<>();
            for (Iterator<Job> it = queues[lvl].iterator(); it.hasNext(); ) {
                Job j = it.next();
                j.waitTicks++;
                if (j.waitTicks >= agingTicks) {
                    it.remove();
                    promoted.add(j);
                }
            }
            for (Job j : promoted) {
                j.level = lvl - 1;
                j.waitTicks = 0;
                queues[lvl - 1].addLast(j);
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

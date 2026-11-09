package edu.urosario.mineos.sched;

import edu.urosario.mineos.model.Job;

/**
 * Política de planificación de la CPU del hilo principal del servidor.
 * El motor decide <i>cuándo</i> llamar; el planificador decide <i>a quién</i> darle la CPU y por cuánto.
 */
public interface TickScheduler {

    String name();

    /** Una tarea entra a la cola de listos (nueva, o desbloqueada tras su E/S). */
    void admit(Job job);

    /** Saca la siguiente tarea a ejecutar, o {@code null} si no hay ninguna lista. */
    Job next();

    /** Máximo de CPU (µs) que puede usar {@code job} de corrido; {@code Long.MAX_VALUE} = sin expropiación. */
    long quantumUs(Job job);

    /**
     * La tarea fue interrumpida sin terminar.
     *
     * @param usedFullQuantum {@code true} si agotó su quantum; {@code false} si se cortó por otra razón
     *                        (fin del presupuesto del tick), en cuyo caso debe retomar primero.
     */
    void onPreempt(Job job, boolean usedFullQuantum);

    /** Se llama al comenzar cada tick (aging, boost periódico...). */
    void onTickStart(int tick);

    /** Tareas listas en este momento. */
    int size();
}

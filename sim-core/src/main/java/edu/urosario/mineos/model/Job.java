package edu.urosario.mineos.model;

import edu.urosario.mineos.mem.Chunks;

/**
 * Estado de ejecución de una tarea dentro del simulador (el "PCB" de la tarea).
 * Es mutable y lo manipulan el motor y el planificador.
 */
public final class Job {
    public final TaskSpec spec;
    public final long createdAtUs;
    public final int createdTick;
    public final long chunkKey;

    /** CPU que le falta, en microsegundos. */
    public long remainingUs;
    /** Nivel de cola actual; -1 hasta que el planificador lo admite por primera vez. */
    public int level = -1;
    /** Ticks consecutivos esperando en una cola sin recibir CPU (para aging). */
    public int waitTicks;
    /** Si está bloqueada por E/S de disco: instante en que su chunk estará listo. */
    public long readyAtUs;
    /** Verdadero cuando su chunk ya está en memoria y puede ejecutarse. */
    public boolean chunkReady;

    public Job(TaskSpec spec, long createdAtUs, int createdTick) {
        this.spec = spec;
        this.createdAtUs = createdAtUs;
        this.createdTick = createdTick;
        this.remainingUs = spec.costUs();
        this.chunkKey = Chunks.key(spec.cx(), spec.cz());
    }

    public TaskType type() {
        return spec.type();
    }
}

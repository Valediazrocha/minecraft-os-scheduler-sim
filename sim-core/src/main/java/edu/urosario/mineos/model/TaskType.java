package edu.urosario.mineos.model;

/**
 * Tipos de trabajo que el servidor ejecuta en cada tick. La prioridad estática (0 = más alta)
 * es la que usa el planificador Priority; MFQ la ignora y "aprende" de la conducta de cada tarea.
 */
public enum TaskType {
    /** Acciones de un jugador (movimiento, combate, paquetes). Lo que el jugador "siente" como lag. */
    PLAYER(0),
    /** Máquinas de redstone: pocas tareas, muy costosas. */
    REDSTONE(1),
    /** IA y física de mobs. Muchas tareas pequeñas. */
    ENTITY(2),
    /** Ticks aleatorios de bloques (cultivos, hojas, fuego). Lo más postergable. */
    CHUNK_TICK(3);

    public final int priority;

    TaskType(int priority) {
        this.priority = priority;
    }

    public static final int LEVELS = 4;
}

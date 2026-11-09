package edu.urosario.mineos.model;

/**
 * Descripción inmutable de una tarea que aparece en un tick: es la unidad de una {@code Trace}.
 *
 * @param tick   tick en el que se genera la tarea
 * @param key    identidad estable dentro de su tipo (id de jugador, de entidad, de máquina...)
 * @param type   tipo de tarea
 * @param cx     coordenada X del chunk que la tarea necesita en memoria
 * @param cz     coordenada Z del chunk que la tarea necesita en memoria
 * @param costUs tiempo de CPU que necesita, en microsegundos
 */
public record TaskSpec(int tick, int key, TaskType type, int cx, int cz, int costUs) {

    /** Identificador único de la "actividad" (tipo + key). Sirve para fusionar actualizaciones pendientes. */
    public long uid() {
        return ((long) type.ordinal() << 32) | (key & 0xffffffffL);
    }
}

package edu.urosario.mineos.mem;

import java.util.function.LongPredicate;

/**
 * Algoritmo de reemplazo de páginas aplicado a chunks: decide qué chunk sale de la RAM
 * cuando hace falta espacio para cargar otro.
 */
public interface ReplacementPolicy {

    /** Valor devuelto por {@link #chooseVictim} cuando ningún chunk cumple la condición. */
    long NO_VICTIM = Long.MIN_VALUE;

    String name();

    /** Un chunk acaba de cargarse en un marco. */
    void onLoad(long key);

    /** Un chunk residente fue referenciado. */
    void onHit(long key);

    /** El chunk salió de memoria. */
    void onRemove(long key);

    /** Elige la víctima entre los chunks residentes que cumplan {@code evictable}. */
    long chooseVictim(LongPredicate evictable);

    static ReplacementPolicy create(String name, int frames) {
        return switch (name.toUpperCase()) {
            case "FIFO" -> new FifoPolicy();
            case "LRU" -> new LruPolicy();
            case "CLOCK" -> new ClockPolicy(frames);
            default -> throw new IllegalArgumentException("Política de reemplazo desconocida: " + name);
        };
    }
}

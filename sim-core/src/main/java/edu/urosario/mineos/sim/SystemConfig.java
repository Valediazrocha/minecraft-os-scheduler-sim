package edu.urosario.mineos.sim;

/**
 * Configuración del "hardware" y de las decisiones de diseño del servidor simulado.
 *
 * @param tickUs                  duración nominal de un tick (50 000 µs = 20 TPS)
 * @param warmupTicks             ticks iniciales que no entran en las métricas (caché fría)
 * @param enforceBudget           si es {@code true}, un tick se corta al llegar a {@code budgetUs} y el
 *                                trabajo pendiente pasa al siguiente tick (se degrada la frescura, no el TPS)
 * @param budgetUs                presupuesto de CPU por tick
 * @param asyncIo                 si es {@code true}, una tarea que falla por un chunk se bloquea y la CPU
 *                                atiende a otras; si es {@code false}, el hilo se queda esperando al disco
 * @param frames                  chunks que caben en RAM
 * @param diskLatencyUs           tiempo de una lectura de chunk
 * @param ioThreads               lecturas de disco simultáneas
 * @param prefetchDepth           chunks por delante del jugador que se precargan (0 = sin prefetch)
 * @param prefetchWidth           semiancho lateral de la franja precargada
 * @param prefetchMaxBacklogUs    el prefetch se omite si el disco ya tiene más cola que esto
 *                                ({@code Long.MAX_VALUE} = ingenuo, sin throttling)
 */
public record SystemConfig(long tickUs, int warmupTicks, boolean enforceBudget, long budgetUs,
                           boolean asyncIo, int frames, long diskLatencyUs, int ioThreads,
                           int prefetchDepth, int prefetchWidth, long prefetchMaxBacklogUs) {

    public static final long TICK_US = 50_000;

    /** Servidor vanilla: E/S síncrona, sin presupuesto, sin prefetch. */
    public static SystemConfig vanilla(int frames) {
        return new SystemConfig(TICK_US, 100, false, TICK_US, false, frames, 1_500, 1, 0, 0, 0);
    }

    /** Hilo de E/S aparte: la CPU no espera al disco. */
    public static SystemConfig async(int frames) {
        return new SystemConfig(TICK_US, 100, false, TICK_US, true, frames, 1_500, 2, 0, 0, 0);
    }

    /** E/S asíncrona + presupuesto de 50 ms por tick. */
    public static SystemConfig budget(int frames) {
        return new SystemConfig(TICK_US, 100, true, TICK_US, true, frames, 1_500, 2, 0, 0, 0);
    }

    /** Presupuesto + prefetch ingenuo: dispara todas las lecturas especulativas sin mirar el disco. */
    public static SystemConfig naivePrefetch(int frames) {
        return new SystemConfig(TICK_US, 100, true, TICK_US, true, frames, 1_500, 2, 6, 3, Long.MAX_VALUE);
    }

    /** Presupuesto + prefetch con throttling: solo precarga si el disco está (casi) libre. */
    public static SystemConfig full(int frames) {
        return new SystemConfig(TICK_US, 100, true, TICK_US, true, frames, 1_500, 2, 6, 3, 1_500);
    }

    public SystemConfig withFrames(int newFrames) {
        return new SystemConfig(tickUs, warmupTicks, enforceBudget, budgetUs, asyncIo, newFrames,
                diskLatencyUs, ioThreads, prefetchDepth, prefetchWidth, prefetchMaxBacklogUs);
    }

    public SystemConfig withWarmup(int ticks) {
        return new SystemConfig(tickUs, ticks, enforceBudget, budgetUs, asyncIo, frames,
                diskLatencyUs, ioThreads, prefetchDepth, prefetchWidth, prefetchMaxBacklogUs);
    }
}

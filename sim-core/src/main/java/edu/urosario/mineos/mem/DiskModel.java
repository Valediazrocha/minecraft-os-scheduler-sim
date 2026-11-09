package edu.urosario.mineos.mem;

/**
 * Disco simulado: {@code ioThreads} servidores independientes, cada uno atiende una carga de chunk
 * a la vez y tarda {@code latencyUs}. Si todos están ocupados, la petición espera en cola (FIFO).
 */
public final class DiskModel {
    private final long[] freeAt;
    private final long latencyUs;
    private long busyUs;

    public DiskModel(int ioThreads, long latencyUs) {
        if (ioThreads < 1) {
            throw new IllegalArgumentException("ioThreads debe ser >= 1");
        }
        this.freeAt = new long[ioThreads];
        this.latencyUs = latencyUs;
    }

    /** Encola una lectura pedida en {@code now}; devuelve el instante en que el chunk estará listo. */
    public long schedule(long now) {
        int best = 0;
        for (int i = 1; i < freeAt.length; i++) {
            if (freeAt[i] < freeAt[best]) {
                best = i;
            }
        }
        long start = Math.max(now, freeAt[best]);
        long end = start + latencyUs;
        freeAt[best] = end;
        busyUs += latencyUs;
        return end;
    }

    /** Cola actual: cuánto tendría que esperar una lectura pedida ahora antes de empezar (µs). */
    public long backlogUs(long now) {
        long best = Long.MAX_VALUE;
        for (long f : freeAt) {
            best = Math.min(best, Math.max(0, f - now));
        }
        return best;
    }

    public long busyUs() {
        return busyUs;
    }

    public int ioThreads() {
        return freeAt.length;
    }

    public void resetBusy() {
        busyUs = 0;
    }
}

package edu.urosario.mineos.mem;

import java.util.HashMap;

/**
 * RAM del servidor vista como una tabla de marcos: cada chunk residente ocupa un marco.
 * Acceder a un chunk ausente es un <b>page fault</b>: hay que elegir una víctima (según la
 * {@link ReplacementPolicy}) y leer el chunk del disco.
 *
 * <p>Una carga en curso ya ocupa su marco pero tiene {@code readyAt} en el futuro; quien la pida
 * mientras tanto espera a esa misma carga (no se pide dos veces).
 */
public final class ChunkCache {

    private static final class Entry {
        final long readyAt;
        final boolean prefetched;
        boolean used;

        Entry(long readyAt, boolean prefetched) {
            this.readyAt = readyAt;
            this.prefetched = prefetched;
            this.used = !prefetched;
        }
    }

    private final int capacity;
    private final ReplacementPolicy policy;
    private final DiskModel disk;
    private final HashMap<Long, Entry> resident = new HashMap<>();

    private long hits;
    private long faults;
    private long inflightWaits;
    private long evictions;
    private long prefetchLoads;
    private long prefetchUseful;
    private long prefetchWasted;

    public ChunkCache(int capacity, ReplacementPolicy policy, DiskModel disk) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity debe ser >= 1");
        }
        this.capacity = capacity;
        this.policy = policy;
        this.disk = disk;
    }

    /**
     * Acceso de una tarea a un chunk.
     *
     * @return instante en que el chunk está disponible: {@code <= now} si fue un acierto,
     *         mayor que {@code now} si hay que esperar al disco.
     */
    public long access(long key, long now) {
        Entry e = resident.get(key);
        if (e != null) {
            policy.onHit(key);
            if (e.prefetched && !e.used) {
                prefetchUseful++;
            }
            e.used = true;
            if (e.readyAt > now) {
                inflightWaits++;
                return e.readyAt;
            }
            hits++;
            return now;
        }
        faults++;
        return load(key, now, false);
    }

    /**
     * Pide el chunk por adelantado sin que nadie lo esté esperando. No cuenta como acceso.
     * Se omite si el disco ya tiene más de {@code maxBacklogUs} de cola: una lectura especulativa
     * no debe retrasar las que alguien está esperando de verdad.
     */
    public void prefetch(long key, long now, long maxBacklogUs) {
        if (resident.containsKey(key) || disk.backlogUs(now) > maxBacklogUs) {
            return;
        }
        prefetchLoads++;
        load(key, now, true);
    }

    /** Actualiza el recuerdo de uso sin contar estadísticas (una tarea desbloqueada retoma su chunk). */
    public void touch(long key) {
        if (resident.containsKey(key)) {
            policy.onHit(key);
        }
    }

    private long load(long key, long now, boolean prefetched) {
        if (resident.size() >= capacity) {
            evict(now);
        }
        long ready = disk.schedule(now);
        resident.put(key, new Entry(ready, prefetched));
        policy.onLoad(key);
        return ready;
    }

    private void evict(long now) {
        long victim = policy.chooseVictim(k -> resident.get(k).readyAt <= now);
        if (victim == ReplacementPolicy.NO_VICTIM) {
            // Todos los marcos tienen cargas en curso: se sacrifica cualquiera (caso extremo con pocos marcos).
            victim = policy.chooseVictim(k -> true);
        }
        Entry removed = resident.remove(victim);
        if (removed == null) {
            throw new IllegalStateException("La política eligió un chunk que no está residente: " + victim);
        }
        if (removed.prefetched && !removed.used) {
            prefetchWasted++;
        }
        policy.onRemove(victim);
        evictions++;
    }

    public void resetStats() {
        hits = 0;
        faults = 0;
        inflightWaits = 0;
        evictions = 0;
        prefetchLoads = 0;
        prefetchUseful = 0;
        prefetchWasted = 0;
    }

    public boolean isResident(long key) {
        return resident.containsKey(key);
    }

    public int residentCount() {
        return resident.size();
    }

    public int capacity() {
        return capacity;
    }

    public long hits() {
        return hits;
    }

    public long faults() {
        return faults;
    }

    public long inflightWaits() {
        return inflightWaits;
    }

    public long evictions() {
        return evictions;
    }

    public long prefetchLoads() {
        return prefetchLoads;
    }

    public long prefetchUseful() {
        return prefetchUseful;
    }

    public long prefetchWasted() {
        return prefetchWasted;
    }

    public String policyName() {
        return policy.name();
    }
}

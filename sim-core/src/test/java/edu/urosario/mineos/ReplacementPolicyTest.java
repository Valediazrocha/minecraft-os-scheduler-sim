package edu.urosario.mineos;

import edu.urosario.mineos.mem.ChunkCache;
import edu.urosario.mineos.mem.Chunks;
import edu.urosario.mineos.mem.DiskModel;
import edu.urosario.mineos.mem.ReplacementPolicy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Los algoritmos de reemplazo, contra cadenas de referencias de libro de texto con resultado conocido. */
public class ReplacementPolicyTest {

    /** Cadena de Silberschatz (Operating System Concepts): 20 referencias, 3 marcos. */
    static final int[] SILBERSCHATZ = {7, 0, 1, 2, 0, 3, 0, 4, 2, 3, 0, 3, 2, 1, 2, 0, 1, 7, 0, 1};
    /** Cadena de la anomalía de Belady. */
    static final int[] BELADY = {1, 2, 3, 4, 1, 2, 5, 1, 2, 3, 4, 5};

    static long faults(String policy, int frames, int[] refs) {
        ChunkCache cache = new ChunkCache(frames, ReplacementPolicy.create(policy, frames), new DiskModel(1, 0));
        long now = 0;
        for (int r : refs) {
            cache.access(Chunks.key(r, 0), now++);
        }
        return cache.faults();
    }

    @Test
    public void silberschatzFifoLruClock() {
        assertEquals(15, faults("FIFO", 3, SILBERSCHATZ));
        assertEquals(12, faults("LRU", 3, SILBERSCHATZ));
        // Clock calculado a mano paso a paso (bit de referencia en 1 al cargar, puntero inicia en el marco 0).
        assertEquals(14, faults("CLOCK", 3, SILBERSCHATZ));
    }

    @Test
    public void beladyAnomalyAppearsInFifoButNotInLru() {
        long fifo3 = faults("FIFO", 3, BELADY);
        long fifo4 = faults("FIFO", 4, BELADY);
        assertEquals(9, fifo3);
        assertEquals(10, fifo4);
        assertTrue("FIFO con más marcos produce MÁS faltas (anomalía de Belady)", fifo4 > fifo3);

        long lru3 = faults("LRU", 3, BELADY);
        long lru4 = faults("LRU", 4, BELADY);
        assertEquals(10, lru3);
        assertEquals(8, lru4);
        assertTrue("LRU es un algoritmo de pila: más marcos nunca empeora", lru4 <= lru3);
    }

    @Test
    public void evictionCountEqualsFaultsMinusFrames() {
        ChunkCache cache = new ChunkCache(3, ReplacementPolicy.create("LRU", 3), new DiskModel(1, 0));
        long now = 0;
        for (int r : SILBERSCHATZ) {
            cache.access(Chunks.key(r, 0), now++);
        }
        assertEquals(cache.faults() - 3, cache.evictions());
        assertEquals(3, cache.residentCount());
    }

    @Test
    public void lruKeepsRecentlyUsedChunk() {
        ChunkCache cache = new ChunkCache(2, ReplacementPolicy.create("LRU", 2), new DiskModel(1, 0));
        cache.access(Chunks.key(1, 0), 0);
        cache.access(Chunks.key(2, 0), 1);
        cache.access(Chunks.key(1, 0), 2); // 1 vuelve a ser el más reciente
        cache.access(Chunks.key(3, 0), 3); // debe salir el 2
        assertTrue(cache.isResident(Chunks.key(1, 0)));
        assertFalse(cache.isResident(Chunks.key(2, 0)));
        assertTrue(cache.isResident(Chunks.key(3, 0)));
    }

    @Test
    public void fifoIgnoresAccesses() {
        ChunkCache cache = new ChunkCache(2, ReplacementPolicy.create("FIFO", 2), new DiskModel(1, 0));
        cache.access(Chunks.key(1, 0), 0);
        cache.access(Chunks.key(2, 0), 1);
        cache.access(Chunks.key(1, 0), 2); // no cambia el orden de llegada
        cache.access(Chunks.key(3, 0), 3); // sale el 1, aunque se usó hace poco
        assertFalse(cache.isResident(Chunks.key(1, 0)));
        assertTrue(cache.isResident(Chunks.key(2, 0)));
    }

    @Test
    public void accessWhileLoadingWaitsForTheSameRead() {
        DiskModel disk = new DiskModel(1, 1500);
        ChunkCache cache = new ChunkCache(4, ReplacementPolicy.create("LRU", 4), disk);
        long first = cache.access(Chunks.key(5, 5), 100);
        long second = cache.access(Chunks.key(5, 5), 400); // misma carga en curso
        assertEquals(1600, first);
        assertEquals(1600, second);
        assertEquals(1, cache.faults());
        assertEquals(1, cache.inflightWaits());
        assertEquals(1500, disk.busyUs()); // el disco leyó el chunk una sola vez
    }

    @Test
    public void prefetchIsSkippedWhenDiskIsBacklogged() {
        DiskModel disk = new DiskModel(1, 1500);
        ChunkCache cache = new ChunkCache(10, ReplacementPolicy.create("LRU", 10), disk);
        cache.access(Chunks.key(0, 0), 0);          // el disco queda ocupado hasta 1500
        cache.prefetch(Chunks.key(1, 0), 0, 1000);  // cola de 1500 > 1000: se omite
        assertFalse(cache.isResident(Chunks.key(1, 0)));
        cache.prefetch(Chunks.key(1, 0), 0, 2000);  // cola de 1500 <= 2000: se hace
        assertTrue(cache.isResident(Chunks.key(1, 0)));
        assertEquals(1, cache.prefetchLoads());
    }

    @Test
    public void usedPrefetchIsCountedUseful() {
        ChunkCache cache = new ChunkCache(10, ReplacementPolicy.create("LRU", 10), new DiskModel(1, 0));
        cache.prefetch(Chunks.key(7, 7), 0, Long.MAX_VALUE);
        cache.access(Chunks.key(7, 7), 10);
        assertEquals(1, cache.prefetchUseful());
        assertEquals(0, cache.faults());
        assertEquals(1, cache.hits());
    }
}

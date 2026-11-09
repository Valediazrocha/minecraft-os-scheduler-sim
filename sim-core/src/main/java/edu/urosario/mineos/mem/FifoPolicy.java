package edu.urosario.mineos.mem;

import java.util.LinkedHashSet;
import java.util.function.LongPredicate;

/** FIFO: sale el chunk que lleva más tiempo en memoria, sin importar si se sigue usando. */
public final class FifoPolicy implements ReplacementPolicy {
    private final LinkedHashSet<Long> order = new LinkedHashSet<>();

    @Override
    public String name() {
        return "FIFO";
    }

    @Override
    public void onLoad(long key) {
        order.add(key);
    }

    @Override
    public void onHit(long key) {
        // FIFO ignora los accesos.
    }

    @Override
    public void onRemove(long key) {
        order.remove(key);
    }

    @Override
    public long chooseVictim(LongPredicate evictable) {
        for (long k : order) {
            if (evictable.test(k)) {
                return k;
            }
        }
        return NO_VICTIM;
    }
}

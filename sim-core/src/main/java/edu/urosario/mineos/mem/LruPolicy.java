package edu.urosario.mineos.mem;

import java.util.LinkedHashMap;
import java.util.function.LongPredicate;

/** LRU: sale el chunk que lleva más tiempo sin ser referenciado (aprovecha la localidad temporal). */
public final class LruPolicy implements ReplacementPolicy {
    // accessOrder = true: cada get() mueve la entrada al final; el primero es el menos reciente.
    private final LinkedHashMap<Long, Boolean> order = new LinkedHashMap<>(16, 0.75f, true);

    @Override
    public String name() {
        return "LRU";
    }

    @Override
    public void onLoad(long key) {
        order.put(key, Boolean.TRUE);
    }

    @Override
    public void onHit(long key) {
        order.get(key);
    }

    @Override
    public void onRemove(long key) {
        order.remove(key);
    }

    @Override
    public long chooseVictim(LongPredicate evictable) {
        for (long k : order.keySet()) {
            if (evictable.test(k)) {
                return k;
            }
        }
        return NO_VICTIM;
    }
}

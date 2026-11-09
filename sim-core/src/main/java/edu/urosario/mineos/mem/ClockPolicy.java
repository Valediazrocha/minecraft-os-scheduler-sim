package edu.urosario.mineos.mem;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.function.LongPredicate;

/**
 * Clock (segunda oportunidad): aproxima LRU con un solo bit de referencia por marco y un puntero
 * circular. Es lo que usan los sistemas operativos reales porque no necesita reordenar una lista
 * en cada acceso.
 */
public final class ClockPolicy implements ReplacementPolicy {
    private final int capacity;
    private final long[] keys;
    private final boolean[] occupied;
    private final boolean[] referenced;
    private final HashMap<Long, Integer> slotOf = new HashMap<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int hand;

    public ClockPolicy(int capacity) {
        this.capacity = capacity;
        this.keys = new long[capacity];
        this.occupied = new boolean[capacity];
        this.referenced = new boolean[capacity];
        for (int i = 0; i < capacity; i++) {
            freeSlots.addLast(i);
        }
    }

    @Override
    public String name() {
        return "CLOCK";
    }

    @Override
    public void onLoad(long key) {
        Integer slot = freeSlots.pollFirst();
        if (slot == null) {
            throw new IllegalStateException("Clock: no quedan marcos libres (¿falta llamar onRemove?)");
        }
        keys[slot] = key;
        occupied[slot] = true;
        referenced[slot] = true;
        slotOf.put(key, slot);
    }

    @Override
    public void onHit(long key) {
        Integer slot = slotOf.get(key);
        if (slot != null) {
            referenced[slot] = true;
        }
    }

    @Override
    public void onRemove(long key) {
        Integer slot = slotOf.remove(key);
        if (slot != null) {
            occupied[slot] = false;
            referenced[slot] = false;
            freeSlots.addFirst(slot); // el siguiente onLoad reutiliza este mismo marco
        }
    }

    @Override
    public long chooseVictim(LongPredicate evictable) {
        // Dos vueltas bastan: la primera limpia bits de referencia, la segunda encuentra víctima.
        for (int step = 0; step < 2 * capacity; step++) {
            int s = hand;
            hand = (hand + 1) % capacity;
            if (!occupied[s] || !evictable.test(keys[s])) {
                continue;
            }
            if (referenced[s]) {
                referenced[s] = false;
            } else {
                return keys[s];
            }
        }
        return NO_VICTIM;
    }
}

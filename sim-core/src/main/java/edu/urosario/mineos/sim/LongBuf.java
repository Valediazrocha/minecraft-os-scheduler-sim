package edu.urosario.mineos.sim;

import java.util.Arrays;

/** Lista de {@code long} sin boxing (las latencias pueden ser cientos de miles por corrida). */
final class LongBuf {
    private long[] data = new long[1024];
    private int size;

    void add(long v) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size * 2);
        }
        data[size++] = v;
    }

    int size() {
        return size;
    }

    long[] raw() {
        return data;
    }
}

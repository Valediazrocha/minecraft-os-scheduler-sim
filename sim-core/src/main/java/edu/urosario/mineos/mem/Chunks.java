package edu.urosario.mineos.mem;

/** Utilidades para codificar la posición de un chunk (cx, cz) en un solo {@code long}. */
public final class Chunks {
    private Chunks() {
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    public static int cx(long key) {
        return (int) (key >> 32);
    }

    public static int cz(long key) {
        return (int) key;
    }
}

package edu.urosario.mineos.sim;

import java.util.Arrays;

/** Resumen de una lista de latencias, expresado en milisegundos. */
public record LatencyStats(long count, double meanMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {

    public static final LatencyStats EMPTY = new LatencyStats(0, 0, 0, 0, 0, 0);

    /** @param valuesUs latencias en microsegundos (se ordena una copia, no el arreglo original) */
    public static LatencyStats of(long[] valuesUs, int n) {
        if (n == 0) {
            return EMPTY;
        }
        long[] v = Arrays.copyOf(valuesUs, n);
        Arrays.sort(v);
        double sum = 0;
        for (long x : v) {
            sum += x;
        }
        return new LatencyStats(n, sum / n / 1000.0, percentile(v, 0.50), percentile(v, 0.95),
                percentile(v, 0.99), v[n - 1] / 1000.0);
    }

    /** Percentil por rango más cercano (nearest-rank) sobre un arreglo ordenado, en ms. */
    static double percentile(long[] sortedUs, double p) {
        int rank = (int) Math.ceil(p * sortedUs.length);
        return sortedUs[Math.max(0, Math.min(sortedUs.length - 1, rank - 1))] / 1000.0;
    }
}

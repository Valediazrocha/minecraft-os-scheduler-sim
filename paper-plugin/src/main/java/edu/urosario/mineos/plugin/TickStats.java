package edu.urosario.mineos.plugin;

import java.util.Arrays;

/** Ventana deslizante con las últimas N duraciones de tick (MSPT), para comparar políticas en vivo. */
final class TickStats {
    private final double[] window;
    private int count;
    private int next;

    TickStats(int size) {
        this.window = new double[size];
    }

    synchronized void add(double msptMs) {
        window[next] = msptMs;
        next = (next + 1) % window.length;
        count = Math.min(count + 1, window.length);
    }

    synchronized void clear() {
        count = 0;
        next = 0;
    }

    synchronized int samples() {
        return count;
    }

    synchronized double mean() {
        if (count == 0) {
            return 0;
        }
        double s = 0;
        for (int i = 0; i < count; i++) {
            s += window[i];
        }
        return s / count;
    }

    /** Percentil por rango más cercano, igual que el simulador. */
    synchronized double percentile(double p) {
        if (count == 0) {
            return 0;
        }
        double[] v = Arrays.copyOf(window, count);
        Arrays.sort(v);
        int rank = (int) Math.ceil(p * count);
        return v[Math.max(0, Math.min(count - 1, rank - 1))];
    }

    /** Porcentaje de ticks que superaron los 50 ms. */
    synchronized double overrunPct() {
        if (count == 0) {
            return 0;
        }
        int over = 0;
        for (int i = 0; i < count; i++) {
            if (window[i] > 50.0) {
                over++;
            }
        }
        return 100.0 * over / count;
    }
}

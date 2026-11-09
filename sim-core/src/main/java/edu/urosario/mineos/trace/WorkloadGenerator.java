package edu.urosario.mineos.trace;

import edu.urosario.mineos.mem.Chunks;
import edu.urosario.mineos.model.TaskSpec;
import edu.urosario.mineos.model.TaskType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Genera una {@link Trace} determinista (misma semilla = misma traza) imitando cómo un servidor
 * de Minecraft reparte trabajo: los jugadores se mueven por el mundo y solo se actualiza lo que
 * está dentro de su distancia de simulación.
 *
 * <p>Supuestos simplificadores (documentados para el informe):
 * <ul>
 *   <li>Las entidades y máquinas de redstone son estáticas; lo que se mueve son los jugadores.</li>
 *   <li>El costo de CPU de cada tarea sale de distribuciones fijas, no de medición real.
 *       Cuando se use una traza real (plugin), estos costos se sustituyen por los medidos.</li>
 *   <li>El orden de llegada de las tareas dentro de un tick se baraja con la semilla, para no
 *       favorecer ni perjudicar a FCFS con un orden artificial.</li>
 * </ul>
 */
public final class WorkloadGenerator {
    private WorkloadGenerator() {
    }

    public static Trace generate(Scenario s) {
        Random rnd = new Random(s.seed());
        int radius = s.worldRadiusChunks();

        // ---- Entidades regulares: dispersas por el mundo ----
        int totalEntities = s.entities() + s.farms() * s.farmSize();
        int[] ecx = new int[totalEntities];
        int[] ecz = new int[totalEntities];
        int[] ecost = new int[totalEntities];
        int idx = 0;
        for (int i = 0; i < s.entities(); i++, idx++) {
            ecx[idx] = rnd.nextInt(2 * radius + 1) - radius;
            ecz[idx] = rnd.nextInt(2 * radius + 1) - radius;
            ecost[idx] = entityCost(rnd);
        }
        // ---- Granjas de mobs: muchas entidades baratas en un solo chunk ----
        for (int f = 0; f < s.farms(); f++) {
            int fx = rnd.nextInt(2 * radius + 1) - radius;
            int fz = rnd.nextInt(2 * radius + 1) - radius;
            for (int i = 0; i < s.farmSize(); i++, idx++) {
                ecx[idx] = fx;
                ecz[idx] = fz;
                ecost[idx] = 30 + rnd.nextInt(30);
            }
        }
        Map<Long, List<Integer>> entitiesByChunk = new HashMap<>();
        for (int i = 0; i < totalEntities; i++) {
            entitiesByChunk.computeIfAbsent(Chunks.key(ecx[i], ecz[i]), k -> new ArrayList<>()).add(i);
        }

        // ---- Redstone: pocas máquinas muy costosas ----
        int[] rcx = new int[s.redstoneMachines()];
        int[] rcz = new int[s.redstoneMachines()];
        int[] rcost = new int[s.redstoneMachines()];
        Map<Long, List<Integer>> redstoneByChunk = new HashMap<>();
        for (int i = 0; i < rcx.length; i++) {
            rcx[i] = rnd.nextInt(2 * radius + 1) - radius;
            rcz[i] = rnd.nextInt(2 * radius + 1) - radius;
            rcost[i] = 1200 + rnd.nextInt(3800);
            redstoneByChunk.computeIfAbsent(Chunks.key(rcx[i], rcz[i]), k -> new ArrayList<>()).add(i);
        }

        // ---- Jugadores: caminata aleatoria con persistencia de rumbo ----
        double[] px = new double[s.players()];
        double[] pz = new double[s.players()];
        double[] heading = new double[s.players()];
        double[] speed = new double[s.players()];
        int[] pbase = new int[s.players()];
        for (int p = 0; p < s.players(); p++) {
            px[p] = (rnd.nextDouble() * 2 - 1) * radius * 0.5;
            pz[p] = (rnd.nextDouble() * 2 - 1) * radius * 0.5;
            heading[p] = rnd.nextDouble() * Math.PI * 2;
            double f = rnd.nextDouble();
            speed[p] = s.playerSpeedChunksPerSec() / 20.0 * (0.05 + 0.95 * f * f); // muchos lentos, pocos rápidos
            pbase[p] = 150 + rnd.nextInt(200);
        }

        List<List<TaskSpec>> ticks = new ArrayList<>(s.ticks());
        int sim = s.simDistance();
        for (int t = 0; t < s.ticks(); t++) {
            List<TaskSpec> out = new ArrayList<>();
            Set<Long> active = new HashSet<>();
            Set<Long> chunkTicked = new HashSet<>();

            for (int p = 0; p < s.players(); p++) {
                heading[p] += rnd.nextGaussian() * 0.12;
                px[p] += Math.cos(heading[p]) * speed[p];
                pz[p] += Math.sin(heading[p]) * speed[p];
                // rebote en el borde del mundo
                if (px[p] < -radius || px[p] > radius) {
                    heading[p] = Math.PI - heading[p];
                    px[p] = Math.max(-radius, Math.min(radius, px[p]));
                }
                if (pz[p] < -radius || pz[p] > radius) {
                    heading[p] = -heading[p];
                    pz[p] = Math.max(-radius, Math.min(radius, pz[p]));
                }
                int cx = (int) Math.floor(px[p]);
                int cz = (int) Math.floor(pz[p]);

                out.add(new TaskSpec(t, p, TaskType.PLAYER, cx, cz, jitter(rnd, pbase[p])));

                for (int dx = -sim; dx <= sim; dx++) {
                    for (int dz = -sim; dz <= sim; dz++) {
                        active.add(Chunks.key(cx + dx, cz + dz));
                    }
                }
                for (int k = 0; k < s.chunkTicksPerPlayer(); k++) {
                    int ccx = cx + rnd.nextInt(9) - 4;
                    int ccz = cz + rnd.nextInt(9) - 4;
                    // Clave única por chunk (el patrón de bits de dos enteros de 16 bits no colisiona).
                    int key = ((ccx + 32768) << 16) | ((ccz + 32768) & 0xffff);
                    if (chunkTicked.add(Chunks.key(ccx, ccz))) { // un chunk recibe a lo sumo un tick de bloques por tick
                        out.add(new TaskSpec(t, key, TaskType.CHUNK_TICK, ccx, ccz, jitter(rnd, 35)));
                    }
                }
            }

            for (long chunk : active) {
                List<Integer> es = entitiesByChunk.get(chunk);
                if (es != null) {
                    for (int e : es) {
                        out.add(new TaskSpec(t, e, TaskType.ENTITY, ecx[e], ecz[e], jitter(rnd, ecost[e])));
                    }
                }
                List<Integer> rs = redstoneByChunk.get(chunk);
                if (rs != null) {
                    for (int r : rs) {
                        out.add(new TaskSpec(t, r, TaskType.REDSTONE, rcx[r], rcz[r], jitter(rnd, rcost[r])));
                    }
                }
            }

            Collections.shuffle(out, rnd); // orden de llegada no sesgado
            ticks.add(out);
        }
        return new Trace(ticks);
    }

    /** Mayoría de mobs baratos; una minoría (aldeanos con pathfinding) cuesta bastante más. */
    private static int entityCost(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.80) {
            return 20 + rnd.nextInt(40);
        }
        if (r < 0.95) {
            return 60 + rnd.nextInt(90);
        }
        return 150 + rnd.nextInt(250);
    }

    /** Variación de ±20 % por tick, nunca menos de 5 µs. */
    private static int jitter(Random rnd, int base) {
        double f = 0.8 + rnd.nextDouble() * 0.4;
        return Math.max(5, (int) Math.round(base * f));
    }
}

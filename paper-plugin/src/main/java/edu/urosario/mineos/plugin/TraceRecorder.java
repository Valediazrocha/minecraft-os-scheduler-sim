package edu.urosario.mineos.plugin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Graba una traza en el formato CSV del simulador ({@code tick,key,type,cx,cz,costUs}).
 *
 * <p><b>Limitación importante:</b> Bukkit/Paper no expone cuánto CPU consume cada entidad, así que el
 * costo por tarea sale de un modelo a priori ({@link #priorCostUs}). Para que la traza no quede
 * desconectada de la realidad, al terminar se <b>escala</b> todo el costo modelado para que el
 * tick medio modelado iguale al tick medio realmente medido. Eso calibra el total, no el reparto
 * entre entidades. Tampoco se registran REDSTONE ni CHUNK_TICK (no son observables desde la API).
 */
final class TraceRecorder {

    private record Sample(int tick, int key, String type, int cx, int cz, double rawCostUs) {
    }

    private final int targetTicks;
    private final List<Sample> samples = new ArrayList<>();
    private int ticksRecorded;
    private double measuredMsSum;

    TraceRecorder(int targetTicks) {
        this.targetTicks = targetTicks;
    }

    boolean isDone() {
        return ticksRecorded >= targetTicks;
    }

    int ticksRecorded() {
        return ticksRecorded;
    }

    /** Se llama al final de cada tick del servidor, con la duración real del tick en ms. */
    void onTickEnd(double tickDurationMs) {
        if (isDone()) {
            return;
        }
        int tick = ticksRecorded;
        int simChunks = Math.max(2, Bukkit.getSimulationDistance());
        for (World world : Bukkit.getWorlds()) {
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            for (Player p : players) {
                samples.add(new Sample(tick, p.getEntityId(), "PLAYER",
                        p.getLocation().getBlockX() >> 4, p.getLocation().getBlockZ() >> 4, 250));
            }
            for (LivingEntity e : world.getLivingEntities()) {
                if (e instanceof Player) {
                    continue;
                }
                int cx = e.getLocation().getBlockX() >> 4;
                int cz = e.getLocation().getBlockZ() >> 4;
                if (nearAnyPlayer(players, cx, cz, simChunks)) {
                    samples.add(new Sample(tick, e.getEntityId(), "ENTITY", cx, cz, priorCostUs(e)));
                }
            }
        }
        measuredMsSum += tickDurationMs;
        ticksRecorded++;
    }

    private static boolean nearAnyPlayer(List<Player> players, int cx, int cz, int simChunks) {
        for (Player p : players) {
            int pcx = p.getLocation().getBlockX() >> 4;
            int pcz = p.getLocation().getBlockZ() >> 4;
            if (Math.abs(pcx - cx) <= simChunks && Math.abs(pcz - cz) <= simChunks) {
                return true;
            }
        }
        return false;
    }

    /** Costo a priori por categoría de entidad, en microsegundos (valores de partida, no medidos). */
    static double priorCostUs(Entity e) {
        if (e instanceof Villager) {
            return 250; // pathfinding y comercio
        }
        if (e instanceof Monster) {
            return 60;
        }
        if (e instanceof Animals) {
            return 40;
        }
        return 50;
    }

    /** Escribe el CSV con el costo escalado; devuelve el factor de escala aplicado. */
    double writeCsv(Path file) throws IOException {
        double modeledUsPerTick = 0;
        for (Sample s : samples) {
            modeledUsPerTick += s.rawCostUs();
        }
        modeledUsPerTick /= Math.max(1, ticksRecorded);
        double measuredUsPerTick = 1000.0 * measuredMsSum / Math.max(1, ticksRecorded);
        double scale = modeledUsPerTick <= 0 ? 1.0 : measuredUsPerTick / modeledUsPerTick;
        // Evita escalas absurdas si el tick medido incluye mucho trabajo ajeno (carga de chunks, otros plugins).
        scale = Math.max(0.1, Math.min(scale, 20.0));

        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write("tick,key,type,cx,cz,costUs");
            w.newLine();
            for (Sample s : samples) {
                int cost = (int) Math.max(1, Math.round(s.rawCostUs() * scale));
                w.write(s.tick() + "," + s.key() + "," + s.type() + "," + s.cx() + "," + s.cz() + "," + cost);
                w.newLine();
            }
        }
        return scale;
    }
}

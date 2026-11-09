package edu.urosario.mineos.plugin;

import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Política "por niveles" aplicada al servidor real: es el análogo, dentro de lo que permite la API,
 * de las colas de prioridad del simulador. Los mobs se clasifican por distancia al jugador más cercano:
 *
 * <pre>
 *   nivel 0: &lt; 24 bloques   → la IA corre todos los ticks
 *   nivel 1: &lt; 64 bloques   → la IA corre 1 de cada 2 ticks
 *   nivel 2: resto            → la IA corre 1 de cada 4 ticks
 * </pre>
 *
 * <p>Se implementa alternando {@code Mob#setAware}. Es reversible ({@link #restoreAll()}), no borra
 * ni mueve nada. <b>Experimental:</b> degrada la reactividad de mobs lejanos a propósito (es el
 * equivalente real de las "actualizaciones descartadas" del simulador) y su efecto sobre el MSPT debe
 * medirse con {@code /mineos status}, no suponerse.
 */
final class TieredPolicy {
    static final double NEAR_SQ = 24.0 * 24.0;
    static final double MID_SQ = 64.0 * 64.0;

    private boolean enabled;
    private final Set<UUID> touched = new HashSet<>();

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Se llama al final de cada tick; los cambios de {@code setAware} rigen desde el tick siguiente. */
    void apply(int tick, Iterable<World> worlds) {
        if (!enabled) {
            return;
        }
        for (World world : worlds) {
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                continue;
            }
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                double d2 = nearestPlayerDistanceSq(mob, players);
                int period = d2 < NEAR_SQ ? 1 : (d2 < MID_SQ ? 2 : 4);
                boolean run = ((tick + mob.getEntityId()) % period) == 0;
                if (mob.isAware() != run) {
                    mob.setAware(run);
                }
                if (period > 1) {
                    touched.add(mob.getUniqueId());
                }
            }
        }
    }

    private static double nearestPlayerDistanceSq(Mob mob, List<Player> players) {
        double best = Double.MAX_VALUE;
        for (Player p : players) {
            best = Math.min(best, mob.getLocation().distanceSquared(p.getLocation()));
        }
        return best;
    }

    /** Devuelve la IA a todos los mobs que la política llegó a tocar. */
    void restoreAll() {
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            for (Mob mob : world.getEntitiesByClass(Mob.class)) {
                if (touched.contains(mob.getUniqueId())) {
                    mob.setAware(true);
                }
            }
        }
        touched.clear();
    }
}

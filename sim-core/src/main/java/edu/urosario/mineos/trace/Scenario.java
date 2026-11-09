package edu.urosario.mineos.trace;

import java.util.List;

/**
 * Parámetros del workload sintético de un servidor de Minecraft.
 *
 * @param name                  nombre del escenario
 * @param ticks                 duración en ticks (20 ticks = 1 s de juego)
 * @param players               jugadores conectados
 * @param playerSpeedChunksPerSec velocidad máxima de un jugador (caminar ~0.27, elytra ~2 chunks/s)
 * @param entities              mobs repartidos por el mundo (solo se actualizan los cercanos a un jugador)
 * @param farms                 granjas de mobs: muchas entidades concentradas en un solo chunk
 * @param farmSize              entidades por granja
 * @param redstoneMachines      máquinas de redstone repartidas por el mundo
 * @param chunkTicksPerPlayer   ticks aleatorios de bloque por jugador y tick
 * @param worldRadiusChunks     el mundo simulado es un cuadrado de lado 2*radio
 * @param simDistance           distancia de simulación (chunks) alrededor de cada jugador
 * @param seed                  semilla: misma semilla = misma traza
 */
public record Scenario(String name, int ticks, int players, double playerSpeedChunksPerSec,
                       int entities, int farms, int farmSize, int redstoneMachines,
                       int chunkTicksPerPlayer, int worldRadiusChunks, int simDistance, long seed) {

    /** Servidor pequeño entre amigos: sobra CPU. */
    public static Scenario light() {
        return new Scenario("light", 1200, 6, 0.3, 1500, 1, 80, 3, 10, 50, 6, 11L);
    }

    /** Servidor mediano: cerca del límite de 50 ms por tick. */
    public static Scenario medium() {
        return new Scenario("medium", 1200, 20, 0.4, 2400, 3, 90, 6, 12, 60, 6, 22L);
    }

    /** Servidor sobrecargado: granjas grandes y mucha redstone. */
    public static Scenario heavy() {
        return new Scenario("heavy", 1200, 30, 0.4, 5000, 6, 160, 16, 14, 60, 6, 33L);
    }

    /** Exploradores con elytra: lo que más estresa la memoria (muchos page faults). */
    public static Scenario explorers() {
        return new Scenario("explorers", 1200, 16, 2.0, 2500, 2, 100, 6, 10, 150, 6, 44L);
    }

    public static List<Scenario> all() {
        return List.of(light(), medium(), heavy(), explorers());
    }

    public static Scenario byName(String name) {
        for (Scenario s : all()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Escenario desconocido: " + name
                + " (disponibles: light, medium, heavy, explorers)");
    }

    public Scenario withTicks(int newTicks) {
        return new Scenario(name, newTicks, players, playerSpeedChunksPerSec, entities, farms, farmSize,
                redstoneMachines, chunkTicksPerPlayer, worldRadiusChunks, simDistance, seed);
    }

    public Scenario withSeed(long newSeed) {
        return new Scenario(name, ticks, players, playerSpeedChunksPerSec, entities, farms, farmSize,
                redstoneMachines, chunkTicksPerPlayer, worldRadiusChunks, simDistance, newSeed);
    }
}

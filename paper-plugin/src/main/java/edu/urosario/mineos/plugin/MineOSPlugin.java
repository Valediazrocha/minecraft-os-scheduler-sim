package edu.urosario.mineos.plugin;

import io.papermc.paper.event.server.ServerTickEndEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Plugin de MineOS. Tiene dos funciones, que cierran el ciclo con el simulador:
 * <ol>
 *   <li><b>Grabar</b> una traza de carga real ({@code /mineos record}) que el simulador reproduce con
 *       {@code Experiments --trace archivo.csv}.</li>
 *   <li><b>Validar</b>: activar/desactivar la política por niveles ({@code /mineos policy}) y comparar
 *       el MSPT real con {@code /mineos status}.</li>
 * </ol>
 * EXPERIMENTAL: este módulo no se pudo compilar ni probar en el entorno donde se escribió.
 */
public final class MineOSPlugin extends JavaPlugin implements Listener {

    private final TickStats stats = new TickStats(400); // últimos 20 s
    private final TieredPolicy policy = new TieredPolicy();
    private TraceRecorder recorder;
    private int tickCounter;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("MineOS activo. Usa /mineos para grabar trazas o activar la política.");
    }

    @Override
    public void onDisable() {
        policy.setEnabled(false);
        policy.restoreAll();
    }

    @EventHandler
    public void onTickEnd(ServerTickEndEvent event) {
        double tickMs = event.getTickDuration();
        stats.add(tickMs);
        tickCounter++;
        if (recorder != null) {
            recorder.onTickEnd(tickMs);
            if (recorder.isDone()) {
                finishRecording(Bukkit.getConsoleSender());
            }
        }
        policy.apply(tickCounter, Bukkit.getWorlds());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "record" -> {
                int ticks = 1200;
                if (args.length > 1) {
                    try {
                        ticks = Math.max(20, Integer.parseInt(args[1]));
                    } catch (NumberFormatException e) {
                        sender.sendMessage("Número de ticks inválido: " + args[1]);
                        return true;
                    }
                }
                recorder = new TraceRecorder(ticks);
                sender.sendMessage("Grabando " + ticks + " ticks (" + ticks / 20 + " s)...");
            }
            case "stop" -> {
                if (recorder == null) {
                    sender.sendMessage("No hay ninguna grabación en curso.");
                } else {
                    finishRecording(sender);
                }
            }
            case "status" -> sender.sendMessage(String.format(Locale.ROOT,
                    "MSPT (últimos %d ticks): media %.2f ms | p95 %.2f | p99 %.2f | ticks >50 ms: %.1f%% | política: %s",
                    stats.samples(), stats.mean(), stats.percentile(0.95), stats.percentile(0.99),
                    stats.overrunPct(), policy.isEnabled() ? "tiered" : "off"));
            case "policy" -> {
                if (args.length < 2) {
                    return false;
                }
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "tiered" -> {
                        policy.setEnabled(true);
                        stats.clear();
                        sender.sendMessage("Política por niveles ACTIVADA (estadísticas reiniciadas).");
                    }
                    case "off" -> {
                        policy.setEnabled(false);
                        policy.restoreAll();
                        stats.clear();
                        sender.sendMessage("Política DESACTIVADA, IA restaurada (estadísticas reiniciadas).");
                    }
                    default -> {
                        return false;
                    }
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void finishRecording(CommandSender to) {
        TraceRecorder r = recorder;
        recorder = null;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = getDataFolder().toPath().resolve("trace-" + stamp + ".csv");
        try {
            getDataFolder().mkdirs();
            double scale = r.writeCsv(file);
            to.sendMessage(String.format(Locale.ROOT,
                    "Traza guardada en %s (%d ticks, escala de costo x%.2f).",
                    file, r.ticksRecorded(), scale));
        } catch (IOException e) {
            to.sendMessage("No se pudo guardar la traza: " + e.getMessage());
        }
    }
}

package edu.urosario.mineos.trace;

import edu.urosario.mineos.model.TaskSpec;
import edu.urosario.mineos.model.TaskType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Traza de carga: para cada tick, la lista de tareas que el servidor tuvo que atender.
 * Puede venir del generador sintético o de un servidor real (la graba el plugin de Paper).
 * Formato CSV: {@code tick,key,type,cx,cz,costUs}.
 */
public record Trace(List<List<TaskSpec>> ticks) {

    public static final String HEADER = "tick,key,type,cx,cz,costUs";

    public int tickCount() {
        return ticks.size();
    }

    public List<TaskSpec> tick(int k) {
        return ticks.get(k);
    }

    public long taskCount() {
        long n = 0;
        for (List<TaskSpec> t : ticks) {
            n += t.size();
        }
        return n;
    }

    public void writeCsv(Path file) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            w.write(HEADER);
            w.newLine();
            for (List<TaskSpec> tick : ticks) {
                for (TaskSpec s : tick) {
                    w.write(s.tick() + "," + s.key() + "," + s.type().name() + ","
                            + s.cx() + "," + s.cz() + "," + s.costUs());
                    w.newLine();
                }
            }
        }
    }

    public static Trace readCsv(Path file) throws IOException {
        List<List<TaskSpec>> ticks = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file)) {
            String line = r.readLine(); // encabezado
            if (line == null || !line.startsWith("tick")) {
                throw new IOException("Falta el encabezado '" + HEADER + "' en " + file);
            }
            int lineNo = 1;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",");
                if (p.length != 6) {
                    throw new IOException("Línea " + lineNo + ": se esperaban 6 columnas y hay " + p.length);
                }
                int tick = Integer.parseInt(p[0].trim());
                if (tick < 0) {
                    throw new IOException("Línea " + lineNo + ": tick negativo");
                }
                while (ticks.size() <= tick) {
                    ticks.add(new ArrayList<>());
                }
                ticks.get(tick).add(new TaskSpec(
                        tick,
                        Integer.parseInt(p[1].trim()),
                        TaskType.valueOf(p[2].trim()),
                        Integer.parseInt(p[3].trim()),
                        Integer.parseInt(p[4].trim()),
                        Math.max(1, Integer.parseInt(p[5].trim()))));
            }
        }
        return new Trace(ticks);
    }
}

# MineOS — Simulador de scheduling y memoria para servidores de Minecraft

Proyecto final de **Sistemas Operativos** (Universidad del Rosario). Un servidor de Minecraft (Java) se comporta como un mini sistema operativo: un reloj de 50 ms (el *tick*), miles de "procesos" (jugadores, mobs, redstone), una "RAM" de chunks y un disco. MineOS aplica los conceptos del curso —**scheduling, memoria virtual y E/S**— para entender por qué un servidor se atrasa y qué decisiones de diseño lo arreglan.

El proyecto tiene dos mitades que se conectan con un archivo CSV:

```
 Servidor Paper real ──(plugin: /mineos record)──▶ traza.csv ──▶ Simulador MineOS ──▶ comparación de políticas
        ▲                                                                                    │
        └────────────(plugin: /mineos policy tiered, medir con /mineos status)◀──────────────┘
```

| Módulo | Qué es | Estado |
|---|---|---|
| `sim-core` | Simulador (Java 21, **sin dependencias**) + pruebas | ✅ compilado y probado |
| `paper-plugin` | Plugin de Paper: graba trazas reales y aplica una política por niveles | ⚠️ **escrito pero NO compilado ni probado** |

## 1. Modelo

**Tick loop.** El tick *k* arranca en `max(fin del tick anterior, k·50 ms)`. Si el trabajo excede 50 ms el tick se alarga (MSPT > 50) y el TPS cae, como en un servidor real.

**Tareas** (`TaskType`), con su prioridad estática: `PLAYER`(0) · `REDSTONE`(1) · `ENTITY`(2) · `CHUNK_TICK`(3). Cada tarea tiene un costo de CPU y necesita un chunk en RAM.

**Memoria.** Cada chunk es una *página*; la RAM son *marcos*. Acceder a un chunk ausente es un **page fault**: se elige una víctima (FIFO / LRU / Clock) y se lee del disco (1.5 ms, 1–2 hilos de E/S). Con E/S **asíncrona** la tarea se bloquea y la CPU atiende a otra; con E/S **síncrona** (vanilla) el hilo principal espera.

**Políticas comparadas**

| Eje | Opciones |
|---|---|
| Planificador | `FCFS` (vanilla) · `PRIORITY` (4 niveles + Round Robin + *aging*) · `MFQ` (3 niveles con degradación y *boost*) |
| Reemplazo de chunks | `FIFO` · `LRU` · `CLOCK` |
| Arquitectura | `vanilla` (E/S síncrona) · `async` · `budget` (corta el tick a 50 ms) · `prefetch-naive` · `prefetch-throttled` |

**Presupuesto de tick.** Con `budget`, el tick se corta a 50 ms y el trabajo pendiente pasa al siguiente tick. Si una entidad genera una tarea nueva mientras la anterior sigue pendiente, la nueva se **descarta** (esa entidad se actualiza menos veces). Así el TPS se mantiene en 20 a costa de la *frescura* de lo menos importante.

**Métricas:** MSPT medio/p95/p99, TPS, % de ticks > 50 ms, utilización de CPU y disco, page faults, hit ratio, latencia (creación → fin) por tipo de tarea, % de actualizaciones descartadas.

## 2. Cómo correrlo

Sin Maven (solo JDK 21):

```bash
javac -d out/main $(find sim-core/src/main -name '*.java')
java -cp out/main edu.urosario.mineos.cli.Experiments                        # matriz completa -> results/results.csv
java -cp out/main edu.urosario.mineos.cli.Experiments --scenario heavy       # un escenario
java -cp out/main edu.urosario.mineos.cli.Experiments --scenario medium --sweep-frames   # curva de thrashing
java -cp out/main edu.urosario.mineos.cli.Experiments --trace mi-traza.csv --frames 2000 # trazas reales
```

Con Maven: `mvn test` y `mvn package` (los tests usan JUnit 4.13.2).

Escenarios: `light` · `medium` (≈95 % de CPU) · `heavy` (sobrecargado) · `explorers` (jugadores con elytra, estresa la memoria). La misma semilla produce siempre la misma traza.

## 3. Hallazgos (ver `docs/` y `results/results.csv`)

Una semilla por escenario, 1100 ticks medidos tras 100 de calentamiento. Las diferencias grandes son sólidas; las pequeñas (p. ej. LRU vs Clock, ~1 %) están dentro del ruido y no deben interpretarse.

1. **Priority protege al jugador; FCFS no.** En `medium`, p99 de latencia del jugador: **5.1 ms** (Priority) vs 48 ms (FCFS). En `heavy`, 7.3 ms vs 110–123 ms. El costo lo pagan las tareas de baja prioridad: `CHUNK_TICK` llega a p99 = 271 ms y pierde 47 % de sus actualizaciones.
2. **MFQ no prioriza lo importante, solo lo corto.** Casi todo el trabajo del tick son tareas pequeñas, así que los jugadores esperan en la misma cola que todo lo demás (p50 de 19 ms en `medium` vs 2.6 ms con Priority). Y **deja morir de hambre a lo pesado**: con presupuesto en `heavy`, la redstone llega a p99 ≈ **7 s**, a pesar del *boost*.
3. **El presupuesto convierte el colapso en degradación controlada.** En `heavy` sin presupuesto el servidor cae a **9 TPS**; con presupuesto se mantiene en **20 TPS**, pero descarta 56–66 % de las actualizaciones de entidades.
4. **E/S síncrona es lo más caro.** `medium` (1500 marcos): 70 ms/tick y 14.3 TPS en vanilla vs 46.8 ms y 20 TPS con E/S asíncrona.
5. **LRU ≈ Clock ≪ FIFO.** FIFO provoca entre 42 % y 100 % más page faults según el escenario (`light` +42 %, `medium` +62 %: 27 501 vs 16 977; `explorers` +69 %: 10 986 vs 6 493; `heavy` +100 %). Clock logra el resultado de LRU con un solo bit por marco.
6. **Thrashing: un precipicio, no una pendiente.** En `medium` (LRU) con el 100 % de la RAM: 20 TPS y disco al 23 %; con el 80 %: 19.9 TPS pero disco al 93 %; con 60 %: 11.7 TPS; con 40 %: 4.7 TPS.
7. **El prefetch ingenuo empeora el servidor.** Satura el disco y las lecturas que sí se necesitan quedan en cola (p99 del tick en `explorers`: 33 ms vs 18 ms sin prefetch; 5 077 útiles / 4 704 desperdiciados). Con *throttling* (solo precarga si el disco está libre) los faults bajan 36 % (6 493 → 4 181) **sin** penalizar el tick (18.4 ms) y casi todo lo precargado se usa (2 325 / 65).
8. **Un buen planificador de CPU puede hacerle daño al disco.** Con presión de memoria, Priority deja para el final las tareas que más fallan, así que sus lecturas se piden cuando la CPU ya no tiene nada más que hacer: mismos page faults, pero 31.5 ms/tick vs 19.4 ms (MFQ) en `light`. Es el motivo por el que los sistemas reales lanzan primero a los procesos limitados por E/S.

## 4. Limitaciones (importantes para el informe)

- **Los costos de CPU son sintéticos** (distribuciones fijas), no medidos. Las conclusiones *cualitativas* (quién gana, por qué, dónde está el precipicio) son las defendibles; las cifras absolutas no son las de un servidor concreto. Una traza real reemplaza los costos.
- Una sola semilla por escenario: no hay intervalos de confianza.
- Un solo hilo de CPU; no se simula paralelismo por regiones (Folia).
- Entidades y redstone son estáticas; solo se mueven los jugadores. No se modelan escrituras de chunks sucios en el desalojo.
- La E/S tiene latencia constante (sin variación por SSD/HDD ni caché del sistema operativo).
- El tick de un servidor real también incluye red, plugins y GC, que aquí no existen.

## 5. Verificación realizada

- `sim-core` compila con `javac` (JDK 21) sin advertencias.
- **31 pruebas JUnit pasan.** Incluyen cadenas de libro de texto con resultado conocido (Silberschatz: FIFO 15 / LRU 12 / Clock 14 faltas; anomalía de Belady: FIFO 9→10 con más marcos, LRU 10→8), casos de tiempo calculados a mano (E/S síncrona vs asíncrona), corte por presupuesto, descarte de actualizaciones y reproducibilidad.
- Exportar una traza a CSV y reproducirla con `--trace` da resultados idénticos a la corrida directa.
- **No verificado:** `mvn package` completo (el entorno de desarrollo no pudo bajar dependencias; solo se validó con `mvn -o validate`) y **todo el módulo `paper-plugin`**.

## 6. Plugin de Paper (experimental)

Compilarlo (necesita acceso a `repo.papermc.io`): `mvn -Pplugin package` y copiar el `.jar` de `paper-plugin/target` a la carpeta `plugins/` de un servidor Paper 1.21.x.

| Comando | Qué hace |
|---|---|
| `/mineos record [ticks]` | Graba jugadores y mobs cercanos a un jugador en `plugins/MineOS/trace-*.csv` |
| `/mineos status` | MSPT medio/p95/p99 y % de ticks > 50 ms (últimos 20 s) |
| `/mineos policy tiered` / `off` | Activa/desactiva la política por niveles (IA de mobs a 1, 1/2 o 1/4 de frecuencia según la distancia al jugador) |

Procedimiento de validación: con el mismo servidor y carga, correr `policy off` → esperar 20 s → `status`; luego `policy tiered` → esperar 20 s → `status`. Comparar el MSPT.

Límites que conviene declarar: Bukkit no expone el costo de CPU por entidad, así que el costo grabado es un modelo a priori **escalado** para que el tick medio modelado iguale al medido (calibra el total, no el reparto); no se graban `REDSTONE` ni `CHUNK_TICK`; y la política usa `Mob#setAware`, cuya disponibilidad y efecto real hay que confirmar en la versión de Paper que uses.

## 7. Ideas para ampliar

- **Lanzar la E/S temprano** (al inicio del tick, para todas las tareas pendientes) y comprobar que elimina la penalización de Priority del hallazgo 8.
- **Política híbrida:** Priority para jugador/redstone + MFQ para el resto.
- Cota inferior con reemplazo **óptimo (Belady)** sobre la cadena de referencias.
- Varias semillas por escenario con intervalos de confianza.
- Modelar paralelismo por regiones (tipo Folia) con sincronización en los bordes.

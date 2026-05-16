# Changelog

Todos los cambios reseñables de cada versión publicada de MusVisto.

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
[Semantic Versioning](https://semver.org/lang/es/).

## [Sin publicar]

(añadir aquí los cambios de la próxima versión)

## [1.0.0-beta7] — 2026-05-15

### Cambiado
- IA: nueva apertura de apuestas con farol/valor controlado por contexto (mano, posición, rivales que ya han pasado). La IA deja de apostar solo con manos casi perfectas; los lances se disputan de verdad. (#1)

### Corregido
- IA: el rival ya **no intercepta el 100% de las señas** — solo algunas, de vez en cuando. Antes, usar señas era perjudicial porque la IA siempre las cazaba. (#7)
- IA: al detectar una seña del rival, ahora también ajusta su juego de Grande/Chica (no solo Pares/Juego), y solo si esa seña supera su propia mano. (#9)

## [1.0.0-beta6] — 2026-05-15

### Cambiado
- IA: el importe del envite escala con la fuerza de la mano (antes 2-4 aleatorio plano); manos fuertes apuestan más, faroles baratos.
- IA: valor de Juego recalibrado — el 32 ya no se sobrevalora; los juegos no-31 pierden valor según la posición (postre pierde desempates).
- IA: coordinación de pareja — si el compañero señaliza fuerza y tiene mejor posición, la IA con mano floja juega de apoyo en vez de pisarle el envite.
- IA: con el compañero de mano y mano normalita, pide Mus en vez de cortársela.
- UI: anuncios de acción rediseñados (aparición/desaparición clara, duración mínima legible, animaciones explícitas).

### Corregido
- IA: un 31 en postre ya no acepta envites a Juego de forma 100% determinista (era farmeable por un rival con 31 más cerca de mano).
- UI: los anuncios de acción ya no desplazan las cartas ni otros elementos por largos que sean.
- UI: el avatar del jugador inferior ya no queda pegado al borde izquierdo.
- Debug: el panel de AI logs ya no queda tapado por la barra de navegación; log más legible (cartas sin palo, resumen al inicio).

### Añadido
- Tests JUnit de regresión para capitanía de pareja, decisión de Mus y 31 en postre.

## [1.0.0-beta5] — 2026-05-14

### Añadido
- IA: órdago proactivo en tres escenarios (desesperación, bloquear victoria rival, cerrar partida).
- IA: ahora tiene en cuenta las señas del compañero para Grande y Chica, no solo para Pares y Juego.
- Infraestructura: panel "AI Debug Log" + botón de toggle en menú de pausa, reorganizado a `src/debug/` para que el APK release quede limpio.
- Tests JUnit de regresión para descarte, órdago proactivo y boosts por seña del compañero.

### Cambiado
- IA: envido de respuesta cuando hay ventaja alta ahora es 2-4 aleatorio (antes siempre 2), menos predecible.
- IA: 31 del compañero penalizado a 85 (en vez de 100) si el compañero no es mano — un 31 fuera de mano puede perder ante otro 31.
- Build: variante `release` usa `signingConfig` de debug como fallback para permitir `./gradlew installRelease` local. No afecta a la firma del AAB de Play Store, que sigue dependiendo del wizard de Android Studio.

## [1.0.0-beta4] — 2026-05-14

### Añadido
- IA: nueva heurística de descarte por scoring de carta. Suelta más cartas mediocres, tira pares bajos (4-7) para buscar Reyes y prioriza jugadas reales sobre conservar Juego inferior.

### Corregido
- Menú de pausa: al abrirlo la IA deja de jugar de verdad (antes seguía moviendo turnos en segundo plano).
- Menú de pausa: ya no se abre/cierra solo tras cada acción de la IA cuando se cierra durante un turno.
- `evaluateHand` ya no devuelve placeholders a cero en Grande/Chica/Pares al evaluar Juego.
- En Chica los Doses cuentan como Ases (no como Dos cualquiera).
- En descarte las equivalencias Rey↔Tres y As↔Dos se respetan al contar pares.
- La seña "Tres Ases" se muestra correctamente cuando toca.
- Regla del 31 al descartar: preserva As/Dos para Chica en vez de tirar la carta de menor valor numérico.

---

Las versiones anteriores a beta4 (beta1, beta2, beta3) no están documentadas
formalmente; el detalle vive en `git log`.

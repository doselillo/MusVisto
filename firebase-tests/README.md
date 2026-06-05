# Tests de reglas RTDB (emulador)

Valida `../database.rules.json` contra el **emulador de Realtime Database** con
`@firebase/rules-unit-testing` — sin tocar tu base de datos real ni teclear JSON a
mano en el simulador de la consola.

## Requisitos

- **Node** (ya lo tienes: v24).
- **JDK 11+** en el PATH. Tu `java` global es Java 8, pero Android Studio trae un
  JDK 21 en `JAVA_HOME` (`D:\Android\Android Studio\jbr`). Antepónlo al PATH en la
  sesión de PowerShell antes de lanzar los tests (ver abajo).

## Cómo ejecutar (PowerShell, desde esta carpeta `firebase-tests/`)

```powershell
npm install                                  # solo la primera vez
$env:Path = "$env:JAVA_HOME\bin;$env:Path"   # usa el JDK 21 del JBR (no el Java 8)
npm test
```

`npm test` arranca el emulador de Database (con las reglas de
`../database.rules.json`), corre `rules.test.mjs` y apaga el emulador. Sale con
código 0 si todo pasa.

La primera vez, el emulador **descarga su .jar** (necesita red). La UI del emulador,
si quieres curiosear los datos, queda en `http://localhost:4000` mientras corre.

## Qué comprueba

`rules.test.mjs` siembra una sala de ejemplo (admin) y luego, como distintos
usuarios autenticados, asierta PERMITIDO/DENEGADO:

- ✅ el dueño togglea su `ready`/`connected`, manda su `actions`, lee la sala; el
  host publica `views` y cambia `status`; un nuevo jugador reclama un asiento vacío;
  crear sala siendo host.
- ⛔ jugar/escribir en el asiento de otro, publicar vistas sin ser host, cambiar el
  status sin ser host, suplantar/expulsar (tocar un `uid` ajeno), secuestrar un
  código, `connected` no-booleano, crear sala poniendo a otro de host, leer sin auth.

## Probar la APP entera contra el emulador (opcional)

Para jugar de verdad contra el emulador (create/join/partida reales), añade un
`useEmulator(...)` temporal en debug a `FirebaseDatabase`/`FirebaseAuth` y arranca
`firebase emulators:start --only database,auth` desde la raíz. Pídelo y te indico el
cambio exacto.

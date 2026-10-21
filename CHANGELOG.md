# Changelog

All notable changes to this project are documented in this file.
Format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Refactor — catalogo y decision de recarga del tagger

- **`TaggerStore` + `TaggerSession` + `TaggerSelection`**
  (`app/src/main/java/com/yumenode/ai/draw/TaggerStore.kt`, 145 lineas)
  - El catalogo del tagger (modelo ONNX + tabla de tags en `files/tagger/`) sale
    de `DrawPage` a un store sin dependencias de UI; `DrawPage` baja de 3,468 a
    3,469 lineas (el store añade la logica de recarga explicitada).
  - `DrawPage` conserva su API publica (`listTaggerModels()`,
    `listTaggerCsvs()`, `taggerModelFile()`, `taggerCsvFile()`,
    `currentTaggerModelName()`, `currentTaggerCsvName()`, `selectTaggerModel()`,
    `selectTaggerCsv()`) delegando en el store, asi que los call sites de
    `TaggerUi` no cambian.
  - Las claves `"selTaggerModel"` / `"selTaggerCsv"` quedan definidas **una
    sola vez** (`TaggerStore.KEY_SEL_MODEL` / `KEY_SEL_CSV`); ya no hay
    literales sueltos en `DrawPage`.
  - La decision de "el tagger cargado ya sirve" se modela como
    `TaggerSelection.isReadyWith(TaggerSession)` y la usan **los dos** call
    sites que antesTenian la comparacion duplicada: `ensureTaggerLoaded()` y
    `taggerReadyError()`.

### Refactor — catalogo de LoRA a un store

- **`LoraStore`** (`app/src/main/java/com/yumenode/ai/draw/LoraStore.kt`, 92 lineas)
  - El catalogo de LoRA (listado, seleccion activa, borrado) sale de `DrawPage`
    a un store sin dependencias de UI; `DrawPage` baja de 3,472 a 3,468 lineas.
  - `DrawPage` conserva su API publica (`listLoras()`, `hasLora()`,
    `activeLora()`, `selectLora()`, `deleteLora()`) delegando en el store, asi
    que los call sites de `MainActivity` no cambian.
  - La clave de preferencia `"selLora"` queda definida **una sola vez**
    (`LoraStore.KEY_SEL_LORA`); ya no hay literales sueltos en `DrawPage`.
  - La regla de prioridad de la seleccion (sesion > guardada > primera) queda
    documentada y fijada por tests, incluida la caida cuando el fichero
    seleccionado ya no esta en disco.

### Refactor — logica de parametros de dibujo a un objeto puro

- **`DrawParamsLogic`** (`app/src/main/java/com/yumenode/ai/draw/DrawParamsLogic.kt`, 136 lineas)
  - Los clamps de los parametros estaban **repetidos en tres sitios** dentro
    de `DrawPage` (generacion, upscale e img2img) con los mismos numeros
    escritos a mano: `coerceIn(1, 150)` para los pasos, `coerceIn(1f, 30f)`
    para el CFG, `coerceIn(0f, 2f)` para el peso de la LoRA, el alineado a
    multiplos de 64 con `coerceIn(64, 2048)`, la fuerza `coerceIn(0.05f, 0.99f)`,
    el tiling de VAE y los parametros del tagger.
  - Ahora cada regla vive **una sola vez** en `DrawParamsLogic` y `DrawPage`
    la usa en los 14 puntos donde estaba duplicada (29 llamadas).
  - Se extrajo tambien la matematica del hires fix, que no tenia ninguna
    cobertura: `hiresDenoise()` mapea la fuerza del slider (0.05–0.99) al
    rango que el hires nativo de sd.cpp usa bien (0.15–0.55), con los limites
    durs 0.10–0.60.
  - Sin cambio de comportamiento: los tests fijan los valores actuales.

### Added

- **18 tests JVM para el tagger** (`TaggerStoreTest.kt`): 8 del catalogo
  (solo se listan `.onnx` / `.csv`, ordenados por nombre; la seleccion
  guardada por **nombre** y su caida al primero cuando el fichero ya no
  existe) y 10 de la decision de recarga (nada cargado, modelo distinto,
  tabla distinta, cambio de backend GPU <-> CPU, y el caso en que el engine no
  tiene sesion aunque los nombres coincidan).

- **13 tests JVM para el catalogo de LoRA** (`LoraStoreTest.kt`, 161 lineas):
  solo se listan los `.safetensors` (extension sin distinguir mayusculas) y
  ordenados por nombre; la prioridad de la seleccion (la de la sesion gana
  sobre la guardada, y la guardada sobre la primera); la caida a la siguiente
  opcion cuando el fichero elegido ya no existe; la seleccion guardada por
  **nombre** (no por ruta, para sobrevivir a una restauracion de copia de
  seguridad); y el borrado.

- **21 tests JVM para la logica de parametros** (`DrawParamsLogicTest.kt`, 183 lineas):
  alineado a multiplos de 64 y acotado a 64..2048, defaults de cada parametro,
  clamps de pasos/CFG/LoRA/fuerza, la fuerza del upscale mapeada desde el
  progreso de la barra, los tamanos del upscale (2x alineado y acotado) y de la
  primera ronda del hires (tamaño original), la monotonia y los limites de
  `hiresDenoise`, y el tiling de VAE y los parametros del tagger.

### Refactor — extracción del store de model sets de dibujo

- **`ModelSetStore` + `ModelSet` a nivel de archivo** (`app/src/main/java/com/yumenode/ai/draw/ModelSetStore.kt`, 249 líneas)
  - La persistencia de los model sets (JSON `ponko-set.json` por set, ficheros
    sueltos legacy, selección, borrado, TAESD global) sale de `DrawPage` y pasa
    a un store sin dependencias de UI: `DrawPage` baja de 3,564 a 3,474 líneas.
  - `ModelSet` deja de ser una clase anidada de `DrawPage` y pasa a clase de
    nivel de archivo; `MainActivity` importa `com.yumenode.ai.draw.ModelSet`
    (antes `DrawPage.ModelSet`).
  - `DrawPage` mantiene su API pública (`listSets()`, `selectSet()`,
    `deleteSet()`, `clearSlot()`, `taesdFile()`, `activeTaesdPath()`,
    `assignTaesdToActiveSet()`) delegando en el store: los call sites de
    `MainActivity` y `TaggerUi` no cambian.
  - Fuente única de verdad: `KIND_SINGLE`/`KIND_MULTI`, `KEY_SEL_SET`,
    `KEY_TAESD_PATH` y `TAESD_MIN_BYTES` viven ahora en `ModelSetStore`;
    `DrawPage` los referencia (sus constantes privadas `KEY_SEL_SET` y
    `KEY_TAESD_PATH`, ya sin uso, se borraron).

### Added

- **21 tests JVM para el store** (`ModelSetStoreTest.kt`, 272 líneas;
  `FakePrefs.kt`, 89 líneas) — la lógica de disco y JSON de los model sets
  no tenía ninguna cobertura antes:
  - `safeId`: caracteres seguros, sustitución, recorte de `_`, fallback `"set"`,
    tope de 48 caracteres.
  - `listSets`: `.gguf` suelto → set legacy, el VAE suelto se cuelga del set
    en lugar de listarse aparte, los ficheros ocultos se ignoran.
  - Round-trip del JSON: nombre/kind/family, ficheros **dentro** del set por
    nombre y ficheros **fuera** por ruta absoluta, slots cuyo fichero ya no
    existe se descartan al leer, JSON corrupto se ignora.
  - Selección, borrado (con y sin directorio), `clearSlot` (quita el slot pero
    **no** borra el fichero) y TAESD (ruta, fichero ausente, fichero truncado).
- **`org.json` real en el classpath de test** (`app/build.gradle.kts`):
  `testImplementation("org.json:json:20240303")`. El `org.json` de `android.jar`
  es un stub que lanza `Method put in org.json.JSONObject not mocked`, así que
  sin esto la persistencia en JSON no se puede probar. **Solo afecta al
  classpath de test**: `debugRuntimeClasspath` no lo incluye y el APK no lo
  lleva.

### Verified

- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest` → **97/97** tests pasan
  (`ChatSendLogicTest` 15, `DistilledPresetsTest` 6, `ModelSetStoreTest` 21,
  `DrawParamsLogicTest` 21, `LoraStoreTest` 13, `TaggerStoreTest` 8,
  `TaggerSessionTest` 10, `ThreadRecommendationTest` 3; 0 fallos, 0 errores,
  0 omitidos)
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL, `app-debug.apk` (73,201,435 bytes)
- `debugRuntimeClasspath` no contiene `org.json` (el APK no cambia de tamaño
  ni de comportamiento respecto a antes del refactor)
- Verificación en dispositivo: **pendiente** — no había ningún dispositivo
  accesible por ADB (`adb devices` vacío; barrido de `192.168.1.0/24:5555` sin
  resultados), así que el gate de UI en hardware queda sin ejecutar.

### Refactor — page extraction (incremental monolith reduction)

- **ModelsPage extracted** (`fcd86be`)
  - Moved the entire `buildModelsPage()` body out of `MainActivity` into a new
    `ModelsPage.kt` (584 lines) owned by a narrow `ModelsHost` contract
    (`ModelsHost.kt`, 145 lines). `MainActivity` now only hosts the page.
  - `MainActivity` lost ~570 lines (3,891 remaining, was 4,427 pre-extraction).
  - The page is the single owner of its 43 views; `MainActivity` exposes them
    via read-only getter delegations — no double initialization.
  - Removed the orphan closing brace left by the off-by-one function deletion
    that had prematurely closed `MainActivity`.
  - Replaced the recursive ambiguous host callbacks
    `onDrawToggleClick()` / `onTaggerToggleClick()` with explicit
    `onDrawToggleClickRequested()` / `onTaggerToggleClickRequested()`, wired
    through `this@MainActivity`.
  - Fixed `ColorStateList` import (`android.content.res`, not `android.graphics`).
  - Fixed `themeMode` (`val`, not `var`) to satisfy the `SettingsHost` contract.

### Added
- **`ChatSendLogic` + characterization tests** (`ChatSendLogic.kt`, `ChatSendLogicTest.kt`)
  - Factored the pure, stateless logic out of `doSend()`: `composeSendText`
    (text/file merge), `computeSeedTitle` (18-char title fallback),
    `countImages` (`userImage` "|" split) and `buildTagHint` (tagger notice).
  - **15 JVM characterization tests** pin the current `doSend()` behavior
    *before* the refactor, so any drift fails the gate.
  - `doSend()` and `tagHint()` now delegate to `ChatSendLogic`; behavior
    preserved. `runRound()` is intentionally untouched (shared-state
    orchestration requires characterization coverage first).

### Changed
- **About-section portrait updated** — `res/drawable-nodpi/about_portrait.png`
  replaced with the new artwork at its **full 1408×768** aspect (no crop); the
  ImageView uses `matchWrap()` so the whole image is shown. No code change:
  `SettingsPage.kt` still uses `R.drawable.about_portrait`; renders landscape
  (950×543 px on device).

### Fixed
- **Startup crash** (`savedContainer` `lateinit` unassigned): the delegated
  getters resolved it; app now launches without
  `UninitializedPropertyAccessException` (verified on-device, pid 21195,
  crash buffer clean).
- **ETA formatter** (`Formatters.kt`): corrected the invalid `%h` format specifier
  (which prints the argument's `hashCode`) to `%d:%02d`, so hours ≥ 1 render as
  `1:16` instead of garbage. (Shared by UI and downloader via `Formatters.eta`.)

### Verified (device: Motorola Edge 50 Fusion, ADB over Wi-Fi)
- `compileDebugKotlin` → BUILD SUCCESSFUL
- `testDebugUnitTest` → 15/15 `ChatSendLogicTest` pass (no other JVM tests in tree)
- `assembleDebug` → app-debug.apk (73 MB)
- App starts; Models page renders: sub-tabs `Backend`/`CPU`/`Chat model`/`Draw model`/`Tagger`,
  `loadButton`, `savedContainer`, and navigation tabs all present.
- `doSend()` post-refactor: send tap does not crash (pid 23727 alive, crash buffer 0).

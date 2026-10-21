# Changelog

All notable changes to this project are documented in this file.
Format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Verificacion de checksum de las descargas: la deteccion de placeholders
  era fragil** (`DownloadIntegrity.kt`).
  `ModelDownloader.verifyChecksum()` identificaba los checksums de ejemplo
  del catalogo (`"a1b2c3d4e5f6..."`) comparando solo los **4 primeros
  hexagrafos**. Un SHA-256 real que empezara por `a1b2` (o por cualquiera de
  los 11 prefijos) se aceptaba **sin verificar nada**: la comprobacion se
  saltaba entera y el fichero se daba por bueno.
  Ahora un placeholder se reconoce por su **forma completa** (4 grupos de 4
  hex separados por puntos y acabando en `...`), no por un prefijo. Un hash
  real no puede confundirse con un placeholder, y la verificacion se
  mantiene activa en cuanto haya checksums de verdad.
  El hash SHA-256 y el calculo de "descarga incompleta" salen tambien a un
  objeto puro (`DownloadIntegrity`), con la logica antes mezclada en el
  descargador.

### Added

- **19 tests JVM de la integridad de descargas** (`DownloadIntegrityTest.kt`):
  los 9 placeholders del catalogo se detectan; un SHA-256 real **no** se
  confunde con un placeholder (este es el caso del bug corregido); un
  placeholder truncado si se intenta verificar; el hash de un contenido
  conocido (`"abc"`) es el correcto; la comparacion ignora mayusculas; un
  checksum equivocado se rechaza; y el calculo de descarga incompleta
  respeta el ratio del 90 % y trata como completo lo que no esta en el
  catalogo.

### Refactor — unificar los LayoutParams de la UI

- **`UiExt.matchWrap()` / `UiExt.wrapWrap()`** como fuente unica.
  `ApiBlockBuilder`, `ChatImageHandler` y `ModelsPage` tenian cada uno su
  copia local de los `LayoutParams` (con firmas distintas: sin parametros,
  con `topMargin`, con `leftMargin`). Ahora las tres delegan en las
  extensiones de `Context` de `UiExt.kt`, que ya eran la fuente comun.
  `UiExt.wrapWrap()` acepta `leftMargin` para cubrir la variante de
  `ModelsPage` sin cambiar sus 13 call sites.
- `UiHelpers.hintTextView()` pasa a ser privado: solo lo usa `numField()`.

### Refactor — una sola aritmetica de dp a px

- **`UiScale`** (`app/src/main/java/com/yumenode/ai/UiScale.kt`)
  - La conversion dp → px estaba **copiada en 7 sitios**: `UiExt.dp()`,
    `UiHelpers.dp()`, `ApiBlockBuilder.dp()`, `GalleryPage.dp()`,
    `ModelsPage.dp()`, `SegmentedSwitch.dpV()` y `ChatImageHandler.dp()`.
    Todas pasan ahora por `UiScale`.
  - Se conservaron **las dos convenciones** que coexistian, explicitas:
    truncado (la mayoria) y redondeo (`ChatImageHandler`). La diferencia es
    real: a 400dpi (densidad 2.5) un 3dp son 7.5px — truncar da 7 y redondear
    da 8. La variante de redondeo tambien habia reaparecido como copia en
    `ChatImageHandler`, sin relacion visible con la fuente comun.
  - La aritmetica vive fuera de Android para que se pueda comprobar en JVM:
    `Context.dp()` depende de `resources`, que no existe en un test plano.

### Added

- **12 tests JVM de la escala** (`UiScaleTest.kt`): truncado y redondeo (que
  difieren en 1px como maximo, y solo en valores fraccionarios), el variante
  en coma flotante para tamanos de texto y grosores de linea, y el alineado a
  multiplo de paso que exigen los multiples de 64 de sd.cpp.

### Refactor — presupuesto de contexto a un objeto puro

- **`ContextBudget`** (`app/src/main/java/com/yumenode/ai/ContextBudget.kt`, 81 lineas)
  - `fitTokens` estaba **duplicado byte a byte** en `ChatManager` y en
    `AttachmentHandler`: los dos recortaban el texto por el mismo algoritmo
    (corte por presupuesto de tokens, y si el corte cae en la segunda mitad
    se recorta al ultimo salto de linea anterior para no partir una frase).
    Ahora vive una sola vez, en forma pura, con las dos copias reducidas a
    delegaciones de una linea.
  - El calculo del presupuesto de la conversacion (`estimateConversationTokens`)
    sale de `ChatManager` al mismo objeto; el wrapper de cuatro lineas que
    `MainActivity` tenia encima tambien desaparece.
  - Codigo muerto barrido: `AttachmentHandler.estTokens()` dejo de usarse al
    delegar `fitTokens`, y el wrapper privado de `MainActivity` quedo sin
    llamadores al apuntar directamente a `ContextBudget`.

### Added

- **12 tests JVM del presupuesto de contexto** (`ContextBudgetTest.kt`): el
  recorte devuelve el texto intacto si cabe, corta cuando no cabe, corta en un
  salto de linea cuando el corte cae en la segunda mitad y no parte lineas a
  medias, y el coste por turno (texto + sobre de 12 tokens) con y sin el
  ultimo turno, con el prompt de dibujo y con el texto de un fichero adjunto.
  Al fijarlos aparecio un detalle que no era evidente leyendo el codigo: el
  mensaje actual lleva **su propio** sobre de 12 tokens ademas del sobre de
  cada turno, de modo que una conversacion vacia con un mensaje corto ya
  cuesta 13,2 tokens y no 1,2.

### Refactor — unificar los tres handlers de importacion de ficheros de dibujo

- **`DrawFileImporter` + `DrawFileImportHost`**
  (`app/src/main/java/com/yumenode/ai/DrawFileImporter.kt`)
  - `handlePickedLora()`, `handlePickedTaggerOnnx()` y `handlePickedTaggerCsv()`
    eran **el mismo handler copiado tres veces**: identicos salvo por la
    funcion de importacion, el texto de estado, el texto del toast y el
    refresco de la UI. Ahora los tres llaman a un unico `DrawFileImporter`.
  - El importer es **generico en el tipo del fichero** (`DrawFileImporter<T>`)
    y no depende de `android.net.Uri`: asi se puede probar en JVM sin
    Robolectric (`Uri.parse` es un stub de `android.jar` y ni se puede
    construir ni parsear en un test plano).
  - `DrawFileImportHost` expone **solo** lo que el importer necesita (marcar
    la app ocupada, mover la barra de progreso, poner textos). No puede tocar
    el motor, la sesion ni el estado de los modelos — el compilador lo
    garantiza.

### Added

- **5 tests JVM del importador** (`DrawFileImporterTest.kt`): fijan el **orden**
  de los pasos (ocupar -> progreso -> importar -> liberar -> resultado), que
  es lo que un refactor rompe en silencio: si el `setBusy(false)` se ejecuta
  antes de tiempo la app se queda bloqueada, y si el toast de exito se
  dispara en el error el usuario ve un mensaje falso. Tambien cubren que el
  refresco se ejecute siempre, que la URI llegue intacta a la importacion y
  que los callbacks de etapa y progreso se propaguen.
  Requiere `kotlinx-coroutines-test` (`runTest` + `advanceUntilIdle`) en el
  classpath de test.

### Fixed

- **CI: el build ya no muere al pedir el JDK 25** (`gradle/gradle-daemon-jvm.properties`
  eliminado, plugin `foojay-resolver-convention` quitado de `settings.gradle.kts`).
  Ese fichero (generado por `updateDaemonJvm` en `c94d492`) fijaba el daemon de
  Gradle en **JetBrains JDK 25** descargado de `api.foojay.io`. En CI, donde
  no esta cacheado, la descarga fallaba de forma **intermitente** con
  `400 Bad Request` en el HEAD, y el build moria antes de compilar:
  `Unable to download toolchain matching the requirements
  {languageVersion=25, vendor=JetBrains, ...}`.
  Los runs afectados fallaban a los ~1 min (abortando) mientras los que
  pasaban tardaban 4-5 min (descargando el JDK) — el sintoma de un
  rate-limit de foojay contra los runners de GitHub.
  El proyecto compila con `sourceCompatibility`/`targetCompatibility` 17 y el
  CI ya instala el JDK 17 con `setup-java`, asi que el JDK 25 no hacia falta
  para nada. Verificado simulando el CI con JDK 17 (Temurin 17.0.20.1):
  `compileDebugKotlin` y `testDebugUnitTest` salen **BUILD SUCCESSFUL**, el
  daemon arranca con `javaVersion=17` y ya no se pide ningun toolchain.

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

- **Test de equivalencia del tagger** (`TaggerReadyEquivalenceTest.kt`, 125 lineas):
  reimplementa la logica ORIGINAL (la comparacion que estaba dentro de
  `DrawPage` antes del refactor) y la compara con la nueva en una matriz de
  128 combinaciones de estado (cargado/no cargado, modelo y tabla coincidentes
  o no, backend GPU y CPU). Un segundo test comprueba que la forma invertida
  de la funcion habria recarga un tagger que ya estaba listo: es el fallo
  silencioso que casi se cuela al cablear los dos call sites, y que ni el
  compilador ni los tests de la funcion aislada detectan.

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
- `./gradlew :app:testDebugUnitTest` → **147/147** tests pasan
  (`ChatSendLogicTest` 15, `DistilledPresetsTest` 6, `ModelSetStoreTest` 21,
  `DrawParamsLogicTest` 21, `LoraStoreTest` 13, `TaggerStoreTest` 8,
  `TaggerSessionTest` 10, `TaggerReadyEquivalenceTest` 2,
  `DrawFileImporterTest` 5, `ContextBudgetTest` 12, `UiScaleTest` 12,
  `DownloadIntegrityTest` 19, `ThreadRecommendationTest` 3;
  0 fallos, 0 errores, 0 omitidos)
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL, `app-debug.apk` (73301231 bytes)
- `debugRuntimeClasspath` no contiene `org.json` (el APK no cambia de tamaño
  ni de comportamiento respecto a antes del refactor)
- **Verificación en dispositivo** (Motorola Edge 50 Fusion, Android 16,
  ADB por Wi-Fi en `192.168.1.239:5555`):
  - APK debug instalado (`adb install -r`), app arranca (pid 29687) con el
    **buffer de crash vacío** (`adb logcat -d -b crash` sin trazas).
  - `ModelSetStore`: un set multiarchivo creado a mano en
    `files/draw/sets/misets/ponko-set.json` (con un slot **relativo** y otro
    **absoluto**) se listó como `misets_model.gguf [Multi-archivo] 2,92 GB`
    (1,46 + 1,46 GB: los dos slots se resolvieron bien). Al seleccionarlo se
    guardó `selSet=misets` en `shared_prefs/ponko.xml`, y al cargarlo se
    obtuvo **"Modelo de dibujo listo"** con
    `Listo: misets_model.gguf · CPU · 8Hilo · sd.cpp`.
  - Modelo single-file real (`stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf`)
    cargado también: `Listo: … · CPU · 8Hilo · sd.cpp`.
  - `LoraStore`: la LoRA instalada se detecta
    (`Instalado (desactivado): hyper-sd-sd15-1step (256,7 MB)`) y la
    preferencia `selLora=hyper-sd-sd15-1step.safetensors` quedó escrita.
  - `TaggerStore`: la tabla `selected_tags.csv` (248 KB) se detecta; sin
    modelo `.onnx` importado la UI muestra correctamente
    "Modelo de etiquetado no importado".
  - Los slots requeridos ausentes se reportan bien
    ("Faltan archivos requeridos: Modelo de difusión (DiT / UNet)").
- **Importación de una LoRA** (ejercita el `DrawFileImporter` unificado):
  importar `mi_lora_prueba.safetensors` desde Descargas deja el estado
  "LoRA importado", el fichero en `files/draw/lora/` y la LoRA listada en la
  UI ("Instalado (desactivado): mi_lora_prueba"). Sin crash. El fichero de
  prueba se borro despues.
- **Envío de un mensaje de chat** (ejercita el presupuesto de contexto):
  el mensaje sale en el hilo, la app sigue viva y el buffer de crash queda
  vacio.
- **Revisión visual de la unificación de `dp`** (afecta a los margenes de
  toda la UI): se recorrieron las paginas de dibujo, modelos, galeria y
  ajustes en el Edge 50 Fusion (densidad 400dpi). Todo renderiza sin
  solapamientos ni textos cortados; en la captura de la pagina de dibujo los
  presets de tamaño (64/128/256/384/512/Personalizado) quedan alineados y los
  margenes son identicos a antes del refactor. La captura de la sub-pestaña
  API (que usa `ApiBlockBuilder`) sale tambien sin deformaciones: campo de
  puerto, botones «Nueva clave» / «Borrar» alineados y «Iniciar servicio» a
  ancho completo.

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

# AGENTS.md — Yumenode AI

Guía canónica para agentes de código (Claude Code, OpenCode, Codex, Hermes, etc.)
que trabajen en este repositorio. Léela antes de tocar nada.

## Proyecto

App Android nativa (Kotlin) para correr modelos LLM **on-device**:

- **LiteRT-LM** (`.litertlm`) y **llama.cpp / GGUF** (`.gguf`) para chat.
- **sd.cpp** para generación de imágenes (Stable Diffusion / Flux / Qwen, etc.).
- **ONNX Runtime** para el tagger (`.onnx` + `.csv`).
- Servidor API local (OpenAI-compatible) para chat y dibujo.

| Dato | Valor |
|---|---|
| Paquete | `com.yumenode.ai` |
| Nombre visible | **Yumenode AI** |
| `minSdk` / `targetSdk` | 28 / 36 |
| ABI | solo `arm64-v8a` |
| Lenguaje | Kotlin + C++ (JNI) |

## Comandos

```bash
./gradlew assembleDebug            # APK debug
./gradlew test                     # tests unitarios
./gradlew compileDebugKotlin       # iteración rápida (sin nativo)
./gradlew assembleRelease          # APK/AAB release (requiere firma)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Release**: crea y pushea un tag `vX.Y.Z`. `.github/workflows/release.yml`
  deriva `versionName` del tag y calcula
  `versionCode = major*10000 + minor*100 + patch` (ej. `v1.1.0` → `10100`),
  compila, firma y publica APK + AAB en GitHub Releases.
- El build **nativo** (CMake de `sd.cpp`) es el paso lento: para iterar usa
  `compileDebugKotlin`.

## Estructura

```
app/src/main/java/com/yumenode/ai/
  MainActivity.kt        núcleo de UI + orquestación (chat, header, tabs)
  ModelsPage.kt          pestaña Modelos/API (chat / draw / tagger)
  DownloadPage.kt        catálogo de descargas
  SettingsPage.kt        ajustes
  ThemeManager.kt        estado de tema (0=auto 1=claro 2=oscuro)
  YumenodeTheme.kt       paleta + backing de aliases C_*
  ThemeAliases.kt        interfaz de aliases C_* (fuente común)
  Constants.kt           request codes / preference keys
  ChatManager.kt         preparación de mensajes y tokens
  InferenceParamsBuilder.kt  parámetros gguf / litertlm
  VisionManager.kt       mmproj / visión
  draw/                  DrawPage, SdCppEngine (libyumenode_sd.so), TaggerEngine, GgufProbe
app/src/main/cpp/        sdcpp_jni.cpp, CMakeLists.txt (JNI: Java_com_yumenode_ai_draw_SdCppEngine_*)
app/src/main/res/        strings en values/ (default inglés) + values-zh/
```

## Convenciones (importantes)

- **Colores**: nunca hardcodear. Usa `pal` / `YumenodeTheme` y los aliases `C_*`
  vía `ThemeAliases`. El header es `#1A237E` con título/dot `#00F0FF` y subtexto `#8A96B4`.
- **Textos de UI**: van en `res/values/strings.xml`. Evita literales en Kotlin
  (hay deuda: se está migrando a ES/EN sin mezclar).
- **Tema en caliente**: `applyThemeInPlace()` reconstruye las vistas sin recrear la
  Activity (no perder motores nativos cargados).
- **Paquete**: `com.yumenode.ai`; librería nativa `libyumenode_sd.so`; los símbolos
  JNI deben llevar `Java_com_yumenode_ai_...`.
- **No** commitear claves, tokens, contraseñas ni credenciales.
- **Cambios grandes**: hazlos en una rama aparte y mergea solo si compila y se prueba.

## Verificación antes de terminar

1. `./gradlew compileDebugKotlin` (cambio chico) o `./gradlew assembleDebug`.
2. Si aplica a UI/runtime: instalar y revisar
   `adb logcat -d | grep -iE "FATAL|AndroidRuntime|ResourceNotFound"`.
3. No reportes como "hecho" algo que no compiló o no se probó.

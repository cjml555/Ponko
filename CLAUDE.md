# CLAUDE.md

Este proyecto usa **AGENTS.md** como guía canónica para agentes. Léela antes de trabajar:

@AGENTS.md

## Notas específicas de Claude Code

- El proyecto se construye con **Gradle** (`./gradlew`); no hay `make`/`npm`.
- `./gradlew compileDebugKotlin` es la iteración rápida; `assembleDebug` incluye el
  build nativo (CMake de sd.cpp) y tarda mucho más.
- No modifiques `app/src/main/cpp/` ni `CMakeLists.txt` sin necesidad: afecta el
  build nativo y los símbolos JNI.
- Para cambios de UI, verifica en dispositivo con `adb` y revisa `logcat`.

#!/bin/bash
# Verificacion en dispositivo de lo que se ha cambiado en la rama
# feat/chat-vulkan-gpu:
#   1) la app NO se muere al pedir GPU (la via experimental esta off)
#   2) la carga secuencial de pesos (params_backend="disk") baja el pico de RAM
#   3) el cache KV cuantizado (Q8_0) carga bien
#
# Uso: tools/verify-device.sh [ruta-al-apk]
set -u

APK=${1:-/home/jhon/Ponko/app/build/outputs/apk/debug/app-debug.apk}
PKG=com.yumenode.ai
SERIES=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "═══════════════════════════════════════════════════════════════"
echo "DISPOSITIVO: $SERIES"
echo "APK: $APK"
echo "═══════════════════════════════════════════════════════════════"

echo
echo "── 1) instalar ──"
adb install -r "$APK" 2>&1 | tail -1

echo
echo "── 2) arrancar limpio ──"
adb shell am force-stop $PKG
adb logcat -c
adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 8
echo "PID: $(adb shell pidof $PKG)"

echo
echo "── 3) la app no se muere con la opcion GPU (debe seguir viva) ──"
# Modelos/API -> spinner Backend -> GPU
adb shell input tap 270 2305; sleep 4
adb shell input tap 261 442; sleep 3
adb shell input tap 263 560; sleep 3
SPINNER=$(adb shell uiautomator dump /sdcard/v1.xml >/dev/null 2>&1; \
  adb shell cat /sdcard/v1.xml 2>/dev/null | tr '>' '\n' | grep -oE 'text="(CPU|GPU)"' | head -1)
echo "spinner: $SPINNER"

# Seleccionar el modelo GGUF y cargarlo
adb shell input tap 540 1662; sleep 4
adb shell input swipe 540 1500 540 900 400; sleep 3
BTN=$(adb shell uiautomator dump /sdcard/v2.xml >/dev/null 2>&1; \
  adb shell cat /sdcard/v2.xml 2>/dev/null | tr '<' '\n' | grep 'text="Cargar modelo de chat"' \
  | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
echo "boton cargar: $BTN"
Y=$(echo "$BTN" | grep -oE '[0-9]+' | sed -n '4p')
X=$(echo "$BTN" | grep -oE '[0-9]+' | sed -n '3p')
[ -n "$Y" ] && adb shell input tap "$X" "$Y"

echo "cargando (40 s)..."
sleep 40
echo "PID tras la carga: $(adb shell pidof $PKG)"
CRASHES=$(adb logcat -d -b crash 2>/dev/null | grep -c "Fatal signal")
echo "crashes: $CRASHES"
adb shell uiautomator dump /sdcard/v3.xml >/dev/null 2>&1
echo "estado:"
adb shell cat /sdcard/v3.xml 2>/dev/null | tr '>' '\n' | grep -oE 'text="[^"]{2,60}"' | sed 's/text=//' | head -5

echo
echo "── 4) RAM del proceso (pico del motor de dibujo) ──"
PID=$(adb shell pidof $PKG | tr -d '\r')
if [ -n "$PID" ]; then
    adb shell dumpsys meminfo $PKG 2>/dev/null | grep -E "TOTAL PSS|Native Heap|EGL mtrack|GL mtrack" | head -5
fi

echo
echo "── 5) aviso de GPU en la interfaz ──"
adb logcat -d 2>/dev/null | grep -iE "LlamaCpp|ggml" | tail -5 | cut -c1-160

echo
echo "── 6) resumen ──"
echo "crashes totales: $CRASHES"
adb logcat -d -b crash 2>/dev/null | grep -E "Fatal signal" | tail -2 | cut -c1-120
echo "═══════════════════════════════════════════════════════════════"

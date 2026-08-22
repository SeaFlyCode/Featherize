#!/usr/bin/env bash
# Lance l'app Featherize sur un émulateur Android local.
# Usage: ./scripts/run-emulator.sh [nom_avd]
set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]:-$0}"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_SOURCE")/.." && pwd)"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
EMULATOR_BIN="$ANDROID_SDK/emulator/emulator"
ADB_BIN="$ANDROID_SDK/platform-tools/adb"
APP_ID="com.featherize.app"
LAUNCH_ACTIVITY="$APP_ID/.MainActivity"

if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "emulator introuvable: $EMULATOR_BIN (ANDROID_HOME mal configuré ?)" >&2
  exit 1
fi

AVD_NAME="${1:-}"
if [[ -z "$AVD_NAME" ]]; then
  AVD_NAME="$("$EMULATOR_BIN" -list-avds | head -n1)"
fi
if [[ -z "$AVD_NAME" ]]; then
  echo "Aucun AVD trouvé. Crée-en un via Android Studio > Device Manager." >&2
  exit 1
fi

echo "==> AVD sélectionné: $AVD_NAME"

# Démarre l'émulateur seulement s'il n'y a pas déjà un device en ligne
if ! "$ADB_BIN" devices | grep -q "^emulator-"; then
  echo "==> Démarrage de l'émulateur..."
  "$EMULATOR_BIN" -avd "$AVD_NAME" -netdelay none -netspeed full >/dev/null 2>&1 &
  disown

  echo "==> Attente du device..."
  "$ADB_BIN" wait-for-device

  echo "==> Attente que le boot soit terminé..."
  until [[ "$("$ADB_BIN" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done
else
  echo "==> Un émulateur tourne déjà, réutilisation."
fi

echo "==> Build + install debug..."
cd "$ROOT_DIR"
./gradlew installDebug

echo "==> Lancement de l'app..."
"$ADB_BIN" shell am start -n "$LAUNCH_ACTIVITY"

echo "==> Fait."

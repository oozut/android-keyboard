#!/usr/bin/env bash
# Wireless ADB helper for manual on-device validation.
#
# Phone setup (Android 11+):
#   1. Settings -> About phone -> tap "Build number" 7 times.
#   2. Settings -> System -> Developer options -> Wireless debugging -> On.
#   3. Tap "Pair device with pairing code"; keep that dialog open.
#   4. On the Wireless debugging screen note the *connect* IP:port.
#
# Usage:
#   tools/phone-debug.sh discover
#   tools/phone-debug.sh pair 192.168.4.50:41234 123456
#   tools/phone-debug.sh connect 192.168.4.50:37123
#   tools/phone-debug.sh devices
#   tools/phone-debug.sh install [apk]
#   tools/phone-debug.sh log [seconds] [output-file]
#
# Pairing and connect ports are different; both are shown on the phone.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/var/lib/aoe/android-sdk}"
ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
APK="${APK:-$ROOT/build/outputs/apk/unstable/debug/lithuanians-unstable-debug.apk}"
PACKAGE="${PACKAGE:-org.futo.inputmethod.latin.unstable}"

if [[ ! -x "$ADB" ]]; then
    echo "adb not found at $ADB (set ADB=... or ANDROID_HOME=...)" >&2
    exit 1
fi

usage() {
    sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

cmd="${1:-help}"
shift || true

case "$cmd" in
    discover)
        "$ADB" mdns services
        ;;

    pair)
        address="${1:-}"
        code="${2:-}"
        if [[ -z "$address" ]]; then
            echo "usage: $0 pair <ip:pairing-port> [code]" >&2
            exit 2
        fi
        if [[ -n "$code" ]]; then
            printf '%s\n' "$code" | "$ADB" pair "$address"
        else
            "$ADB" pair "$address"
        fi
        ;;

    connect)
        address="${1:-}"
        if [[ -z "$address" ]]; then
            echo "usage: $0 connect <ip:connect-port>" >&2
            exit 2
        fi
        "$ADB" connect "$address"
        "$ADB" devices -l
        ;;

    devices)
        "$ADB" devices -l
        ;;

    install)
        apk="${1:-$APK}"
        if [[ ! -f "$apk" ]]; then
            echo "APK not found: $apk" >&2
            exit 1
        fi
        echo "Installing $apk"
        "$ADB" install -r -d -g "$apk"
        echo "Launching $PACKAGE"
        "$ADB" shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
        ;;

    log)
        seconds="${1:-0}"
        out="${2:-/tmp/phone-log-$(date +%Y%m%d-%H%M%S).txt}"
        "$ADB" logcat -c
        echo "Capturing logcat to $out"
        echo "Reproduce the voice-input issue now; Ctrl-C stops the capture."
        pid="$("$ADB" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
        if [[ -n "$pid" ]]; then
            filter=(--pid="$pid")
        else
            filter=()
        fi
        if [[ "$seconds" != 0 ]]; then
            timeout "$seconds" "$ADB" logcat -v threadtime "${filter[@]}" | tee "$out" || true
        else
            "$ADB" logcat -v threadtime "${filter[@]}" | tee "$out" || true
        fi
        echo "Saved $out"
        ;;

    help|-h|--help)
        usage
        ;;

    *)
        echo "unknown command: $cmd" >&2
        usage >&2
        exit 2
        ;;
esac

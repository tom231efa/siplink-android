#!/usr/bin/env bash
# Runs the NAT lab tests (app/src/androidTest/.../itest/natlab) on an emulator of this host,
# against the lab started by ./up.sh.
#
#   ANDROID_SERIAL=emulator-5554 ./run.sh                        all lab test classes
#   ANDROID_SERIAL=emulator-5554 ./run.sh NatTransportTest       one class
#   ANDROID_SERIAL=emulator-5554 ./run.sh 'NatTransportTest#largeUdpPacketsMoveTheLineToTcp'
#   NATLAB_LONG=1 ...                                            also the tests of about 20 minutes
#   SKIP_INSTALL=1 ...                                           reuse the APKs already installed
#
# ANDROID_SERIAL is required: the lab is reached through this host's routes, which only an
# emulator on it has, and a phone that is connected as well must not be touched.
#
# WARNING: like testenv/run-tests.sh, the tests own the installed debug app: its data is cleared
# before every class.
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "Start the lab first: ./up.sh" >&2; exit 1; }
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator to use, e.g. emulator-5554}"
case "$ANDROID_SERIAL" in
  emulator-*) ;;
  *) echo "The lab tests run on an emulator of this host only, not on $ANDROID_SERIAL" >&2; exit 1 ;;
esac
export ANDROID_SERIAL
adb="${ANDROID_HOME:?ANDROID_HOME is not set}/platform-tools/adb"

set -a
# shellcheck disable=SC1091
. ./.env
set +a

env_blob=$(base64 -w0 <<EOF
labHost=172.31.251.2
ctlPort=7000
stunPort=3478
sipPassword=$SIP_PASSWORD
amiUser=$AMI_USER
amiSecret=$AMI_SECRET
long=${NATLAB_LONG:-0}
EOF
)

if [ -z "${SKIP_INSTALL:-}" ]; then
  abi=$("$adb" shell getprop ro.product.cpu.abi | tr -d '\r')
  (cd ../.. && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Psiplink.abi="$abi" --console=plain -q)
  "$adb" install -r -t ../../app/build/outputs/apk/debug/app-debug.apk
  "$adb" install -r -t ../../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi

run() {
  local output
  "$adb" shell pm clear com.sky.siplink > /dev/null
  output=$("$adb" shell am instrument -w -e natlabEnv "$env_blob" "$@" \
    com.sky.siplink.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r')
  printf '%s\n' "$output" >&2
  grep -q '^OK (' <<<"$output"
}

package=com.sky.siplink.itest.natlab
if [ $# -gt 0 ]; then
  run -e class "$package.$1"
  exit
fi

failed=()
for class in NatDiagnosisTest NatTransportTest; do
  echo "=== $class" >&2
  run -e class "$package.$class" || failed+=("$class")
done
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All lab test classes passed." >&2

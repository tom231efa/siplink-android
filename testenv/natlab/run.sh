#!/usr/bin/env bash
# Runs the NAT lab tests (android/app/src/androidTest/.../itest/natlab) against the lab started
# by ./up.sh: on an emulator of this host, or, with the lab published on an address of this host
# (LAN_ADDRESS=... ./up.sh), on a phone on the local network.
#
#   ANDROID_SERIAL=emulator-5554 ./run.sh                        all lab test classes
#   ANDROID_SERIAL=emulator-5554 ./run.sh NatTransportTest       one class
#   ANDROID_SERIAL=emulator-5554 ./run.sh 'NatTransportTest#largeUdpPacketsMoveTheLineToTcp'
#   NATLAB_LONG=1 ...                                            also the test of about half an hour
#   SKIP_INSTALL=1 ...                                           reuse the APKs already installed
#
# ANDROID_SERIAL is required, so that no other device that is connected is touched. An emulator
# reaches the lab through this host's routes; a phone reaches it on the published address only
# (an emulator would reach that address through Docker's port proxies, which change the ports
# the NAT box sees, so it always uses the lab's own network).
#
# WARNING: like testenv/run-tests.sh, the tests own the installed debug app: its data is cleared
# before every class.
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] || { echo "Start the lab first: ./up.sh" >&2; exit 1; }
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device to use, e.g. emulator-5554}"
export ANDROID_SERIAL
adb="${ANDROID_HOME:?ANDROID_HOME is not set}/platform-tools/adb"

set -a
# shellcheck disable=SC1091
. ./.env
set +a

# The lab's ports as the device reaches them: on the lab's own network, or published (see
# docker-compose.lan.yml).
case "$ANDROID_SERIAL" in
  emulator-*)
    lab_host=172.31.251.2 sip_port=5060 inner_port=5062 ami_port=5038 ctl_port=7000
    ;;
  *)
    published=$(docker compose port --protocol udp natc 5060 2>/dev/null || true)
    if [ -z "$published" ]; then
      echo "A phone reaches the lab only where it is published: LAN_ADDRESS=... ./up.sh" >&2
      exit 1
    fi
    lab_host=${published%:*} sip_port=5080 inner_port=5082 ami_port=5081 ctl_port=5083
    ;;
esac

env_blob=$(base64 -w0 <<EOF
labHost=$lab_host
sipPort=$sip_port
innerPort=$inner_port
amiPort=$ami_port
ctlPort=$ctl_port
stunPort=3478
sipPassword=$SIP_PASSWORD
amiUser=$AMI_USER
amiSecret=$AMI_SECRET
long=${NATLAB_LONG:-0}
EOF
)

if [ -z "${SKIP_INSTALL:-}" ]; then
  abi=$("$adb" shell getprop ro.product.cpu.abi | tr -d '\r')
  ../../android/gradlew -p ../../android :app:assembleDebug :app:assembleDebugAndroidTest -Psiplink.abi="$abi" --console=plain -q
  "$adb" install -r -t ../../android/app/build/outputs/apk/debug/app-debug.apk
  "$adb" install -r -t ../../android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi

# A phone left alone switches its screen off and loses its network for the tests in the
# background: keep it awake, as testenv/run-tests.sh does.
case "$ANDROID_SERIAL" in
  emulator-*) ;;
  *)
    (while "$adb" shell input keyevent KEYCODE_WAKEUP KEYCODE_SHIFT_LEFT > /dev/null 2>&1; do sleep 10; done) &
    keep_awake=$!
    trap 'kill "$keep_awake" 2> /dev/null || true' EXIT
    ;;
esac

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
for class in NatDiagnosisTest NatTransportTest NatMediaTest NatIceTest; do
  echo "=== $class" >&2
  run -e class "$package.$class" || failed+=("$class")
done
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All lab test classes passed." >&2

#!/usr/bin/env bash
# Runs the integration tests of android/app/src/androidTest on the connected emulator or device, against
# the test environment started by ./up.sh.
#
#   ./run-tests.sh                                          all tests (except PbxReferTest, see there,
#                                                           and ConferenceLoadTest, a measurement run by hand)
#   ./run-tests.sh com.sky.siplink.itest.BasicCallTest      one class
#   ./run-tests.sh 'com.sky.siplink.itest.BasicCallTest#outgoingCallSendsDtmf'
#   SKIP_INSTALL=1 ./run-tests.sh ...                       reuse the APKs already installed
#
# WARNING: the tests own the installed debug app. Its data is cleared before every run and the
# tests remove all accounts, settings and imported CAs, so never run them on a device whose
# SipLink debug installation holds a configuration worth keeping.
#
# Requirements: ANDROID_HOME and JAVA_HOME set, and one device visible to adb (with several,
# ANDROID_SERIAL names the one to use) that reaches the test environment in one of two ways:
#   - An emulator on this host reaches the 172.31.250.0/24 test network directly. Start it with
#     -dns-server 172.31.250.13  so that it resolves pbx.siplink.test / ldap.siplink.test.
#   - A real device on the local network: publish the environment on an address of this host
#     and run the tests with the same settings,
#         LAN_ADDRESS=192.0.2.10 LAN_NAME=pbx.example ./up.sh
#         LAN_ADDRESS=192.0.2.10 LAN_NAME=pbx.example ./run-tests.sh
#     LAN_NAME is a DNS name the device resolves to LAN_ADDRESS. It can be left out, but LDAP
#     over StartTLS only works with a name: those tests are skipped without it.
set -euo pipefail
cd "$(dirname "$0")"

[ -f .env ] && [ -f certs/ca.crt.pem ] || { echo "Start the test environment first: ./up.sh" >&2; exit 1; }
adb="${ANDROID_HOME:?ANDROID_HOME is not set}/platform-tools/adb"

set -a
# shellcheck disable=SC1091
. ./.env
set +a

# Addresses as the device reaches them: the test network itself, or the address of this host
# the environment was published on.
if [ -n "${LAN_ADDRESS:-}" ]; then
  pbx_host=$LAN_ADDRESS ldap_host=$LAN_ADDRESS
  phones_ami_host=$LAN_ADDRESS phones_ami_port=5039
  pbx_name=${LAN_NAME:-$LAN_ADDRESS} ldap_name=${LAN_NAME:-}
  # The name server of the environment is published on a port of this host; only it knows the name.
  dns_server=$LAN_ADDRESS:5300 dns_name_is_private=1
else
  pbx_host=172.31.250.10 ldap_host=172.31.250.11
  phones_ami_host=172.31.250.12 phones_ami_port=5038
  pbx_name=pbx.siplink.test ldap_name=ldap.siplink.test
  dns_server=172.31.250.13 dns_name_is_private=0
fi

# Everything the tests need to know, handed over as a single instrumentation argument so that
# no secret is stored in the test APK or in the repository.
env_blob=$(base64 -w0 <<EOF
pbxHost=$pbx_host
phonesHost=172.31.250.12
phonesAmiHost=$phones_ami_host
phonesAmiPort=$phones_ami_port
ldapHost=$ldap_host
pbxName=$pbx_name
ldapName=$ldap_name
dnsServer=$dns_server
dnsName=pbx.siplink.test
dnsNameIsPrivate=$dns_name_is_private
sipPassword=$SIP_PASSWORD
amiUser=$AMI_USER
amiSecret=$AMI_SECRET
ldapBaseDn=dc=siplink,dc=test
ldapBindDn=cn=reader,dc=siplink,dc=test
ldapPassword=$LDAP_READER_PASSWORD
caPemBase64=$(base64 -w0 certs/ca.crt.pem)
EOF
)

if [ -z "${SKIP_INSTALL:-}" ]; then
  abi=$("$adb" shell getprop ro.product.cpu.abi | tr -d '\r')
  (cd ../android && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Psiplink.abi="$abi" --console=plain -q)
  "$adb" install -r -t ../android/app/build/outputs/apk/debug/app-debug.apk
  "$adb" install -r -t ../android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi

# A real device left alone switches its screen off, and Android then tells a process in the
# background, such as the tests, that there is no network: nothing registers any more. Keep the
# device awake for as long as the tests run. The wake key switches the screen on but does not
# count as someone using the device, so the screen would still go off every time its timeout
# runs out; a key that no app acts on follows it.
(while "$adb" shell input keyevent KEYCODE_WAKEUP KEYCODE_SHIFT_LEFT > /dev/null 2>&1; do sleep 10; done) &
keep_awake=$!
trap 'kill "$keep_awake" 2> /dev/null || true' EXIT

# Runs one instrumentation process; succeeds when every test in it passed. The runner reports
# when the process is done; progress in between is easiest to follow in logcat (tag TestRunner).
run() {
  local output
  # Start from empty app data: an aborted run may have left accounts behind (up to 100 of them).
  "$adb" shell pm clear com.sky.siplink > /dev/null
  # Every class starts without the camera permission, as a new installation does; the video
  # tests grant it where they need it. Taking it away inside a test would kill the app process.
  "$adb" shell pm revoke com.sky.siplink android.permission.CAMERA > /dev/null 2>&1 || true
  output=$("$adb" shell am instrument -w -e siplinkEnv "$env_blob" "$@" \
    com.sky.siplink.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r')
  printf '%s\n' "$output" >&2
  grep -q '^OK (' <<<"$output"
}

if [ $# -gt 0 ]; then
  run -e class "$1"
  exit # with the result of that run
fi

# Every class runs in a process of its own. Some state only exists once per process (the LDAP
# library keeps the CA certificates of its first TLS connection), and a native crash then
# costs one class instead of the whole run.
failed=()
for class in RegistrationTest BasicCallTest TransferParkTest SupervisionTest CallHandlingTest \
  CallerIdTest LineRoutingTest CodecTest DirectoryTest DirectoryTrustTest ShortcutStoreTest DirectoryCacheTest \
  VideoCallTest VideoNoCameraTest ManagedLockTest ManagedScreensTest RemoteConfigTest \
  AdvancedSettingsTest SdkDefaultsTest AccountFormTest ReleaseJsonTest UpdateCheckTest CallRecordingTest IncomingSlideTest \
  ConferenceTest ConferencePacingTest VideoControlsTest VideoWindowTest CallConflictTest LandscapeScreensTest \
  LockScreenTest TwinEntriesTest; do
  echo "=== $class" >&2
  run -e class "com.sky.siplink.itest.$class" || failed+=("$class")
done
if [ ${#failed[@]} -gt 0 ]; then
  echo "FAILED: ${failed[*]}" >&2
  exit 1
fi
echo "All test classes passed." >&2

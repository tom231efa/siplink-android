<p align="right"><sub><a href="zh-CN/testing.md">中文</a> | English</sub></p>

# Testing

## Unit tests

```bash
android/gradlew -p android :app:testDebugUnitTest
```

Unit tests are in `android/app/src/test` and cover the rules in `core/logic` (account and shortcut key validation, CSV, incoming call policy, remote configuration documents and addresses, certificate pins, update version comparison, video rules and so on) and the theme. Sample remote configuration documents are in `android/app/src/test/resources/`.

## Lint

```bash
android/gradlew -p android :app:lintDebug
```

Lint findings do not stop the build (`abortOnError = false`); reports are in `android/app/build/reports/`.

## Integration tests

Integration tests are in `android/app/src/androidTest`; they run on an emulator or a physical device and connect to the test environment started with Docker in `testenv/`.

> **Warning**: the integration tests take over the installed SipLink debug build. App data is cleared before each test class, and the tests delete all accounts, settings and imported CAs. Do not run them on a device whose debug build has a configuration you want to keep.

### Test environment

| Service | Content |
| --- | --- |
| `asterisk` | The PBX under test (Asterisk), with the sample dial plan and feature codes |
| `phones` | A second Asterisk instance that simulates desk phones registered to the PBX (extensions 1002–1005), controlled by the tests through AMI |
| `ldap` | OpenLDAP, for the directory tests |
| `dns` | DNS server that resolves the test environment's domain names |

The first start generates `testenv/.env` (random passwords) and `testenv/certs/` (a one-off test CA and server certificates); both are ignored by git and must not be committed.

```bash
testenv/up.sh      # build and start
testenv/down.sh    # stop and remove containers and networks
```

### Requirements

- `ANDROID_HOME` and `JAVA_HOME` are set
- adb sees one device; with several, choose one with `ANDROID_SERIAL`
- `run-tests.sh` builds and installs the debug and test APKs for the device's ABI; with `SKIP_INSTALL=1` it uses the installed ones

### Emulator

The test environment runs in a private Docker network without published ports, which an emulator on the same machine can reach directly. Start the emulator with `-dns-server` pointing at the test environment's DNS server; the address is described at the top of `testenv/run-tests.sh`.

```bash
testenv/up.sh
testenv/run-tests.sh
```

### Physical device (LAN mode)

Publish the test environment on a LAN address of the machine, and use the same variables to start it and to run the tests:

```bash
LAN_ADDRESS={lan-address} LAN_NAME={lan-name} testenv/up.sh
LAN_ADDRESS={lan-address} LAN_NAME={lan-name} testenv/run-tests.sh
```

- `{lan-address}`: the LAN IP address of the host running Docker; the device must be able to reach it
- `{lan-name}`: a DNS name the device resolves to that address. It can be left out, but LDAP StartTLS works only with a domain name, so the related tests are skipped without it

When the address or the name changes, `up.sh` generates new certificates that contain them and recreates the containers.

### Running selected tests

```bash
testenv/run-tests.sh                                                   # all test classes: about an hour on a phone, the run before a release
testenv/run-tests.sh quick                                             # the quick set: some ten minutes, the run of a round of work
testenv/run-tests.sh quick ConferenceTest VideoCallTest                # the quick set and the classes a change touches
testenv/run-tests.sh com.sky.siplink.itest.BasicCallTest               # one class
testenv/run-tests.sh 'com.sky.siplink.itest.BasicCallTest#outgoingCallSendsDtmf'   # one method
SKIP_INSTALL=1 testenv/run-tests.sh com.sky.siplink.itest.CodecTest    # without reinstalling
```

When all tests run, each test class runs in its own process, and the failed classes are listed at the end. During the tests the script wakes the device screen at intervals, so that background processes do not lose the network when the screen is off. The passwords and addresses the tests need are passed as a single instrumentation argument and are not written into the test APK or the repository.

The following test classes are not part of the default run and must be run on their own:

| Class | Reason |
| --- | --- |
| `PbxReferTest` | A REFER started by the PBX leaves channels behind in Asterisk, which affects other tests; run it alone on an emulator inside the test network, then restart the PBX with `docker compose restart asterisk` in `testenv/` |
| `RealAudioTest` | Checks that the real microphone sends sound; needs a physical device and an environment that is not completely silent |

## Baseline profile and startup measurement

The `android/baselineprofile` module generates the app's baseline profile on a physical device (`BaselineProfileGenerator`: cold start, the dial pad and the four tabs) and measures the cold start time (`StartupBenchmark`). The generated files are kept in `android/app/src/release/generated/baselineProfiles/` and committed with the code; a normal build only uses the files that are there and needs no device.

> **Warning**: both tasks install release-type builds (`nonMinifiedRelease`, `benchmarkRelease`, with the same package name `com.sky.siplink`) on the device, replacing the installed debug build. When `android/keystore.properties` exists these builds are signed with the release key, otherwise with the debug key. If the signature differs from the installed app's, the installation fails; run `adb uninstall com.sky.siplink` first, which deletes the app's data.

### Requirements

- A physical device with Android 13 or later (or a rooted one with Android 9 or later), the only device adb is connected to; the measurement cannot run on an emulator
- The device's ABI is among the ABIs built (arm64-v8a by default; others with `-Psiplink.abi`)
- `ANDROID_HOME` and `JAVA_HOME` are set

### Generating

```bash
android/gradlew -p android :app:generateBaselineProfile
```

The result is written to `baseline-prof.txt` and `startup-prof.txt` in `android/app/src/release/generated/baselineProfiles/`. Before committing, check that `baseline-prof.txt` exists and contains rules for `com/sky/siplink/`: with some AGP versions the task reports success without collecting any rules (issuetracker b/543799367). In that case the `*-prof.txt` files generated on the device can be taken from `android/baselineprofile/build/outputs/connected_android_test_additional_output/`.

### Measuring

```bash
android/gradlew -p android :baselineprofile:connectedBenchmarkReleaseAndroidTest
```

Each test starts the app cold 10 times: `startupWithoutProfile` compiles nothing ahead of time, `startupWithBaselineProfile` compiles only the code the profile in the APK covers. The results (minimum, median and maximum of `timeToInitialDisplayMs`) are written as JSON to `android/baselineprofile/build/outputs/connected_android_test_additional_output/`. Before a profile has been generated, the APK holds only the rules that come with the libraries; measure before and after generating and compare the `startupWithBaselineProfile` results.

Afterwards `testenv/run-tests.sh` reinstalls the debug build; if the signatures differ, uninstall first.

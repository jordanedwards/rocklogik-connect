# rocklogik-connect

The Android app a technician uses in the cab to provision, check and record a RockLogik Scout
VISR install over Bluetooth Low Energy, and later to do the same for the Scout Sensors through the
VISR. Plan: [docs/Development Plan.md](docs/Development%20Plan.md). Contract with the devices:
[docs/protocol.md](docs/protocol.md). Design record: `../rocklogik`, POC Decisions §10.

## Build

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug    # needs the Android SDK with platform 37.0
./build.sh all                                          # the same in Docker: lint, tests, APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app targets Android 7.1 (API 25) and later; the test tablet is a Galaxy Tab A on 7.1.1. On
Android 11 and earlier, Bluetooth scanning needs Location permission and Location switched on.

## The mock VISR and the conformance checker

```sh
python3 -m venv tools/.venv && tools/.venv/bin/pip install -r tools/requirements.txt
tools/.venv/bin/python tools/conformance.py --direct                # the mock in-process, no radio
tools/.venv/bin/python tools/mock_visr.py --time-scale 60          # the mock over BLE, as VISR-MOCK
tools/.venv/bin/python tools/conformance.py --ble VISR-0001 --key …  # a real unit, from this machine
```

The mock's key is `MOCKKEYMOCKKEYMOCKKEYMOCKK`. The mock needs BlueZ with LE enabled: a
`ControllerMode = bredr` line in `/etc/bluetooth/main.conf` hides the GATT and advertising
managers it uses. A machine cannot be both the mock and the checker over BLE.

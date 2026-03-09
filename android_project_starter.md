# Android BLE Battery Monitor - Project Setup Guide

## Project Overview
This is an Android app that monitors battery levels from multiple XIAO nRF52840 devices via Bluetooth Low Energy (BLE) advertising packets. No connection required - devices broadcast their battery data and the app displays it in real-time.

## Project Structure
```
BLEBatteryMonitor/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/blebatterymonitor/
│   │       │   └── MainActivity.kt
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   ├── activity_main.xml
│   │       │   │   └── item_ble_device.xml
│   │       │   └── values/
│   │       │       ├── strings.xml
│   │       │       ├── colors.xml
│   │       │       └── themes.xml
│   │       └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle (project level)
├── settings.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
└── local.properties
```

## Prerequisites Checklist
- [ ] JDK 17 installed
- [ ] Android SDK Command Line Tools installed
- [ ] ANDROID_HOME environment variable set
- [ ] Android SDK components installed (platform-tools, platforms;android-34, build-tools;34.0.0)
- [ ] VS Code with Java Extension Pack installed
- [ ] Gradle wrapper initialized

## Key Files Reference

### MainActivity.kt
- Location: `app/src/main/java/com/example/blebatterymonitor/MainActivity.kt`
- Purpose: Main app logic - BLE scanning and device list management
- Key features:
  - Scans for BLE advertisements
  - Parses manufacturer data for battery info
  - Updates device list in real-time
  - Auto-removes stale devices (10s timeout)

### activity_main.xml
- Location: `app/src/main/res/layout/activity_main.xml`
- Purpose: Main screen layout with toolbar, RecyclerView, and FAB

### item_ble_device.xml
- Location: `app/src/main/res/layout/item_ble_device.xml`
- Purpose: Individual device card layout showing battery %, voltage, RSSI, status

### AndroidManifest.xml
- Location: `app/src/main/AndroidManifest.xml`
- Purpose: App configuration and permissions
- Required permissions:
  - BLUETOOTH_SCAN (Android 12+)
  - BLUETOOTH_CONNECT (Android 12+)
  - ACCESS_FINE_LOCATION (required for BLE scanning)

### build.gradle (app module)
- Location: `app/build.gradle`
- Purpose: App dependencies and build configuration
- Key dependencies:
  - Material Design Components
  - AndroidX libraries
  - RecyclerView

### local.properties
- Location: `local.properties` (root)
- Purpose: SDK location (not in version control)
- Content:
  ```properties
  sdk.dir=C\:\\Android
  ```
  Note: Adjust path to your actual Android SDK location

## BLE Data Format

### Manufacturer Data Packet
The XIAO devices broadcast battery data in manufacturer-specific data:

```
Byte 0-1: Company ID (0xFFFF for development/testing)
Byte 2:   Battery percentage (0-100)
Byte 3:   Voltage low byte (millivolts)
Byte 4:   Voltage high byte (millivolts)
```

Example: `FF FF 55 0F 10` = 85%, 4111mV (4.111V)

## Build Commands

### Initial Setup
```bash
# Windows
gradlew.bat wrapper --gradle-version 8.2

# Mac/Linux
./gradlew wrapper --gradle-version 8.2
```

### Build the App
```bash
# Windows
gradlew.bat build

# Mac/Linux
./gradlew build
```

### Install on Device
```bash
# Windows
gradlew.bat installDebug

# Mac/Linux
./gradlew installDebug
```

### Clean Build
```bash
# Windows
gradlew.bat clean build

# Mac/Linux
./gradlew clean build
```

### Uninstall from Device
```bash
# Windows
gradlew.bat uninstallDebug

# Mac/Linux
./gradlew uninstallDebug
```

## Common Issues & Solutions

### Issue: "sdkmanager not found"
**Solution:** 
1. Verify ANDROID_HOME is set: `echo %ANDROID_HOME%` (Windows) or `echo $ANDROID_HOME` (Mac/Linux)
2. Add to PATH: `%ANDROID_HOME%\cmdline-tools\latest\bin` (Windows)
3. Restart terminal/IDE after setting environment variables

### Issue: "SDK location not found"
**Solution:** Create `local.properties` file in project root:
```properties
sdk.dir=C\:\\Android
```
(Use your actual SDK path, escape backslashes on Windows)

### Issue: "License not accepted"
**Solution:** 
```bash
sdkmanager --licenses
```
Accept all licenses by typing 'y'

### Issue: Build fails with Gradle daemon error
**Solution:**
```bash
./gradlew --stop
./gradlew clean build
```

### Issue: ADB doesn't detect device
**Solution:**
1. Enable USB Debugging on Android device
2. Check USB cable (must be data cable, not charge-only)
3. Try different USB port
4. Revoke and re-authorize USB debugging on device
5. Run: `adb kill-server` then `adb start-server`

## Device Setup (XIAO nRF52840)

The companion device code is for Seeed Studio XIAO nRF52840. It:
- Wakes every second from deep sleep
- Reads battery voltage from A0 pin
- Advertises battery data via BLE (no connection needed)
- Returns to deep sleep (~150-200µA average current)

Upload the Arduino sketch to XIAO and it will automatically appear in the Android app when scanning.

## Testing Workflow

1. **Upload code to XIAO nRF52840**
   - Open Arduino IDE
   - Select "Seeed XIAO nRF52840" board
   - Upload the BLE battery monitor sketch
   - Device will start advertising immediately

2. **Build and install Android app**
   ```bash
   ./gradlew installDebug
   ```

3. **Grant permissions**
   - Open app on phone
   - Grant Bluetooth and Location permissions when prompted

4. **Start scanning**
   - Tap the floating action button (play icon)
   - App will start scanning for BLE advertisements

5. **Monitor devices**
   - XIAO devices appear in list automatically
   - Watch battery %, voltage, and signal strength update
   - No connection needed!

## Performance Characteristics

### Android App
- Continuous BLE scanning (low-latency mode)
- Updates UI as advertisements received
- Auto-removes devices not seen for 10 seconds
- Can monitor 10+ devices simultaneously

### XIAO Device
- Average power: ~150-200µA
- Battery life examples:
  - 200mAh LiPo: ~41 days
  - 500mAh LiPo: ~104 days
  - 1000mAh LiPo: ~208 days
- Advertising interval: 1 second
- Advertising duration: 50ms per cycle

## Future Enhancement Ideas

- [ ] Battery level alerts/notifications
- [ ] Data logging and export
- [ ] Historical graphs
- [ ] Configurable scan/update intervals
- [ ] Device naming/organization
- [ ] Multiple battery chemistry profiles
- [ ] Settings page for device configuration
- [ ] Background monitoring service

## Resources

- [Android BLE Documentation](https://developer.android.com/guide/topics/connectivity/bluetooth/ble-overview)
- [Seeed XIAO nRF52840 Wiki](https://wiki.seeedstudio.com/XIAO_BLE/)
- [Bluefruit nRF52 Library](https://github.com/adafruit/Adafruit_nRF52_Arduino)
- [Gradle Build Tool](https://gradle.org/)
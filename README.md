# Camtraptions Battery Monitor

An Android app that monitors battery levels from multiple Camtraptions devices via Bluetooth Low Energy (BLE). No connection required — devices broadcast their battery data and the app displays it in real-time.

## Features

- Scans for BLE advertisements from Camtraptions XIAO nRF52840 devices
- Displays battery percentage, voltage, and signal strength (RSSI) for each device
- Color-coded battery indicator (green / yellow / red)
- Auto-removes devices not seen for 10 seconds
- Tap any device to open a settings panel for renaming and reset (requires firmware update)

## Screenshots

_Coming soon_

## Requirements

- Android 6.0 (API 24) or higher
- Bluetooth LE support
- Location permission (required by Android for BLE scanning)

## Building

1. Clone the repository
2. Open in Android Studio
3. Build and install:

```bash
./gradlew installDebug
```

## BLE Data Format

Devices advertise battery data in manufacturer-specific data using company ID `0xFFFF`:

| Byte | Description |
|------|-------------|
| 0    | Battery percentage (0–100) |
| 1    | Voltage low byte (millivolts, little-endian) |
| 2    | Voltage high byte (millivolts) |

**Example:** `55 0F 10` = 85%, 4111 mV (4.111 V)

## Firmware

The companion Arduino sketch for the Seeed Studio XIAO nRF52840 is in the `Camtraptions_Firmware/` folder.

- Reads battery voltage from pin A0
- Broadcasts battery data via BLE advertisement every ~1 second
- Device name shown in the app is set via `#define DEVICE_NAME` in the sketch

**Setup:**
1. Install *Seeed nRF52 Boards* in Arduino IDE Board Manager
2. Select *Seeed XIAO nRF52840* as the board
3. Upload `Camtraptions_Firmware.ino`

## Roadmap

- [ ] Save device name to EEPROM via BLE (pending firmware support)
- [ ] Remote reset via BLE (pending firmware specs)
- [ ] Battery level notifications
- [ ] Historical graphs and data logging
- [ ] Background monitoring service

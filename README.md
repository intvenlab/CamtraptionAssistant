# Camtraption Assistant

An Android app for monitoring and configuring Camtraptions devices via Bluetooth Low Energy. Devices broadcast their data continuously — no persistent connection required for the main scan view.

## Features

### Scan / Kit Screen

- **Live scan list** — devices update in real time from BLE advertisements (~1 s)
- **Kit view** — devices sharing a Kit ID are collapsed into a single card; tap to open the Kit dashboard
- **Last seen** — each device row shows live battery when in range, or elapsed time since last seen when out of range
- **Long-press to forget** — hold a Kit card for 2 seconds to remove all its devices from memory
- **Setup flow** — unconfigured devices are pinned at the top with a setup prompt

### Device Dashboard

- **Dual battery display** — external pack and internal CR2032 always shown; displays "No Battery Connected" in red when no external pack is present
- **Stats card** — device type, Kit number, and connection status (shows "Connected" when in range, switches to "Last Seen" elapsed time when out of range)
- **Camera card** — shutter count with one-tap reset (Camera devices only)
- **Event log** — timestamped history of device events, always visible even when the device is offline

### Event Log

Logs are stored per device (up to 500 entries) and survive navigating away from the device page.

| Event | Trigger |
|-------|---------|
| Connect | Device comes into BLE range |
| Disconnect | Device goes out of BLE range |
| Shutter | Shutter count changes |
| Battery | External battery voltage changes by ≥ 1 V |
| Batt Out | External battery pack removed |
| Batt In | External battery pack attached |

- Compact rows show: timestamp · event badge · shutter count (Camera) · external battery % · voltage
- Tap any row to expand full telemetry: internal battery, RSSI, camera state/flags, firmware build
- No events are logged while the device is out of range

### Device Settings

- Configure device name, type, battery chemistry, cell count, and Kit ID over BLE
- **Camera Logic** — full 19-parameter config (timing, sequence counts, I/O policies) read/written over GATT
- **External battery calibration** — set or reset voltage calibration offset
- **Internal CR2032 calibration** — set or reset coin-cell voltage calibration
- **Factory reset** — clears all flash settings on the device
- **Send Log** — shares the raw JSON event log via the Android share sheet; subject line includes Kit number and device name; body signed "Sent from Camtraption Assistant App"

---

## BLE Advertising Packet

Manufacturer-specific data, company ID `0xFFFF`. The app parses both formats.

### New format (13+ bytes, firmware v1.1+)

| Bytes | Field |
|-------|-------|
| 0 | Internal battery % (A0 / CR2032) |
| 1–2 | Internal voltage mV, little-endian |
| 3 | External battery % (A1); `0xFF` = not present |
| 4–5 | External battery mV, little-endian |
| 6 | Flags: `bit0`=configured, `bits1-2`=device type, `bits3-4`=chemistry |
| 7 | Kit ID (0 = no kit) |
| 8 | Cell count |
| 9–10 | Shutter count, little-endian uint16 |
| 11 | Extension type (`0x02` = camera state block) |
| 12 | Camera state byte |
| 13 | Camera live flags |
| 14–15 | Firmware build year, little-endian |
| 16 | Build month |
| 17 | Build day |
| 18 | Build hour |
| 19 | Build minute |
| 20 | Build second |

### Legacy format (8 bytes, firmware pre-v1.1)

| Bytes | Field |
|-------|-------|
| 0 | Battery % |
| 1–2 | Voltage mV, little-endian |
| 3 | Flags |
| 4 | Group ID |
| 5 | Cell count |
| 6–7 | Shutter count, little-endian uint16 |

---

## GATT Service

Base UUID: `ca500000-0000-0000-0000-000000000000`

| UUID suffix | Characteristic | Properties | Length |
|-------------|----------------|------------|--------|
| `...0001` | Device Name | Read / Write | up to 20 bytes (UTF-8) |
| `...0002` | Group ID | Read / Write | 1 byte |
| `...0003` | Group Name | Read / Write | up to 20 bytes (UTF-8) |
| `...0004` | Device Type | Read / Write | 1 byte (0=BattMon 1=Camera 2=Strobe 3=Focus) |
| `...0005` | Battery Chemistry | Read / Write | 1 byte (0=LiPo 1=LiFePO4 2=NiMH 3=Alkaline) |
| `...0006` | Cell Count | Read / Write | 1 byte (1–8) |
| `...0007` | Shutter Count | Read / Notify | 4 bytes, little-endian uint32 |
| `...0008` | Reset Shutter | Write | 1 byte (write `0x01` to reset) |
| `...0009` | Factory Reset | Write | 1 byte (write `0x01` to clear flash) |
| `...000a` | Camera Config | Read / Write / Notify | 22 bytes v3 (see below) |
| `...000b` | Camera Config Status | Notify | 1 byte ACK |
| `...000c` | Ext Cal Set | Write | 2 bytes mV, little-endian |
| `...000d` | Int Cal Set | Write | 2 bytes mV, little-endian |

### Camera Config characteristic (22 bytes, v3)

| Byte | Field |
|------|-------|
| 0 | Version (3) |
| 1 | Enabled (0/1) |
| 2 | Wake HP hold (seconds) |
| 3 | Min HP before shutter (×100 ms) |
| 4–5 | Shutter pulse duration (×10 ms), little-endian |
| 6–7 | Frame spacing (×10 ms), little-endian |
| 8 | Post-shutter HP hold (×100 ms) |
| 9 | HP debounce (ms) |
| 10 | FP debounce (ms) |
| 11 | Frame count (0–64) |
| 12 | Max sequence count (0–64) |
| 13 | Wake hold refresh policy (0=extend 1=restart 2=ignore) |
| 14 | Half-press during burst policy (forced 0) |
| 15 | Full-press without HP policy (0=assert HP then wait 1=ignore) |
| 16 | Activity HP hold policy (forced 0) |
| 17 | FP after max sequences policy (0=ignore until activity end) |
| 18 | Input active polarity (forced by firmware) |
| 19 | Output drive mode (forced by firmware) |
| 20 | Power save idle mode (0/1) |
| 21 | FP ignore gap (×100 ms) |

---

## Requirements

- Android 6.0 (API 24) or higher
- Bluetooth LE
- `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions (Android 12+), or `ACCESS_FINE_LOCATION` (Android 11 and below)

---

## Building

```bash
./gradlew installDebug
```

---

## Project Structure

```
app/src/main/java/com/ivlabs/batterymonitor/
  MainActivity.kt          # BLE scanning, device model, Kit/scan list UI
  AppScreen.kt             # Navigation sealed class, GATT UUIDs, enum helpers
  DeviceScreen.kt          # Device dashboard, event log, settings screen
  CameraConfigScreen.kt    # 19-parameter camera logic config UI
  CommonComposables.kt     # Shared UI components (dropdowns, GATT status banner)
  BleGattManager.kt        # Coroutine-based GATT read/write/notify
  DeviceHistoryStore.kt    # JSON-backed per-device event log (500 entries max)
  KnownDeviceStore.kt      # Persists configured devices across app restarts
  GroupNameStore.kt        # SharedPreferences-backed Kit name store
  GroupScreen.kt           # Kit dashboard
  SetupScreen.kt           # First-time device configuration flow

app/src/main/res/xml/
  file_paths.xml           # FileProvider path config for Send Log share
```

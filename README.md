# Camtraptions Battery Monitor

An Android app and companion firmware for monitoring battery levels across multiple Camtraptions devices via Bluetooth Low Energy. Devices broadcast their data continuously — no persistent connection required for the main scan view.

## Features

### Android App

- **Live scan list** — devices update in real time from BLE advertisements (~1 s)
- **Group view** — devices sharing a Group ID are collapsed into a single card; tap to expand
- **Device dashboard** — large battery gauge, voltage, signal, connection history, shutter count
- **Dual battery display** — primary device/camera battery (A1) shown on gauge; internal CR2032 supply (A0) shown in stats
- **Camera controls** — shutter count with reset button and Camera Logic shortcut directly on the device page
- **Settings page** — configure device name, type, chemistry, cell count, and group over BLE
- **Camera Logic screen** — full 19-parameter camera config (timing, sequence counts, I/O policies) read/written over GATT
- **Connection history** — timestamped log of battery readings per device
- **Backward compatible** — parses both new 13-byte and legacy 8-byte advertising packets
- **Setup flow** — unconfigured devices are pinned at the top of the scan list with a setup prompt

### Firmware (Seeed XIAO nRF52840)

- **Dual battery ADC** — CR2032 internal supply on A0 (coin-cell discharge curve), device/camera LiPo on A1 (LiPo curve)
- **GATT config service** — read/write device name, type, chemistry, cell count, group, shutter count, and camera config over BLE
- **Flash persistence** — device settings saved to `/settings.bin`; camera config saved to `/camera.bin`; both survive power cycles and firmware updates (version-guarded)
- **Factory reset** — single GATT write clears all flash settings
- **Camera I/O framework** — interrupt-driven FP and HP inputs/outputs with two operating modes:
  - **State-machine mode** (Camera Logic enabled): IDLE → WAKE\_HP → BURST\_ACTIVE → POST\_BURST\_HOLD with configurable timing and policies
  - **Pass-through mode** (Camera Logic disabled): CHANGE interrupts mirror FP\_IN → FP\_OUT and HP\_IN → HP\_OUT with zero loop latency; shutter counting still active
- **Open-drain outputs** — FP\_OUT and HP\_OUT idle as high-Z (INPUT), asserted as OUTPUT LOW; compatible with camera hot-shoe and 3.5 mm trigger circuits
- **Camera state machine** — configurable wake hold, AF settle time, shutter pulse duration, frame spacing, post-burst hold, sequence counts, and multiple refresh/ignore policies
- **Sleep gating** — camera logic activity blocks BLE advertisement sleep cycle to prevent missed events
- **Device types** — Battery Monitor, Camera, Strobe, Focus Light; camera I/O only activates for Camera type

---

## Pin Assignments (XIAO nRF52840)

| Pin | Role | Notes |
|-----|------|-------|
| A0 / D0 | `BATTERY_PIN` — internal CR2032 ADC | Powers the monitor itself |
| A1 / D1 | `DEVICE_BATTERY_PIN` — camera/device battery ADC | Shown on main gauge |
| D2 | `FP_IN_PIN` — full-press / shutter input | FALLING interrupt (state-machine) or CHANGE (pass-through) |
| D3 | `HP_IN_PIN` — half-press input | FALLING interrupt (state-machine) |
| D4 | `FP_OUT_PIN` — full-press output | Open-drain to camera |
| D5 | `HP_OUT_PIN` — half-press output | Open-drain to camera |

---

## BLE Advertising Packet

Manufacturer-specific data, company ID `0xFFFF`. The app parses both formats.

### New format (13 bytes, firmware v1.1+)

| Bytes | Field |
|-------|-------|
| 0 | Internal battery % (A0 / CR2032) |
| 1–2 | Internal voltage mV, little-endian |
| 3 | Device battery % (A1); `0xFF` = not present |
| 4–5 | Device battery mV, little-endian; `0xFFFF` = not present |
| 6 | Flags: `bit0`=configured, `bits1-2`=device type, `bits3-4`=chemistry |
| 7 | Group ID (0 = no group) |
| 8 | Cell count |
| 9–10 | Shutter count, little-endian uint16 |

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
| `...000a` | Camera Config | Read / Write | 19 bytes (see below) |

### Camera Config characteristic (19 bytes)

| Byte | Field | Default |
|------|-------|---------|
| 0 | Version (always 1) | 1 |
| 1 | Enabled (0/1) | 0 |
| 2 | Wake HP hold (seconds) | 10 |
| 3 | Min HP before shutter (×100 ms) | 5 |
| 4 | Shutter pulse duration (×10 ms) | 10 |
| 5 | Frame spacing (×100 ms) | 10 |
| 6 | Post-shutter HP hold (×100 ms) | 20 |
| 7 | HP debounce (ms) | 35 |
| 8 | FP debounce (ms) | 20 |
| 9 | Frame count (1–8) | 4 |
| 10 | Max sequence count (1–8) | 4 |
| 11 | Wake hold refresh policy (0=extend 1=restart 2=ignore) | 0 |
| 12 | Half-press during burst policy | 0 |
| 13 | Full-press without HP policy (0=assert HP then wait 1=ignore) | 0 |
| 14 | Activity HP hold policy | 0 |
| 15 | FP after max sequences policy | 0 |
| 16 | Input active polarity (0=active low 1=active high) | 0 |
| 17 | Output drive mode (0=open drain 1=push-pull) | 0 |
| 18 | Power save idle mode (0/1) | 0 |

---

## Requirements

### Android
- Android 6.0 (API 24) or higher
- Bluetooth LE
- `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` permissions (Android 12+), or `ACCESS_FINE_LOCATION` (Android 11 and below)

### Firmware
- Seeed Studio XIAO nRF52840
- Arduino IDE with *Seeed nRF52 Boards* package installed
- Adafruit Bluefruit library
- Adafruit LittleFS / InternalFileSystem library

---

## Building

### Android app
```bash
# Debug build and install
./gradlew installDebug
```

### Firmware
1. Install *Seeed nRF52 Boards* in Arduino IDE Board Manager
2. Select **Seeed XIAO nRF52840** as the board
3. Open `Camtraptions_Firmware/Camtraptions_Firmware.ino`
4. Upload

---

## Project Structure

```
app/src/main/java/com/ivlabs/batterymonitor/
  MainActivity.kt          # BLE scanning, device model, scan list UI
  AppScreen.kt             # Navigation sealed class, GATT UUIDs, enum helpers
  DeviceScreen.kt          # Device dashboard + settings screen
  CameraConfigScreen.kt    # 19-parameter camera logic config UI
  CommonComposables.kt     # Shared UI components (gauge, cards, dropdowns)
  BleGattManager.kt        # Coroutine-based GATT read/write
  DeviceHistoryStore.kt    # JSON-backed per-device connection history
  GroupNameStore.kt        # SharedPreferences-backed group name store
  GroupScreen.kt           # Group dashboard
  SetupScreen.kt           # First-time device configuration flow

Camtraptions_Firmware/
  Camtraptions_Firmware.ino   # Arduino sketch for XIAO nRF52840
```

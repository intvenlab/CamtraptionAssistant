# ESP32-S3 Camera Hub — architecture & rollout plan

## Context

The nRF52's internal LittleFS filesystem has shown real, confirmed corruption in the field
(`lfs error:493: Corrupted dir pair`, diagnosed via `CFG_DEBUG=1` earlier this session) that can
permanently strand a device unable to persist any configuration. A hard-coded-config stopgap
shipped to get through one weekend, but it doesn't address the underlying problem, and the
current system's logging is limited to whatever's captured on the phone while it happens to be
connected — there's no continuous record of what a device did while nobody was around.

The proposed redesign: move the **Camera device** from the nRF52 to an ESP32-S3, gaining "hub"
responsibilities — logging continuously to an SD card (both its own camera-trigger events and
the nearby battery monitors it passively overhears), with the phone app connecting occasionally
to browse, download, and erase those log files. **This is not a new device type** — it stays
`DeviceType.CAMERA` (ordinal 1) in the app's model, same as today, just on new hardware with a
larger firmware and more responsibility. "Hub" below is shorthand for this ESP32-based Camera
device's expanded role, not a distinct entry in the device-type enum. The camera board is
inherently more complex than a battery monitor and carries other camera-specific functionality
(the existing `CameraConfig` settings/burst logic, and more to come) that migrates and grows on
this platform over time — this plan's firmware-porting section treats that as first-class, not
an afterthought. Battery-monitor device types (BattMon/Strobe/Focus/Feeder) are fully unchanged
— same nRF52 firmware, same BLE-advertise/GATT-configure pattern as today.

User-confirmed decisions (firm, shape everything below):
1. **External trigger only** — the ESP32 Camera device drives the existing FP_OUT/HP_OUT cable
   interface to whatever camera is already in use; no onboard camera sensor/image-capture
   pipeline.
2. **Full replacement, no coexistence requirement** — the codebase doesn't need to support old
   nRF52 Cameras and new Hubs indefinitely, though a real-world transition period is expected.
3. **Hub runs its own WiFi access point** — no field infrastructure assumed; phone connects
   directly to the Hub when in range, briefly leaving its normal network during sync.
4. **Passive BLE scanning only** — Hub listens to nearby devices' existing advertisements
   (same manufacturer-data the phone already parses), no GATT connections to them.

Two research passes (current-codebase exploration + external ESP32-S3/SD/BLE/WiFi research)
plus a design pass produced the plan below. One finding worth foregrounding: **SD card FAT
filesystems have the same class of power-loss corruption vulnerability as the nRF52 LittleFS
bug that motivated this whole redesign** — moving to SD doesn't inherit safety for free. The
firmware architecture below (append-only, buffered/batched writes, one file kept open per
day rather than per-event, evaluating LittleFS-over-SD instead of plain FAT) exists specifically
to not repeat that failure on new hardware; SD logging hardening is treated as the go/no-go
gate for the whole initiative in the rollout plan, not an afterthought.

## 1. Hardware recommendation

Start with a **Seeed XIAO ESP32S3 Sense** board for Milestone 0/1 prototyping — dual-core,
WiFi + BLE5, 8MB PSRAM/flash, SD slot wired via SDMMC (the onboard camera sensor goes unused
per decision #1; the board's value here is its known-good SD story, not the camera). Confirm
early that the fixed SDMMC pin group doesn't collide with the 4 trigger-cable GPIOs — a
Milestone-0 verification item, not a settled fact.

- **SD interface**: use the board's onboard SDMMC for prototyping (free, no extra wiring), but
  keep the log-writer behind a standard `SD`/`SdFat`-style abstraction so a future custom PCB
  could move to SPI-mode SD (more GPIO-routing flexibility, throughput doesn't matter at this
  log volume) without touching the logging logic itself.
- **GPIOs**: `camera.cpp`'s Camera logic isn't a simple passthrough — it needs 2
  interrupt-capable inputs (`FP_IN`/`HP_IN`, `INPUT_PULLUP` + `FALLING` interrupt) and 2
  open-drain-capable outputs (`FP_OUT`/`HP_OUT`, toggled between driven-LOW and floating-INPUT)
  to actually replicate today's trigger-cable behavior — 4 dedicated GPIOs minimum, trivially
  available on ESP32-S3. Budget 2 more ADC pins for battery dividers, both on **ADC1** — ADC2 is
  well-documented as unreliable while WiFi is active, and this Hub runs WiFi far more than any
  current device does.
- **Power**: WiFi SoftAP can't be duty-cycled the way BLE can — once it's on, it draws
  continuously in the 50-380mA range. This reinforces decision #3: WiFi must default OFF,
  powering on only for bounded, user-initiated sync sessions with an idle-timeout backstop. The
  camera trigger interrupt needs sub-100ms responsiveness (existing debounce windows are
  20-35ms), so the idle state between BLE windows should be **light sleep with GPIO wake**, not
  deep sleep — a deliberate small power cost traded for guaranteed shutter responsiveness. Don't
  promise a battery-life number without bench data (Milestone 5/7 measurement, not a spec now).

## 2. Firmware architecture

**Core allocation**: ESP32-S3's WiFi/BLE stacks pin their own tasks to core 0 in both
Arduino-ESP32 and ESP-IDF. Dedicate **core 1 to a single high-priority FreeRTOS task running the
camera trigger state machine**, isolated from WiFi SoftAP / HTTP server / BLE scan / BLE
peripheral GATT, all on core 0. This is a real departure from the nRF52's single-`loop()`
polling model, which is fine on a near-idle radio stack but risks network-task jitter bleeding
into shutter timing on a much busier chip.

**Porting `camera.cpp`**: port the state machine, `CameraConfig` tunables, and accumulated
edge-case handling (cold-FP-wait, gap-boundary acceptance, debounce, max-sequence-timeout)
**almost verbatim** — it's proven and field-tuned, not worth re-deriving. What should change is
the execution model around it: replace the nRF52's "ISR sets a `volatile bool`, main loop polls
it" pattern with interrupt-to-task notification (`xTaskNotifyFromISR`) waking the dedicated
core-1 task immediately on FP/HP edges. Validate the port against the original firmware with a
logic analyzer comparing pulse widths/debounce windows before trusting it — **the single
highest-risk correctness item in this whole plan** (Milestone 1).

**`CameraConfig` GATT contract carries over unchanged**: the existing `CAMERA_CONFIG`/
`CAMERA_CONFIG_STATUS` characteristics (`ca50000a`/`ca50000e`) and their 22-byte packed struct
are the wire contract the Android app's already-built Camera Logic settings UI
(`DeviceSettingsScreen`) speaks today. Preserving that exact UUID/struct contract on the ESP32
firmware means the existing settings screen keeps working with **zero Android changes** — this
should be treated as part of the M1 port, not deferred. Additional camera-specific capabilities
beyond what's built today ("other stuff" — not yet specified) are understood to be a later,
incremental addition, following the same established pattern of one new GATT characteristic per
feature (as `FEEDER_CONFIG`, `FEEDER_PUMP_OVERRIDE`, etc. were each added) rather than something
to design in detail now.

**SD write pattern** (the actual fix for the corruption-class problem): today's `storage.cpp`
corruption traces to a "delete-then-rewrite-the-whole-file" pattern repeated on every settings
change — concentrated open/close/erase cycles on the same logical file. Don't repeat that shape
on SD:
- One log file **kept open for the whole day/session**, not opened/closed per event.
- **Append-only** writes — never rewrite existing content.
- **Buffered, batched flushes** on an interval (e.g. every N seconds/records), not after every
  write — worst case on power loss is losing the last unflushed batch, not corrupting the whole
  file/filesystem.
- Evaluate **LittleFS-over-SD** (available ESP-IDF v5.2+) instead of plain FATFS — a strictly
  better fit for this failure class, and would make "SD inherits the same corruption class as
  internal flash" a non-issue rather than a residual risk to manage around.
- Validate with an actual power-cut test rig (Milestone 2) — this is the direct regression test
  for the problem that motivated the whole redesign; budget real rigor here.

## 3. Log data model

**Two streams, one file per day per stream, JSONL (newline-delimited JSON), append-only**:
`/logs/camera/YYYY-MM-DD.jsonl` (Hub's own trigger events, mirroring today's
`DeviceHistoryEntry` shape) and `/logs/scan/YYYY-MM-DD.jsonl` (passively-scanned nearby-device
telemetry). JSONL for consistency with the app's existing JSON-everywhere convention
(`KnownDeviceStore`, `DeviceHistoryStore`), and because WiFi's throughput headroom makes CSV/
binary's size savings irrelevant here. It's also failure-atomic at line granularity — a
power-loss-truncated last line can be skipped by the parser without corrupting the rest of the
file, a much softer failure mode than "whole directory pair corrupted."

**Time/rotation**: the Hub has no internet route while SoftAP-only, so no NTP by default. Add a
lightweight `TIME_SYNC` GATT write (phone writes unix-epoch-seconds on every GATT connect —
cheap, piggybacks on an already-established interaction) to set the Hub's RTC. Before any sync
has happened, fall back to boot-relative session files (`session_00042.jsonl`, counter in NVS)
rather than blocking logging on having ever seen a phone.

**Retention**: SD is GB-scale, so cap by file count/age at rotation (keep last N daily files per
stream), not by trimming within a file like `DeviceHistoryStore`'s 500-entry cap does today. Two
flat per-day files (not one-file-per-scanned-device) is what makes "list/download/erase from the
app" practical — a handful of listable files, not hundreds.

## 4. BLE protocol additions

**No new `DeviceType` at all.** This stays `DeviceType.CAMERA` (ordinal 1) — the ESP32 firmware
advertises with the exact same device-type bits the nRF52 Camera already uses today. No changes
needed to `deviceTypeFromFlags()`, `displayName()`, or `sortOrder()` — the existing Camera icon,
label, and Kit-card sort position already apply automatically. Same manufacturer-data
advertisement layout, so it slots into the existing zero-connection scan/dashboard pipeline
completely unchanged.

What's new are **additional GATT characteristics on the existing Camera GATT service**,
alongside `CAMERA_CONFIG`/`CAMERA_CONFIG_STATUS` — these expose the new logging/hub capabilities
of the same device, not a new device. Continuing the incremental-UUID pattern from `ca500011`
(`FEEDER_PUMP_OVERRIDE`, the last one in use):
- `ca500012` `CAMERA_LOG_STATUS` — read/notify: AP state, SD state, per-stream file counts/sizes
  (same packed-struct-plus-notify shape as `CameraTelemetryPayload`).
- `ca500013` `CAMERA_WIFI_AP_CONTROL` — write: 1-byte start/stop.
- `ca500014` `CAMERA_WIFI_AP_INFO` — read/notify: SSID/password/IP/port once the AP is confirmed up.
- `ca500015` `CAMERA_TIME_SYNC` — write: unix epoch seconds.

(Named with a `CAMERA_` prefix, not `HUB_`, to keep the GATT UUID naming honest about which
device type these live on — matching the existing `CAMERA_CONFIG`/`FEEDER_CONFIG` convention of
naming by feature-on-a-device-type.) Reuse `DEVICE_NAME`/`GROUP_ID`/`GROUP_NAME`/`FACTORY_RESET`
unchanged. Deliberately **no per-file GATT characteristics** — bulk transfer stays WiFi-only per
decision #4's spirit (minimal BLE surface), keeping this addition small.

## 5. WiFi/HTTP protocol

Local HTTP server on the Hub's SoftAP interface (`192.168.4.1:80`, conventional SoftAP address):
- `GET /logs` → JSON array of `{stream, filename, sizeBytes, rotatedAt, isCurrentlyOpen}`.
- `GET /logs/{stream}/{filename}` → streams the raw file for download.
- `DELETE /logs/{stream}/{filename}` → deletes; reject (409) if it's the currently-open file.
- `GET /status` → same summary as `CAMERA_LOG_STATUS` plus firmware build/MAC, so the app can
  confirm it's really talking to the Camera device it discovered over BLE before trusting the
  connection.

**Discovery/handoff**: phone already has this Camera device's BLE identity from scanning (same
as any device today). User taps "Sync Logs" → app opens GATT (existing `BleGattManager`
pattern) → writes `CAMERA_WIFI_AP_CONTROL=start` → reads `CAMERA_WIFI_AP_INFO` → joins via
`WifiNetworkSpecifier` scoped to the app's process (API 29+), binding HTTP requests to that
`Network` explicitly so the phone doesn't leave its normal network system-wide. Releases the
specifier and writes `CAMERA_WIFI_AP_CONTROL=stop` on completion. The app's `minSdk 24` means
pre-Q devices have no scoped-network API and will genuinely leave their normal network while
synced — a documented, tested limitation (Milestone 6), not a surprise.

## 6. Android app changes

**No `DeviceType` enum work at all** — since this stays `DeviceType.CAMERA`, there's nothing to
add to the enum, `deviceTypeFromFlags()`, `displayName()`, or `sortOrder()`; every existing
Camera device already sorts/displays/scans correctly. **Reuses existing patterns directly**: the
new GATT characteristics wire through the unchanged `BleGattManager`, surfaced in
`DeviceSettingsScreen` alongside the existing (unchanged) `CAMERA_CONFIG` UI, gated the same way
today's Camera-only cards already are — `if (device.deviceType == DeviceType.CAMERA) { ... }`. A
new `AppScreen.CameraLogs(device, bluetoothDevice)` case fits the existing hand-rolled back
stack, same shape as `Setup`/`DeviceSettings`, reached from Camera devices specifically (e.g. a
new button on the Camera dashboard/settings screen, alongside the existing Camera Logic
settings). The "Send Log" share-sheet mechanism (`FileProvider`, existing `file_paths.xml`
authority already covering all of `filesDir`) is directly reusable as the final step once a
downloaded log file is cached locally.

**Genuinely new — no existing precedent**:
- An HTTP client — the app currently has zero networking dependencies; picking one
  (`HttpURLConnection` vs OkHttp) is an explicit Milestone-6 `build.gradle` decision.
- `WifiNetworkSpecifier`/`ConnectivityManager` network-binding code — new API surface, though
  `BleGattManager`'s "connect → wait for state → do work → disconnect" shape is a reasonable
  structural template to mirror.
- A **remote file catalog + download-then-cache-locally store** — nothing like this exists
  today (`DeviceHistoryStore` is one continuously-overwritten local file, not a directory of
  discrete remote files with metadata). New store mirroring `KnownDeviceStore`'s shape (single
  JSON file, `synchronized` lock, `Dispatchers.IO`) holding
  `{cameraAddress, stream, filename, localCachePath, downloadedAt, sizeBytes}` rows, populated
  from `GET /logs` and updated per successful download.
- A Camera Log Browser screen: two lists (camera-events/scan streams) with size/date and
  Download/Delete, backed by the new HTTP client + catalog store.

Worth flagging, not blocking: a real async-download-progress flow may strain the current
all-state-in-`MainActivity` Compose pattern more than existing screens do. Stay consistent with
the existing pattern for this rollout; treat a ViewModel/state-management refactor as an
optional follow-up only if this screen's state actually becomes unmanageable in practice.

## 7. Phased rollout plan

Ordering pulls the two highest-uncertainty items — radio coexistence and SD power-loss
resilience (the actual problem this redesign exists to solve) — to the front, validated cheaply
and independently before sinking effort into the Android app or a field pilot, both comparatively
low-risk given existing GATT/JSON-store precedent.

- **M0 — Hardware bring-up / coexistence spike (1-2 wks).** Confirm SD mounts via SDMMC; prove
  BLE peripheral + BLE scanner + WiFi SoftAP + HTTP server can run concurrently without
  crashing. Highest technical unknown — resolve before committing to the firmware architecture.
- **M1 — Camera trigger core loop port (2-3 wks, parallel-safe with M0/M2).** Port `camera.cpp`
  onto a dedicated core-1 task with interrupt-to-task notification; validate FP_OUT/HP_OUT
  timing against the original firmware with a logic analyzer. Riskiest correctness item.
- **M2 — SD logging hardening (2 wks, parallel with M1).** Append-only/batched-flush/rotated
  JSONL writer; repeated power-cut tests confirming only the last unflushed record is lost and
  the filesystem stays mountable; evaluate LittleFS-on-SD vs FAT. **Go/no-go gate for the whole
  initiative** — budget real rigor here, not a quick pass.
- **M3 — WiFi HTTP transfer (2 wks).** SoftAP + the 4-endpoint API against M2's files;
  testable with curl before any Android work exists.
- **M4 — BLE nearby-device scanning + peripheral GATT (1-2 wks, parallel with M3).** Passive
  scanning of nearby battery-monitor devices into the scan stream; the new
  `CAMERA_LOG_STATUS`/`CAMERA_WIFI_AP_*`/`CAMERA_TIME_SYNC` characteristics added to the
  existing Camera peripheral GATT service and advertisement (unchanged device-type bits).
- **M5 — Full concurrency soak test (2 wks).** Everything running together continuously for
  days (not hours) — BLE always-on scan+advertise, scheduled trigger firing, periodic simulated
  WiFi syncs, SD logging throughout — watching for stack overflows/watchdog resets/fragmentation.
  Long-run WiFi+BLE coexistence stability is a classic ESP32 pain point; don't skip straight to
  a field pilot on a few hours of bench time.
- **M6 — Android app integration (2-3 wks, can start once M3's API is stable).**
  `WifiNetworkSpecifier` flow tested standalone against M3's server, Camera Log Browser +
  download catalog, new GATT UUIDs in Camera's existing Settings screen. No `DeviceType`
  changes needed — existing Camera devices already work.
- **M7 — Field pilot (ongoing).** Small number of ESP32 Camera units alongside existing nRF52
  Camera units (real transition coexistence, even though the architecture doesn't need to
  support it indefinitely per decision #2); validate battery life under realistic duty cycle and
  SD reliability under field conditions. Gate full fleet migration on this data.

## Verification approach

This is a multi-week initiative verified milestone-by-milestone as listed above, not end-to-end
at the finish — each milestone has its own concrete pass/fail check (coexistence doesn't crash;
ported timing matches the original via logic analyzer; power-cut tests don't corrupt the
filesystem; curl can list/download/delete against the HTTP API; days-long soak test shows no
resource exhaustion; field pilot data on battery life and SD reliability). M2's power-cut testing
is the one to treat as non-negotiable, since it's the direct regression test for the problem
that motivated this entire redesign.

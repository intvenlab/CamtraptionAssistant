/*
 * Camtraptions BLE Battery Monitor – Seeed Studio XIAO nRF52840
 * Phase 3: Dual battery, camera I/O framework + state machine
 *
 * Setup Instructions:
 * 1. Install "Seeed nRF52 Boards" in Arduino IDE Board Manager
 * 2. Select "Seeed XIAO nRF52840" as your board
 * 3. Upload this sketch
 *
 * GATT UUIDs must match GattUuids object in Android AppScreen.kt.
 */

#include <bluefruit.h>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>

using namespace Adafruit_LittleFS_Namespace;

// ─── Pins ────────────────────────────────────────────────────────────────────
#define BATTERY_PIN          A0  // D0/A0 – Internal CR2032 power-supply ADC input
#define DEVICE_BATTERY_PIN   A1  // D1/A1 – Device/camera battery ADC input (primary gauge)
#define FP_IN_PIN            2   // D2    – FP/shutter input, FALLING interrupt
#define HP_IN_PIN            3   // D3    – HP input, FALLING interrupt
#define FP_OUT_PIN           4   // D4 – FP output to camera (open-drain)
#define HP_OUT_PIN           5   // D5 – HP output to camera (open-drain)

// ─── ADC ─────────────────────────────────────────────────────────────────────
#define VOLTAGE_DIVIDER_RATIO 1.0f
#define ADC_MAX_VALUE         1024.0f

// ─── Timing ──────────────────────────────────────────────────────────────────
#define ADVERTISING_DURATION_MS 50
#define SLEEP_INTERVAL_MS       1000
#define SHUTTER_DEBOUNCE_MS     100   // FP_IN ISR debounce (backward compat)

// ─── Flash ───────────────────────────────────────────────────────────────────
#define SETTINGS_FILE           "/settings.bin"
#define SETTINGS_VERSION        1
#define CAMERA_SETTINGS_FILE    "/camera.bin"
#define CAMERA_SETTINGS_VERSION 1

// ─── Device settings (layout unchanged – SETTINGS_VERSION stays at 1) ───────
// Bump SETTINGS_VERSION only if you add/remove/reorder fields here.
struct DeviceConfig {
  uint8_t  version;        // struct version guard
  uint8_t  configured;     // 0 = factory fresh, 1 = user-configured
  char     name[21];       // user-assigned device name, null-terminated
  uint8_t  groupId;        // 0 = no group, 1–255 = group membership
  char     groupName[21];  // shared group label, null-terminated
  uint8_t  deviceType;     // 0=battery_monitor, 1=camera, 2=strobe, 3=focus_light
  uint8_t  chemistry;      // 0=LiPo, 1=LiFePO4, 2=NiMH, 3=Alkaline
  uint8_t  cellCount;      // 1–8
  uint32_t shutterCount;   // camera shutter actuations (incremented by ISR)
};

// ─── Camera config (19 bytes, stored in /camera.bin) ─────────────────────────
// Guards with CAMERA_SETTINGS_VERSION; independent of DeviceConfig.
struct CameraConfig {
  uint8_t version;                      // CAMERA_SETTINGS_VERSION
  uint8_t enabled;                      // 0=disabled, 1=enabled
  uint8_t wakeHalfPressHoldSec;         // X seconds (default 10) – max HP hold before timeout
  uint8_t minHalfPressBeforeShutter;    // T ×100ms (default 5 → 0.5s) – AF settle time
  uint8_t shutterPulseDuration;         // ×10ms (default 10 → 100ms)
  uint8_t startFrameSpacingTenths;      // Y ×100ms (default 10 → 1.0s) between frames
  uint8_t postShutterHpHoldTenths;      // Z ×100ms (default 20 → 2.0s) HP hold after burst
  uint8_t hpDebounceMs;                 // default 35
  uint8_t fpDebounceMs;                 // default 20 (stored; ISR uses SHUTTER_DEBOUNCE_MS)
  uint8_t frameCount;                   // N frames per sequence (default 4, range 1–8)
  uint8_t maxSequenceCount;             // max sequences per activity (default 4, range 1–8)
  uint8_t wakeHoldRefreshPolicy;        // 0=extend 1=restart 2=ignoreWhileActive
  uint8_t halfPressDuringBurstPolicy;   // 0=independent
  uint8_t fullPressWithoutHpPolicy;     // 0=assertHpThenWait 1=ignoreFP
  uint8_t activityHalfPressHoldPolicy;  // 0=holdUntilActivityEnd
  uint8_t fpAfterMaxSeqCountPolicy;     // 0=ignoreUntilActivityEnd
  uint8_t inputActivePolarity;          // 0=activeLow 1=activeHigh
  uint8_t outputDriveMode;              // 0=openDrain 1=pushPull
  uint8_t powerSaveIdleMode;            // 0=disabled 1=enabled
};
// sizeof(CameraConfig) == 19  (verified: 1+18 bytes)

static DeviceConfig cfg;
static CameraConfig camCfg;
static File         cfgFile(InternalFS);
static File         camFile(InternalFS);

// ─── GATT Service & Characteristics ──────────────────────────────────────────
// 128-bit UUIDs – must match GattUuids object in Android AppScreen.kt

BLEService svc("ca500000-0000-0000-0000-000000000000");

BLECharacteristic chrName     ("ca500001-0000-0000-0000-000000000000");
BLECharacteristic chrGroupId  ("ca500002-0000-0000-0000-000000000000");
BLECharacteristic chrGroupName("ca500003-0000-0000-0000-000000000000");
BLECharacteristic chrDevType  ("ca500004-0000-0000-0000-000000000000");
BLECharacteristic chrChemistry("ca500005-0000-0000-0000-000000000000");
BLECharacteristic chrCellCount("ca500006-0000-0000-0000-000000000000");
BLECharacteristic chrShutter  ("ca500007-0000-0000-0000-000000000000"); // Read + Notify
BLECharacteristic chrReset    ("ca500008-0000-0000-0000-000000000000"); // Write only
BLECharacteristic chrFactory  ("ca500009-0000-0000-0000-000000000000"); // Write only
BLECharacteristic chrCamCfg   ("ca50000a-0000-0000-0000-000000000000"); // Camera config R/W

// ─── Runtime state ────────────────────────────────────────────────────────────
volatile bool     isConnected    = false;
volatile bool     settingsDirty  = false;  // shutter count changed in ISR
volatile bool     shutterUpdated = false;  // notify pending
volatile uint32_t lastShutterMs  = 0;

// Camera I/O ISR flags
volatile bool     fpPulseFlag    = false;  // FP_IN fired
volatile bool     hpPulseFlag    = false;  // HP_IN fired
volatile uint32_t lastHpMs       = 0;

// Camera state machine
enum CameraState {
  CAM_IDLE,
  CAM_WAKE_HP,
  CAM_BURST_ACTIVE,
  CAM_POST_BURST_HOLD
};

static CameraState cameraState        = CAM_IDLE;
static bool        cameraLogicActive  = false;
static uint32_t    cameraTimerMs      = 0;  // state entry / refresh timestamp
static uint8_t     framesFired        = 0;
static uint8_t     sequenceCount      = 0;
static uint32_t    nextFrameMs        = 0;  // when to fire next frame in burst
static uint32_t    fpOutReleaseMs     = 0;  // when to release FP_OUT (0 = idle)
static uint32_t    fullPressIgnoreUntilMs = 0; // reject FP triggers during burst window

// ─── Forward declarations ─────────────────────────────────────────────────────
void loadSettings();
void saveSettings();
void resetToDefaults();
void populateCharacteristics();
void setupGatt();

void loadCameraSettings();
void saveCameraSettings();
void resetCameraToDefaults();
void populateCameraCharacteristics();
void setupCameraGatt();

void advertiseData(int intPct, float intVoltage, uint8_t extPct, uint16_t extVoltMv);
float readBatteryVoltage();
bool  readDeviceBattery(int &pct, float &voltMv);
int   readCR2032Percentage(float voltage);  // internal CR2032 coin cell (A0)
int   readLiPoPercentage(float voltage);    // device/camera LiPo battery  (A1)

void assertPin(int pin);
void releasePin(int pin);
void processCameraLogic();

void onConnect   (uint16_t connHdl);
void onDisconnect(uint16_t connHdl, uint8_t reason);
void onShutterPulse();    // FP_IN ISR – state-machine mode (FALLING)
void onHpPulse();         // HP_IN ISR – state-machine mode (FALLING)
void onFpPassthrough();   // FP_IN ISR – pass-through mode  (CHANGE)
void onHpPassthrough();   // HP_IN ISR – pass-through mode  (CHANGE)

void onNameWrite     (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onGroupIdWrite  (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onGroupNameWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onDevTypeWrite  (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onChemWrite     (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onCellWrite     (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onResetWrite    (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onFactoryWrite  (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onCamCfgWrite   (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);

// ═════════════════════════════════════════════════════════════════════════════
// setup()
// ═════════════════════════════════════════════════════════════════════════════
void setup() {
  // Battery ADC pins
  pinMode(BATTERY_PIN,        INPUT);
  pinMode(DEVICE_BATTERY_PIN, INPUT);

  // LEDs off (active-low on XIAO nRF52840)
  pinMode(LED_RED,   OUTPUT); digitalWrite(LED_RED,   HIGH);
  pinMode(LED_GREEN, OUTPUT); digitalWrite(LED_GREEN, HIGH);
  pinMode(LED_BLUE,  OUTPUT); digitalWrite(LED_BLUE,  HIGH);

  // DC/DC converter for better efficiency; low-power CPU mode
  sd_power_dcdc_mode_set(NRF_POWER_DCDC_ENABLE);
  sd_power_mode_set(NRF_POWER_MODE_LOWPWR);

  // Load persisted settings from flash (must precede pin setup)
  InternalFS.begin();
  loadSettings();
  loadCameraSettings();

  // ── Camera device I/O ────────────────────────────────────────────────────
  if (cfg.deviceType == 1 /* CAMERA */) {
    pinMode(FP_IN_PIN, INPUT_PULLUP);
    pinMode(HP_IN_PIN, INPUT_PULLUP);

    if (camCfg.enabled) {
      // State-machine mode:
      //   FALLING-only interrupts feed the state machine.
      //   Outputs are open-drain: idle as INPUT (high-Z), asserted as OUTPUT LOW.
      attachInterrupt(digitalPinToInterrupt(FP_IN_PIN), onShutterPulse, FALLING);
      attachInterrupt(digitalPinToInterrupt(HP_IN_PIN), onHpPulse,      FALLING);
      pinMode(FP_OUT_PIN, INPUT);  // high-Z idle
      pinMode(HP_OUT_PIN, INPUT);  // high-Z idle
    } else {
      // Pass-through mode:
      //   CHANGE interrupts mirror pin state instantly (ISR-driven, no loop latency).
      //   Outputs are push-pull, initialized to match the current input state so
      //   there is no glitch at boot.
      //   FP_IN shutter counting still runs inside onFpPassthrough.
      pinMode(FP_OUT_PIN, OUTPUT);
      digitalWrite(FP_OUT_PIN, digitalRead(FP_IN_PIN));  // sync to current state
      pinMode(HP_OUT_PIN, OUTPUT);
      digitalWrite(HP_OUT_PIN, digitalRead(HP_IN_PIN));  // sync to current state
      attachInterrupt(digitalPinToInterrupt(FP_IN_PIN), onFpPassthrough, CHANGE);
      attachInterrupt(digitalPinToInterrupt(HP_IN_PIN), onHpPassthrough, CHANGE);
    }
  } else {
    // Non-camera device: only FP_IN is monitored for shutter counting.
    // HP_IN, FP_OUT, HP_OUT are not touched (remain at power-on INPUT default).
    pinMode(FP_IN_PIN, INPUT_PULLUP);
    attachInterrupt(digitalPinToInterrupt(FP_IN_PIN), onShutterPulse, FALLING);
  }

  // BLE – allow connections now (needed for GATT)
  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.autoConnLed(false);
  Bluefruit.setConnLedInterval(0);

  // Use stored name if configured, otherwise generic discoverable name
  Bluefruit.setName(cfg.configured && cfg.name[0] ? cfg.name : "Camtraptions Device");

  Bluefruit.Periph.setConnectCallback(onConnect);
  Bluefruit.Periph.setDisconnectCallback(onDisconnect);

  setupGatt();
  // loop() handles the first advertisement
}

// ═════════════════════════════════════════════════════════════════════════════
// loop()
// ═════════════════════════════════════════════════════════════════════════════
void loop() {
  if (isConnected) {
    // Stay awake while a phone is connected.
    if (shutterUpdated) {
      shutterUpdated = false;
      chrShutter.notify32(cfg.shutterCount);
    }
    delay(50);
    return;
  }

  // ── Camera state machine (camera device type only) ────────────────────────
  if (cfg.deviceType == 1 /* CAMERA */ && camCfg.enabled) {
    processCameraLogic();
  }

  // Sleep guard: don't advertise or sleep while camera logic is running
  if (cameraLogicActive) {
    delay(5);  // yield briefly to avoid busy-loop
    return;
  }

  // ── Advertisement cycle (not connected, camera idle or non-camera) ─────────
  float intVoltage = readBatteryVoltage();
  int   intPct     = readCR2032Percentage(intVoltage);

  int   extPctInt = -1;
  float extVoltMvF = 0.0f;
  bool  extPresent = readDeviceBattery(extPctInt, extVoltMvF);
  uint8_t  extBatPct = extPresent ? (uint8_t)extPctInt    : 0xFF;
  uint16_t extBatMv  = extPresent ? (uint16_t)extVoltMvF  : 0xFFFF;

  advertiseData(intPct, intVoltage, extBatPct, extBatMv);
  delay(ADVERTISING_DURATION_MS);
  Bluefruit.Advertising.stop();

  // Flush any pending flash write (shutter count incremented by ISR)
  if (settingsDirty) {
    saveSettings();
    settingsDirty = false;
  }

  delay(SLEEP_INTERVAL_MS - ADVERTISING_DURATION_MS);
}

// ═════════════════════════════════════════════════════════════════════════════
// Flash storage – DeviceConfig
// ═════════════════════════════════════════════════════════════════════════════
void loadSettings() {
  memset(&cfg, 0, sizeof(cfg));
  cfg.version   = SETTINGS_VERSION;
  cfg.cellCount = 1;

  if (!InternalFS.exists(SETTINGS_FILE)) return;

  if (cfgFile.open(SETTINGS_FILE, FILE_O_READ)) {
    cfgFile.read(&cfg, sizeof(cfg));
    cfgFile.close();
  }

  if (cfg.version != SETTINGS_VERSION) {
    memset(&cfg, 0, sizeof(cfg));
    cfg.version   = SETTINGS_VERSION;
    cfg.cellCount = 1;
  }
}

void saveSettings() {
  InternalFS.remove(SETTINGS_FILE);
  if (cfgFile.open(SETTINGS_FILE, FILE_O_WRITE)) {
    cfgFile.write((const uint8_t*)&cfg, sizeof(cfg));
    cfgFile.close();
  }
}

void resetToDefaults() {
  memset(&cfg, 0, sizeof(cfg));
  cfg.version   = SETTINGS_VERSION;
  cfg.cellCount = 1;
  InternalFS.remove(SETTINGS_FILE);
}

// ═════════════════════════════════════════════════════════════════════════════
// Flash storage – CameraConfig
// ═════════════════════════════════════════════════════════════════════════════
void resetCameraToDefaults() {
  memset(&camCfg, 0, sizeof(camCfg));
  camCfg.version                   = CAMERA_SETTINGS_VERSION;
  camCfg.enabled                   = 0;
  camCfg.wakeHalfPressHoldSec      = 10;
  camCfg.minHalfPressBeforeShutter = 5;
  camCfg.shutterPulseDuration      = 10;
  camCfg.startFrameSpacingTenths   = 10;
  camCfg.postShutterHpHoldTenths   = 20;
  camCfg.hpDebounceMs              = 35;
  camCfg.fpDebounceMs              = 20;
  camCfg.frameCount                = 4;
  camCfg.maxSequenceCount          = 4;
  // all policy and mode fields default to 0
}

void loadCameraSettings() {
  resetCameraToDefaults();
  if (!InternalFS.exists(CAMERA_SETTINGS_FILE)) return;
  if (camFile.open(CAMERA_SETTINGS_FILE, FILE_O_READ)) {
    camFile.read(&camCfg, sizeof(camCfg));
    camFile.close();
  }
  if (camCfg.version != CAMERA_SETTINGS_VERSION) resetCameraToDefaults();
}

void saveCameraSettings() {
  InternalFS.remove(CAMERA_SETTINGS_FILE);
  if (camFile.open(CAMERA_SETTINGS_FILE, FILE_O_WRITE)) {
    camFile.write((const uint8_t*)&camCfg, sizeof(camCfg));
    camFile.close();
  }
}

// ═════════════════════════════════════════════════════════════════════════════
// GATT setup
// ═════════════════════════════════════════════════════════════════════════════

void populateCharacteristics() {
  size_t nameLen      = strlen(cfg.name);
  size_t groupNameLen = strlen(cfg.groupName);

  chrName.write(cfg.name, nameLen > 0 ? nameLen : 1);
  chrGroupId.write8(cfg.groupId);
  chrGroupName.write(cfg.groupName, groupNameLen > 0 ? groupNameLen : 1);
  chrDevType.write8(cfg.deviceType);
  chrChemistry.write8(cfg.chemistry);
  chrCellCount.write8(cfg.cellCount);
  chrShutter.write32(cfg.shutterCount);
}

void populateCameraCharacteristics() {
  chrCamCfg.write((const uint8_t*)&camCfg, sizeof(camCfg));
}

void setupCameraGatt() {
  // ── Camera Config (R/W, fixed 19 bytes) ──────────────────────────────────
  chrCamCfg.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  chrCamCfg.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  chrCamCfg.setFixedLen(sizeof(CameraConfig));
  chrCamCfg.setWriteCallback(onCamCfgWrite);
  chrCamCfg.begin();
}

void setupGatt() {
  svc.begin();

  // ── Device Name (R/W, up to 20 UTF-8 bytes) ──────────────────────────────
  chrName.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  chrName.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  chrName.setMaxLen(20);
  chrName.setWriteCallback(onNameWrite);
  chrName.begin();

  // ── Group ID (R/W, 1 byte: 0=no group, 1–255=group) ──────────────────────
  chrGroupId.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  chrGroupId.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  chrGroupId.setFixedLen(1);
  chrGroupId.setWriteCallback(onGroupIdWrite);
  chrGroupId.begin();

  // ── Group Name (R/W, up to 20 bytes) ─────────────────────────────────────
  chrGroupName.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  chrGroupName.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  chrGroupName.setMaxLen(20);
  chrGroupName.setWriteCallback(onGroupNameWrite);
  chrGroupName.begin();

  // ── Device Type (R/W, 1 byte) ─────────────────────────────────────────────
  chrDevType.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  chrDevType.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  chrDevType.setFixedLen(1);
  chrDevType.setWriteCallback(onDevTypeWrite);
  chrDevType.begin();

  // ── Battery Chemistry (R/W, 1 byte) ──────────────────────────────────────
  chrChemistry.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  chrChemistry.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  chrChemistry.setFixedLen(1);
  chrChemistry.setWriteCallback(onChemWrite);
  chrChemistry.begin();

  // ── Cell Count (R/W, 1 byte: 1–8) ────────────────────────────────────────
  chrCellCount.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE);
  chrCellCount.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  chrCellCount.setFixedLen(1);
  chrCellCount.setWriteCallback(onCellWrite);
  chrCellCount.begin();

  // ── Shutter Count (Read + Notify, 4 bytes little-endian uint32) ──────────
  chrShutter.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chrShutter.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chrShutter.setFixedLen(4);
  chrShutter.begin();

  // ── Reset Shutter Count (Write only; write 0x01 to reset to 0) ───────────
  chrReset.setProperties(CHR_PROPS_WRITE);
  chrReset.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chrReset.setFixedLen(1);
  chrReset.setWriteCallback(onResetWrite);
  chrReset.begin();

  // ── Factory Reset (Write only; write 0x01 to clear flash) ────────────────
  chrFactory.setProperties(CHR_PROPS_WRITE);
  chrFactory.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chrFactory.setFixedLen(1);
  chrFactory.setWriteCallback(onFactoryWrite);
  chrFactory.begin();

  // ── Camera Config (R/W, 19 bytes) ────────────────────────────────────────
  setupCameraGatt();

  // Seed characteristics with values loaded from flash
  populateCharacteristics();
  populateCameraCharacteristics();
}

// ═════════════════════════════════════════════════════════════════════════════
// Advertising – 13-byte manufacturer-specific packet
// Offset after company ID (Android data[]):
//   [0]    Internal battery %
//   [1-2]  Internal voltage mV LE
//   [3]    External battery % (0xFF = not present)
//   [4-5]  External voltage mV LE (0xFFFF = not present)
//   [6]    Flags (configured | deviceType | chemistry)
//   [7]    Group ID
//   [8]    Cell count
//   [9-10] Shutter count LE uint16
// ═════════════════════════════════════════════════════════════════════════════
void advertiseData(int intPct, float intVoltage, uint8_t extPct, uint16_t extVoltMv) {
  Bluefruit.Advertising.stop();
  Bluefruit.Advertising.clearData();
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);

  uint8_t flags = 0;
  if (cfg.configured)           flags |= 0x01;
  flags |= (cfg.deviceType & 0x03) << 1;
  flags |= (cfg.chemistry  & 0x03) << 3;

  uint16_t intVoltMv = (uint16_t)(intVoltage * 1000.0f);

  uint8_t mfgData[13];
  mfgData[0]  = 0xFF;                              // Company ID low
  mfgData[1]  = 0xFF;                              // Company ID high
  mfgData[2]  = (uint8_t)intPct;                  // Internal battery %
  mfgData[3]  =  intVoltMv       & 0xFF;           // Internal voltage low
  mfgData[4]  = (intVoltMv >> 8) & 0xFF;           // Internal voltage high
  mfgData[5]  = extPct;                             // External battery % (0xFF=N/A)
  mfgData[6]  =  extVoltMv       & 0xFF;           // External voltage low
  mfgData[7]  = (extVoltMv >> 8) & 0xFF;           // External voltage high
  mfgData[8]  = flags;                              // Flags
  mfgData[9]  = cfg.groupId;                       // Group ID
  mfgData[10] = cfg.cellCount;                     // Cell count
  mfgData[11] =  cfg.shutterCount        & 0xFF;   // Shutter count low
  mfgData[12] = (cfg.shutterCount >> 8)  & 0xFF;   // Shutter count high

  Bluefruit.Advertising.addData(BLE_GAP_AD_TYPE_MANUFACTURER_SPECIFIC_DATA, mfgData, 13);

  Bluefruit.ScanResponse.clearData();
  Bluefruit.ScanResponse.addName();

  Bluefruit.Advertising.setInterval(32, 32);
  Bluefruit.Advertising.restartOnDisconnect(false);
  Bluefruit.Advertising.start(0);
}

// ═════════════════════════════════════════════════════════════════════════════
// BLE connection callbacks
// ═════════════════════════════════════════════════════════════════════════════
void onConnect(uint16_t connHdl) {
  (void)connHdl;
  isConnected = true;
}

void onDisconnect(uint16_t connHdl, uint8_t reason) {
  (void)connHdl; (void)reason;
  isConnected = false;
}

// ═════════════════════════════════════════════════════════════════════════════
// GATT write callbacks
// ═════════════════════════════════════════════════════════════════════════════
static void markConfigured() { cfg.configured = 1; }

void onNameWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  uint16_t len = (l < 20) ? l : 20;
  memcpy(cfg.name, d, len);
  cfg.name[len] = '\0';
  markConfigured();
  saveSettings();
  Bluefruit.setName(cfg.name);
}

void onGroupIdWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 1) return;
  cfg.groupId = d[0];
  markConfigured();
  saveSettings();
}

void onGroupNameWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  uint16_t len = (l < 20) ? l : 20;
  memcpy(cfg.groupName, d, len);
  cfg.groupName[len] = '\0';
  markConfigured();
  saveSettings();
}

void onDevTypeWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 1) return;
  cfg.deviceType = d[0];
  markConfigured();
  saveSettings();
}

void onChemWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 1) return;
  cfg.chemistry = d[0];
  markConfigured();
  saveSettings();
}

void onCellWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 1) return;
  uint8_t val = d[0];
  if (val < 1) val = 1;
  if (val > 8) val = 8;
  cfg.cellCount = val;
  markConfigured();
  saveSettings();
}

void onResetWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 1 || d[0] != 0x01) return;
  cfg.shutterCount = 0;
  saveSettings();
  chrShutter.notify32(0);
}

void onFactoryWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 1 || d[0] != 0x01) return;
  resetToDefaults();
  populateCharacteristics();
  Bluefruit.setName("Camtraptions Device");
}

void onCamCfgWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < sizeof(camCfg)) return;
  memcpy(&camCfg, d, sizeof(camCfg));
  camCfg.version = CAMERA_SETTINGS_VERSION;  // enforce version
  // Clamp critical range fields
  if (camCfg.frameCount < 1)       camCfg.frameCount = 1;
  if (camCfg.frameCount > 8)       camCfg.frameCount = 8;
  if (camCfg.maxSequenceCount < 1) camCfg.maxSequenceCount = 1;
  if (camCfg.maxSequenceCount > 8) camCfg.maxSequenceCount = 8;
  saveCameraSettings();
}

// ═════════════════════════════════════════════════════════════════════════════
// FP_IN ISR (shutter pulse) – fires on FALLING edge of FP_IN_PIN
// Debounced in software. Does NOT call BLE stack (not ISR-safe).
// ═════════════════════════════════════════════════════════════════════════════
void onShutterPulse() {
  uint32_t now = millis();
  if (now - lastShutterMs < SHUTTER_DEBOUNCE_MS) return;
  lastShutterMs  = now;
  cfg.shutterCount++;
  settingsDirty  = true;
  shutterUpdated = true;
  fpPulseFlag    = true;  // camera logic consumes this in main loop
}

// ═════════════════════════════════════════════════════════════════════════════
// HP_IN ISR – fires on FALLING edge of HP_IN_PIN (state-machine mode)
// ═════════════════════════════════════════════════════════════════════════════
void onHpPulse() {
  uint32_t now = millis();
  if (now - lastHpMs < camCfg.hpDebounceMs) return;
  lastHpMs    = now;
  hpPulseFlag = true;
}

// ═════════════════════════════════════════════════════════════════════════════
// Pass-through ISRs – used when camCfg.enabled == 0 (CHANGE trigger).
// Outputs are pre-configured as OUTPUT in setup(), so digitalWrite() is safe
// here without any pinMode() call.
// FP: also counts shutter actuations on the active (LOW) edge.
// ═════════════════════════════════════════════════════════════════════════════
void onFpPassthrough() {
  int state = digitalRead(FP_IN_PIN);
  digitalWrite(FP_OUT_PIN, state);          // mirror immediately

  if (state == LOW) {                        // active edge only
    uint32_t now = millis();
    if (now - lastShutterMs >= SHUTTER_DEBOUNCE_MS) {
      lastShutterMs  = now;
      cfg.shutterCount++;
      settingsDirty  = true;
      shutterUpdated = true;
    }
  }
}

void onHpPassthrough() {
  digitalWrite(HP_OUT_PIN, digitalRead(HP_IN_PIN));  // mirror immediately
}

// ═════════════════════════════════════════════════════════════════════════════
// Open-drain output helpers
// Assert: drive LOW. Release: float (INPUT / high-Z).
// ═════════════════════════════════════════════════════════════════════════════
void assertPin(int pin) {
  pinMode(pin, OUTPUT);
  digitalWrite(pin, LOW);
}

void releasePin(int pin) {
  pinMode(pin, INPUT);
}

// ═════════════════════════════════════════════════════════════════════════════
// Camera logic state machine – called from loop() when deviceType==CAMERA
// ═════════════════════════════════════════════════════════════════════════════
void processCameraLogic() {
  uint32_t now = millis();

  // Consume ISR flags atomically
  bool hpTrig = false, fpTrig = false;
  noInterrupts();
  if (hpPulseFlag) { hpPulseFlag = false; hpTrig = true; }
  if (fpPulseFlag) { fpPulseFlag = false; fpTrig = true; }
  interrupts();

  // Suppress FP triggers during burst ignore window
  if (fpTrig && (int32_t)(fullPressIgnoreUntilMs - now) > 0) {
    fpTrig = false;
  }

  switch (cameraState) {

    // ── IDLE ──────────────────────────────────────────────────────────────────
    case CAM_IDLE:
      cameraLogicActive = false;

      // Power-save: skip processing and let main loop sleep normally
      if (camCfg.powerSaveIdleMode) return;

      if (hpTrig || (fpTrig && camCfg.fullPressWithoutHpPolicy == 0)) {
        assertPin(HP_OUT_PIN);
        cameraTimerMs = now;
        sequenceCount = 0;
        cameraState   = CAM_WAKE_HP;
        cameraLogicActive = true;
      }
      break;

    // ── WAKE_HP ───────────────────────────────────────────────────────────────
    case CAM_WAKE_HP:
      cameraLogicActive = true;

      // Refresh timer on new HP trigger per policy
      if (hpTrig) {
        switch (camCfg.wakeHoldRefreshPolicy) {
          case 0: cameraTimerMs = now; break;  // extend
          case 1: cameraTimerMs = now; break;  // restart
          case 2: break;                        // ignoreWhileActive
        }
      }

      // Timeout: release HP and return to idle if max hold exceeded
      if (now - cameraTimerMs >= (uint32_t)camCfg.wakeHalfPressHoldSec * 1000UL) {
        releasePin(HP_OUT_PIN);
        cameraState       = CAM_IDLE;
        cameraLogicActive = false;
        sequenceCount     = 0;
        break;
      }

      // Check if min HP hold time has elapsed before allowing burst
      if (now - cameraTimerMs < (uint32_t)camCfg.minHalfPressBeforeShutter * 100UL) break;

      // Sequence limit reached?
      if (camCfg.maxSequenceCount > 0 && sequenceCount >= camCfg.maxSequenceCount) {
        // fpAfterMaxSeqCountPolicy == 0: ignore until timeout
        break;
      }

      // Transition to burst
      {
        sequenceCount++;
        framesFired = 0;

        // Compute burst ignore window: cover all frame pulses + spacings
        uint32_t burstWindowMs =
            (uint32_t)(camCfg.frameCount - 1) * (uint32_t)camCfg.startFrameSpacingTenths * 100UL
          + (uint32_t)camCfg.shutterPulseDuration * 10UL;
        fullPressIgnoreUntilMs = now + burstWindowMs;

        // Fire first frame
        assertPin(FP_OUT_PIN);
        fpOutReleaseMs = now + (uint32_t)camCfg.shutterPulseDuration * 10UL;
        nextFrameMs    = fpOutReleaseMs + (uint32_t)camCfg.startFrameSpacingTenths * 100UL;
        framesFired    = 1;

        cameraTimerMs = now;  // reuse timer for post-burst tracking
        cameraState   = CAM_BURST_ACTIVE;
      }
      break;

    // ── BURST_ACTIVE ──────────────────────────────────────────────────────────
    case CAM_BURST_ACTIVE:
      cameraLogicActive = true;

      // Release FP_OUT after pulse duration
      if (fpOutReleaseMs != 0 && now >= fpOutReleaseMs) {
        releasePin(FP_OUT_PIN);
        fpOutReleaseMs = 0;
      }

      // Fire subsequent frames
      if (framesFired < camCfg.frameCount && now >= nextFrameMs) {
        assertPin(FP_OUT_PIN);
        fpOutReleaseMs = now + (uint32_t)camCfg.shutterPulseDuration * 10UL;
        nextFrameMs    = fpOutReleaseMs + (uint32_t)camCfg.startFrameSpacingTenths * 100UL;
        framesFired++;
      }

      // All frames fired and FP_OUT released → post-burst hold
      if (framesFired >= camCfg.frameCount && fpOutReleaseMs == 0) {
        cameraTimerMs = now;
        cameraState   = CAM_POST_BURST_HOLD;
      }
      break;

    // ── POST_BURST_HOLD ───────────────────────────────────────────────────────
    case CAM_POST_BURST_HOLD:
      cameraLogicActive = true;

      if (now - cameraTimerMs >= (uint32_t)camCfg.postShutterHpHoldTenths * 100UL) {
        releasePin(HP_OUT_PIN);
        cameraState       = CAM_IDLE;
        cameraLogicActive = false;
      }
      break;
  }
}

// ═════════════════════════════════════════════════════════════════════════════
// Battery reading – internal
// ═════════════════════════════════════════════════════════════════════════════
float readBatteryVoltage() {
  analogReference(AR_INTERNAL_3_0);
  analogReadResolution(10);
  int total = 0;
  for (int i = 0; i < 5; i++) {
    total += analogRead(BATTERY_PIN);
    delayMicroseconds(100);
  }
  return ((float)(total / 5) / ADC_MAX_VALUE) * 3.6f * VOLTAGE_DIVIDER_RATIO;
}

// ═════════════════════════════════════════════════════════════════════════════
// Battery reading – device/camera battery (A1)
// Returns true if a battery is detected (voltage >= 0.5V).
// On return: pct = 0–100, voltMv = millivolts.
// ═════════════════════════════════════════════════════════════════════════════
bool readDeviceBattery(int &pct, float &voltMv) {
  analogReference(AR_INTERNAL_3_0);
  analogReadResolution(10);
  int total = 0;
  for (int i = 0; i < 5; i++) {
    total += analogRead(DEVICE_BATTERY_PIN);
    delayMicroseconds(100);
  }
  float voltage = ((float)(total / 5) / ADC_MAX_VALUE) * 3.6f * VOLTAGE_DIVIDER_RATIO;
  if (voltage < 0.5f) return false;  // not present
  voltMv = voltage * 1000.0f;
  pct    = readLiPoPercentage(voltage);
  return true;
}

// ═════════════════════════════════════════════════════════════════════════════
// CR2032 coin cell percentage (internal battery, A0)
// Nominal: 3.0 V full, 2.5 V depleted.
// Discharge is very flat; piecewise linear approximation:
//   ≥3.0 V → 100 %   2.9 V → 80 %   2.8 V → 50 %   2.7 V → 20 %   ≤2.5 V → 0 %
// ═════════════════════════════════════════════════════════════════════════════
int readCR2032Percentage(float voltage) {
  if (voltage >= 3.0f) return 100;
  if (voltage <= 2.5f) return 0;

  float pct;
  if      (voltage >= 2.9f) pct = 80.0f + (voltage - 2.9f) / 0.1f * 20.0f;  // 2.9–3.0 V → 80–100 %
  else if (voltage >= 2.8f) pct = 50.0f + (voltage - 2.8f) / 0.1f * 30.0f;  // 2.8–2.9 V → 50–80 %
  else if (voltage >= 2.7f) pct = 20.0f + (voltage - 2.7f) / 0.1f * 30.0f;  // 2.7–2.8 V → 20–50 %
  else                      pct =          (voltage - 2.5f) / 0.2f * 20.0f;  // 2.5–2.7 V →  0–20 %

  int result = (int)pct;
  if (result > 100) result = 100;
  if (result < 0)   result = 0;
  return result;
}

// ═════════════════════════════════════════════════════════════════════════════
// LiPo percentage (external camera battery, A2)
// Operating range 3.0–4.2 V; piecewise linear approximation.
// ═════════════════════════════════════════════════════════════════════════════
int readLiPoPercentage(float voltage) {
  if (voltage > 4.2f) voltage = 4.2f;
  if (voltage < 3.0f) voltage = 3.0f;

  float pct;
  if      (voltage >= 4.1f) pct = 90.0f + (voltage - 4.1f) * 100.0f;
  else if (voltage >= 3.9f) pct = 70.0f + (voltage - 3.9f) * 100.0f;
  else if (voltage >= 3.7f) pct = 40.0f + (voltage - 3.7f) * 150.0f;
  else if (voltage >= 3.5f) pct = 20.0f + (voltage - 3.5f) * 100.0f;
  else if (voltage >= 3.3f) pct =  5.0f + (voltage - 3.3f) *  75.0f;
  else                      pct =          (voltage - 3.0f) *  16.67f;

  int result = (int)pct;
  if (result > 100) result = 100;
  if (result < 0)   result = 0;
  return result;
}

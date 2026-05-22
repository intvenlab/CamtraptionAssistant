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

// Uncomment to emit state-machine pin transition logs over USB serial.
// #define DEBUG_CAMERA_LOGIC_PINS

// ─── Pins ────────────────────────────────────────────────────────────────────
#define BATTERY_PIN          A0  // D0/A0 – Internal CR2032 power-supply ADC input
#define DEVICE_BATTERY_PIN   A1  // D1/A1 – Device/camera battery ADC input (primary gauge)
#define FP_IN_PIN            2   // D2    – FP/shutter input, FALLING interrupt
#define HP_IN_PIN            3   // D3    – HP input, FALLING interrupt
#define FP_OUT_PIN           4   // D4 – FP output to camera (open-drain)
#define HP_OUT_PIN           5   // D5 – HP output to camera (open-drain)

// ─── ADC ─────────────────────────────────────────────────────────────────────
#define ADC_MAX_VALUE          1024.0f
#define ADC_REFERENCE_VOLTAGE  3.0f    // Matches AR_INTERNAL_3_0
// External battery (A1) uses a 50kΩ / 10kΩ divider: ratio = (50k+10k)/10k = 6.0
#define EXT_BATT_DIVIDER_RATIO 6.0f

// ─── Timing ──────────────────────────────────────────────────────────────────
#define ADVERTISING_DURATION_MS 50
#define SLEEP_INTERVAL_MS       1000
#define SHUTTER_DEBOUNCE_MS     100   // FP_IN ISR debounce (backward compat)

// ─── Flash ───────────────────────────────────────────────────────────────────
#define SETTINGS_FILE           "/settings.bin"
#define SETTINGS_VERSION        1
#define CAMERA_SETTINGS_FILE    "/camera.bin"
#define CAMERA_SETTINGS_VERSION 2
#define TELEMETRY_FILE          "/telemetry.bin"
#define TELEMETRY_VERSION       1
#define CAL_FILE                "/cal.bin"
#define CALIBRATION_VERSION     1
#define INT_CAL_FILE            "/int_cal.bin"
#define BEACON_LAYOUT_VERSION   2
#define TELEMETRY_FLUSH_INTERVAL_MS 60000UL

// Connected-mode scheduler:
// camera control-plane work gets deterministic priority; BLE management-plane
// work runs only within a bounded leftover budget.
#define CONNECTED_ACTIVE_TELEMETRY_MIN_INTERVAL_MS 200UL  // 5 Hz max when timing-sensitive
#define CONNECTED_POST_TELEMETRY_MIN_INTERVAL_MS   100UL  // 10 Hz catch-up after activity
#define CONNECTED_IDLE_TELEMETRY_MIN_INTERVAL_MS    75UL
#define CONNECTED_POST_MODE_WINDOW_MS              1500UL
#define CONNECTED_ACTIVE_BLE_BUDGET_US              500UL
#define CONNECTED_POST_BLE_BUDGET_US               2500UL
#define CONNECTED_IDLE_BLE_BUDGET_US               3500UL

constexpr uint8_t parseTwoDigits(char tens, char ones) {
  return (uint8_t)((tens - '0') * 10 + (ones - '0'));
}

constexpr uint16_t parseFourDigits(char a, char b, char c, char d) {
  return (uint16_t)((a - '0') * 1000 + (b - '0') * 100 + (c - '0') * 10 + (d - '0'));
}

constexpr uint8_t parseBuildMonth(const char* dateStr) {
  return (dateStr[0] == 'J' && dateStr[1] == 'a') ? 1  : // Jan
         (dateStr[0] == 'F')                           ? 2  : // Feb
         (dateStr[0] == 'M' && dateStr[2] == 'r')      ? 3  : // Mar
         (dateStr[0] == 'A' && dateStr[1] == 'p')      ? 4  : // Apr
         (dateStr[0] == 'M' && dateStr[2] == 'y')      ? 5  : // May
         (dateStr[0] == 'J' && dateStr[2] == 'n')      ? 6  : // Jun
         (dateStr[0] == 'J' && dateStr[2] == 'l')      ? 7  : // Jul
         (dateStr[0] == 'A' && dateStr[1] == 'u')      ? 8  : // Aug
         (dateStr[0] == 'S')                           ? 9  : // Sep
         (dateStr[0] == 'O')                           ? 10 : // Oct
         (dateStr[0] == 'N')                           ? 11 : // Nov
                                                        12;   // Dec
}

constexpr uint8_t parseBuildDay(const char* dateStr) {
  return (dateStr[4] == ' ')
    ? (uint8_t)(dateStr[5] - '0')
    : parseTwoDigits(dateStr[4], dateStr[5]);
}

constexpr uint16_t BUILD_YEAR  = parseFourDigits(__DATE__[7], __DATE__[8], __DATE__[9], __DATE__[10]);
constexpr uint8_t  BUILD_MONTH = parseBuildMonth(__DATE__);
constexpr uint8_t  BUILD_DAY   = parseBuildDay(__DATE__);
constexpr uint8_t  BUILD_HOUR  = parseTwoDigits(__TIME__[0], __TIME__[1]);
constexpr uint8_t  BUILD_MIN   = parseTwoDigits(__TIME__[3], __TIME__[4]);
constexpr uint8_t  BUILD_SEC   = parseTwoDigits(__TIME__[6], __TIME__[7]);

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

// ─── Camera config (20 bytes, stored in /camera.bin) ─────────────────────────
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
  uint8_t fpDebounceMs;                 // default 20
  uint8_t frameCount;                   // N frames per sequence (default 4, range 1–8)
  uint8_t maxSequenceCount;             // max sequences per activity (default 4, range 1–8)
  uint8_t wakeHoldRefreshPolicy;        // 0=extend 1=restart 2=ignoreWhileActive
  uint8_t halfPressDuringBurstPolicy;   // 0=independent
  uint8_t fullPressWithoutHpPolicy;     // 0=assertHpThenWait 1=ignoreFP
  uint8_t activityHalfPressHoldPolicy;  // 0=holdUntilActivityEnd
  uint8_t fpAfterMaxSeqCountPolicy;     // 0=ignoreUntilActivityEnd
  uint8_t inputActivePolarity;          // 0=activeLow 1=activeHigh
  uint8_t outputDriveMode;              // 0=openDrain 1=pushPull
  uint8_t powerSaveIdleMode;            // 0=disabled 1=enabled (default)
  uint8_t fullPressIgnoreGapTenths;     // R10 ×100ms (default 31 → 3.1s)
};
// sizeof(CameraConfig) == 20  (verified: 1+19 bytes)

enum TelemetryEvent : uint8_t {
  TEL_EVT_NONE = 0,
  TEL_EVT_HP_WAKE = 1,
  TEL_EVT_HP_REFRESH = 2,
  TEL_EVT_FP_ACCEPTED = 3,
  TEL_EVT_WAKE_TIMEOUT = 4,
  TEL_EVT_FP_REJECT_GAP = 5,
  TEL_EVT_FP_REJECT_CAP = 6,
  TEL_EVT_BURST_COMPLETE = 7,
  TEL_EVT_ACTIVITY_END = 8,
  TEL_EVT_COLD_FP = 9,
  TEL_EVT_HP_IGNORED_BURST = 10,
  TEL_EVT_FP_DEBOUNCE_REJECT = 11,
  TEL_EVT_HP_DEBOUNCE_REJECT = 12,
  TEL_EVT_FP_ACCEPTED_AT_GAP_BOUNDARY = 13
};

enum TelemetryScenarioHint : uint8_t {
  TEL_SC_NONE = 0,
  TEL_SC_WAKE_TIMEOUT = 1,     // SC-04 / SC-04b / SC-12
  TEL_SC_FP_GAP_IGNORE = 2,    // SC-02 / SC-03 / SC-14
  TEL_SC_COLD_FP = 3,          // SC-06 / SC-08
  TEL_SC_SEQUENCE_CAP = 4,     // SC-09 / SC-10
  TEL_SC_HP_DURING_BURST = 5,  // SC-07 / SC-18
  TEL_SC_DEBOUNCE = 6          // SC-13
};

// Coarse lifetime counters persisted separately from user settings.
struct CameraTelemetryCounters {
  uint8_t  version;
  uint8_t  reserved[3];
  uint32_t wakeTimeoutCount;
  uint32_t acceptedFpCount;
  uint32_t ignoredFpDuringGapCount;
  uint32_t ignoredFpDuringBurstCount;
  uint32_t rejectedFpAtSequenceCapCount;
  uint32_t coldFpSequenceCount;
  uint32_t hpRefreshCount;
  uint32_t hpIgnoredDuringBurstCount;
  uint32_t fpDebounceRejectCount;
  uint32_t hpDebounceRejectCount;
  uint32_t sequenceCompletedCount;
  uint32_t activityCompletedCount;
};

// BLE snapshot: live state + persisted counters, little-endian on nRF52.
struct CameraTelemetryPayload {
  uint8_t  version;
  uint8_t  cameraState;
  uint8_t  flags;  // bit0 activityActive, bit1 hpOutAsserted
  uint8_t  framesFiredThisSequence;
  uint8_t  sequencesStartedThisActivity;
  uint8_t  lastEventCode;
  uint8_t  lastScenarioHint;
  uint8_t  reserved;
  uint32_t msUntilWakeDeadline;
  uint32_t msUntilFpIgnoreClear;
  uint32_t msUntilNextFrame;
  uint32_t msUntilPostHoldEnd;
  CameraTelemetryCounters counters;
};

struct CalibrationConfig {
  uint8_t version;       // CALIBRATION_VERSION
  uint8_t reserved[3];
  float   dividerRatio;  // persisted ratio; default EXT_BATT_DIVIDER_RATIO
};

struct IntCalibrationConfig {
  uint8_t version;      // CALIBRATION_VERSION
  uint8_t reserved[3];
  float   scaleFactor;  // multiplier on raw voltage; default 1.0
};

static DeviceConfig cfg;
static CameraConfig camCfg;
static CameraTelemetryCounters telCounters;
static CameraTelemetryPayload  telPayload;
static File         cfgFile(InternalFS);
static File         camFile(InternalFS);
static File         telFile(InternalFS);
static File         calFile(InternalFS);
static File         intCalFile(InternalFS);

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
BLECharacteristic chrTelemetry("ca50000b-0000-0000-0000-000000000000"); // Camera telemetry R + Notify
BLECharacteristic chrCalSet   ("ca50000c-0000-0000-0000-000000000000"); // Write only
BLECharacteristic chrIntCalSet("ca50000d-0000-0000-0000-000000000000"); // Write only

// ─── Runtime state ────────────────────────────────────────────────────────────
static float             deviceDividerRatio = EXT_BATT_DIVIDER_RATIO;  // effective ratio
static volatile uint16_t pendingCalTargetMv = 0;    // mV written by app; 0 = reset
static volatile bool     pendingCalApply    = false;

static float             intScaleFactor       = 1.0f;
static float             lastRawIntVoltage    = 0.0f;  // unscaled A0 reading; set by readBatteryVoltage()
static volatile uint16_t pendingIntCalTargetMv = 0;
static volatile bool     pendingIntCalApply    = false;

volatile bool     isConnected    = false;
volatile bool     settingsDirty  = false;  // shutter count changed in ISR
volatile bool     shutterUpdated = false;  // notify pending
volatile bool     telemetryDirty  = false;  // persisted telemetry changed
volatile bool     telemetryUpdated = false; // notify pending
volatile uint32_t lastShutterMs  = 0;
static uint32_t   nextTelemetryFlushMs = 0;
static uint8_t    lastTelemetryEvent = TEL_EVT_NONE;
static uint8_t    lastTelemetryScenario = TEL_SC_NONE;

// Camera I/O ISR flags
volatile bool     fpPulseFlag    = false;  // FP_IN fired
volatile bool     hpPulseFlag    = false;  // HP_IN fired
volatile uint32_t lastHpMs       = 0;

// Camera state machine
enum CameraState {
  CAM_IDLE,
  CAM_WAKE_AF,
  CAM_COLD_FP_WAIT,
  CAM_BURST_ACTIVE,
  CAM_POST_SHUTTER_EXT
};

static CameraState cameraState              = CAM_IDLE;
static bool        cameraLogicActive        = false;
static bool        activityActive           = false;
static bool        hpOutAsserted            = false;
static bool        coldFpAcceptPending      = false;
static uint8_t     framesFired              = 0;
static uint8_t     sequencesStartedThisActivity = 0;
static uint32_t    hpAssertedMs             = 0;
static uint32_t    wakeHoldDeadlineMs       = 0;
static uint32_t    sequenceStartMs          = 0;
static uint32_t    nextFrameMs              = 0;  // next FP_OUT rising edge target
static uint32_t    lastFpOutStartMs         = 0;
static uint32_t    fpOutReleaseMs           = 0;  // when to release FP_OUT (0 = idle)
static uint32_t    postShutterHoldUntilMs   = 0;
static uint32_t    fullPressIgnoreUntilMs   = 0;  // reject FP triggers during R10 window
static bool        fpAcceptedAtGapBoundary  = false;
static uint32_t    hpOutRecoveryCount       = 0;  // guard count for unexpected HP_OUT drops while active
static bool        pendingCamCfgApply       = false;
static volatile bool runtimeIoReconfigurePending = false;
static CameraConfig pendingCamCfg;
static uint32_t    lastActivityEndMs        = 0;
static uint32_t    lastTelemetryServiceMs   = 0;
#ifdef DEBUG_CAMERA_LOGIC_PINS
static uint32_t    nextHpSampleLogMs         = 0;
#endif

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

void resetTelemetryCounters();
void loadTelemetry();
void saveTelemetry();
void markTelemetryChanged(uint8_t eventCode, uint8_t scenarioHint, bool flushSoon);
void markTelemetryEvent(uint8_t eventCode, uint8_t scenarioHint);
uint32_t remainingMs(uint32_t now, uint32_t target);
void populateTelemetryPayload();
void populateTelemetryCharacteristic();
void flushTelemetryIfDue(uint32_t now);
void setupTelemetryGatt();

void loadCalibration();
void saveCalibration();
void applyPendingCal();
void onCalSetWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);

void loadIntCalibration();
void saveIntCalibration();
void applyPendingIntCal();
void onIntCalSetWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);

void advertiseData(int intPct, float intVoltage, uint8_t extPct, uint16_t extVoltMv);
float readBatteryVoltage();
bool  readDeviceBattery(int &pct, float &voltMv, uint8_t cellCount);
int   readCR2032Percentage(float voltage);  // internal CR2032 coin cell (A0)
int   readLiPoPercentage(float voltage);    // device/camera LiPo battery  (A1)

void assertPin(int pin);
void releasePin(int pin);
void assertHpOut(uint32_t now);
void releaseHpOut();
void assertFpOut(uint32_t now, const char* reason);
void releaseFpOut(uint32_t now, const char* reason);
bool timeReached(uint32_t now, uint32_t target);
uint32_t minHalfPressMs();
uint32_t shutterPulseMs();
uint32_t startFrameSpacingMs();
uint32_t postShutterHoldMs();
uint32_t wakeHalfPressHoldMs();
uint32_t fullPressIgnoreGapMs();
bool hpLeadSatisfied(uint32_t now);
bool underSequenceCap();
bool tryAcceptFp(uint32_t now);
void startSequence(uint32_t now);
void endActivity();
void handleFpAfterCap();
void runBurstScheduler(uint32_t now);
void processCameraLogic();
void idleWaitWithCameraWake(uint32_t durationMs);

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
void sanitizeCameraConfig(CameraConfig &cfgToSanitize);
void configureRuntimeIo();
bool cameraActivityInProgress();
void applyPendingCamCfgIfIdle();
void refreshWakeHoldFromHp(uint32_t now);
bool cameraControlWorkPending();
uint32_t connectedTelemetryIntervalMs(uint32_t now);
uint32_t connectedBleBudgetUs(uint32_t now);
void serviceConnectedManagementPlane(uint32_t now);

#ifdef DEBUG_CAMERA_LOGIC_PINS
static void dbgLogPinTransition(
  const char* sig,
  bool active,
  uint32_t now,
  const char* reason,
  int32_t durationMs
) {
  if (!Serial) return;
  Serial.print("[DBG_PIN] t=");
  Serial.print(now);
  Serial.print(" sig=");
  Serial.print(sig);
  Serial.print(" state=");
  Serial.print(active ? "ACTIVE" : "INACTIVE");
  Serial.print(" reason=");
  Serial.print(reason ? reason : "n/a");
  if (durationMs >= 0) {
    Serial.print(" durMs=");
    Serial.print(durationMs);
  }
  Serial.print(" camState=");
  Serial.print((int)cameraState);
  Serial.print(" hpOutAsserted=");
  Serial.println(hpOutAsserted ? 1 : 0);
}

static void dbgLogRuntimeIo(const char* stage) {
  if (!Serial) return;
  Serial.print("[DBG_IO] t=");
  Serial.print(millis());
  Serial.print(" stage=");
  Serial.print(stage ? stage : "n/a");
  Serial.print(" deviceType=");
  Serial.print((int)cfg.deviceType);
  Serial.print(" camEnabled=");
  Serial.print((int)camCfg.enabled);
  Serial.print(" camState=");
  Serial.print((int)cameraState);
  Serial.print(" logicActive=");
  Serial.print(cameraLogicActive ? 1 : 0);
  Serial.print(" activityActive=");
  Serial.print(activityActive ? 1 : 0);
  Serial.print(" hpOutAsserted=");
  Serial.print(hpOutAsserted ? 1 : 0);
  Serial.print(" hpOutPin=");
  Serial.print(digitalRead(HP_OUT_PIN));
  Serial.print(" fpOutPin=");
  Serial.println(digitalRead(FP_OUT_PIN));
}

static void dbgLogHpSample(uint32_t now) {
  if (!Serial) return;
  Serial.print("[DBG_HP_SAMPLE] t=");
  Serial.print(now);
  Serial.print(" hpOutPin=");
  Serial.print(digitalRead(HP_OUT_PIN));
  Serial.print(" hpOutAsserted=");
  Serial.print(hpOutAsserted ? 1 : 0);
  Serial.print(" camState=");
  Serial.print((int)cameraState);
  Serial.print(" logicActive=");
  Serial.print(cameraLogicActive ? 1 : 0);
  Serial.print(" activityActive=");
  Serial.print(activityActive ? 1 : 0);
  Serial.print(" msUntilWakeDeadline=");
  Serial.println(remainingMs(now, wakeHoldDeadlineMs));
}
#endif

// ═════════════════════════════════════════════════════════════════════════════
// setup()
// ═════════════════════════════════════════════════════════════════════════════
void setup() {
#ifdef DEBUG_CAMERA_LOGIC_PINS
  Serial.begin(115200);
  uint32_t serialWaitStart = millis();
  while (!Serial && millis() - serialWaitStart < 1000) {
    delay(5);
  }
#endif

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
  loadTelemetry();
  loadCalibration();
  loadIntCalibration();

  configureRuntimeIo();

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
  bool cameraModeEnabled = (cfg.deviceType == 1 /* CAMERA */ && camCfg.enabled);

  // Fast-path wake: IRQ-driven camera work always runs before BLE housekeeping.
  if (cameraModeEnabled && cameraControlWorkPending()) {
    processCameraLogic();
  }

  applyPendingCamCfgIfIdle();
  if (pendingCalApply) applyPendingCal();
  if (pendingIntCalApply) applyPendingIntCal();

  // Keep camera logic deterministic regardless of BLE connection state.
  if (cameraModeEnabled && !cameraControlWorkPending()) {
    processCameraLogic();
  }

  if (isConnected) {
    serviceConnectedManagementPlane(millis());

    // Connected idle is event-driven: wake immediately on HP/FP ISR flags.
    if (!cameraControlWorkPending()) {
      __WFE();
    }
    return;
  }

  // Sleep guard: don't advertise or sleep while camera logic is running
  if (cameraLogicActive) {
    delay(1);  // keep output pulse timing tight without busy-looping
    return;
  }

  // ── Advertisement cycle (not connected, camera idle or non-camera) ─────────
  float intVoltage = readBatteryVoltage();
  int   intPct     = readCR2032Percentage(intVoltage);

  int   extPctInt = -1;
  float extVoltMvF = 0.0f;
  bool  extPresent = readDeviceBattery(extPctInt, extVoltMvF, cfg.cellCount);
  uint8_t  extBatPct = extPresent ? (uint8_t)extPctInt    : 0xFF;
  uint16_t extBatMv  = extPresent ? (uint16_t)extVoltMvF  : 0xFFFF;

  advertiseData(intPct, intVoltage, extBatPct, extBatMv);
  idleWaitWithCameraWake(ADVERTISING_DURATION_MS);
  Bluefruit.Advertising.stop();

  // Flush any pending flash write (shutter count incremented by ISR)
  if (settingsDirty) {
    saveSettings();
    settingsDirty = false;
  }
  flushTelemetryIfDue(millis());

  idleWaitWithCameraWake(SLEEP_INTERVAL_MS - ADVERTISING_DURATION_MS);
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
  camCfg.powerSaveIdleMode         = 1;
  camCfg.fullPressIgnoreGapTenths  = 31;
  // all policy and mode fields default to 0
}

void loadCameraSettings() {
  resetCameraToDefaults();
  if (!InternalFS.exists(CAMERA_SETTINGS_FILE)) return;
  if (camFile.open(CAMERA_SETTINGS_FILE, FILE_O_READ)) {
    camFile.read(&camCfg, sizeof(camCfg));
    camFile.close();
  }
  if (camCfg.version != CAMERA_SETTINGS_VERSION) {
    resetCameraToDefaults();
  } else {
    sanitizeCameraConfig(camCfg);
  }
}

void saveCameraSettings() {
  InternalFS.remove(CAMERA_SETTINGS_FILE);
  if (camFile.open(CAMERA_SETTINGS_FILE, FILE_O_WRITE)) {
    camFile.write((const uint8_t*)&camCfg, sizeof(camCfg));
    camFile.close();
  }
}

void sanitizeCameraConfig(CameraConfig &cfgToSanitize) {
  cfgToSanitize.version = CAMERA_SETTINGS_VERSION;
  cfgToSanitize.enabled = cfgToSanitize.enabled ? 1 : 0;

  if (cfgToSanitize.wakeHalfPressHoldSec < 1)  cfgToSanitize.wakeHalfPressHoldSec = 1;
  if (cfgToSanitize.wakeHalfPressHoldSec > 60) cfgToSanitize.wakeHalfPressHoldSec = 60;

  if (cfgToSanitize.minHalfPressBeforeShutter < 1)   cfgToSanitize.minHalfPressBeforeShutter = 1;
  if (cfgToSanitize.minHalfPressBeforeShutter > 100) cfgToSanitize.minHalfPressBeforeShutter = 100;

  if (cfgToSanitize.shutterPulseDuration < 1)  cfgToSanitize.shutterPulseDuration = 1;
  if (cfgToSanitize.shutterPulseDuration > 50) cfgToSanitize.shutterPulseDuration = 50;

  if (cfgToSanitize.startFrameSpacingTenths < 1)   cfgToSanitize.startFrameSpacingTenths = 1;
  if (cfgToSanitize.startFrameSpacingTenths > 200) cfgToSanitize.startFrameSpacingTenths = 200;

  if (cfgToSanitize.postShutterHpHoldTenths < 1)   cfgToSanitize.postShutterHpHoldTenths = 1;
  if (cfgToSanitize.postShutterHpHoldTenths > 200) cfgToSanitize.postShutterHpHoldTenths = 200;

  if (cfgToSanitize.hpDebounceMs < 1)   cfgToSanitize.hpDebounceMs = 1;
  if (cfgToSanitize.hpDebounceMs > 250) cfgToSanitize.hpDebounceMs = 250;
  if (cfgToSanitize.fpDebounceMs < 1)   cfgToSanitize.fpDebounceMs = 1;
  if (cfgToSanitize.fpDebounceMs > 250) cfgToSanitize.fpDebounceMs = 250;

  if (cfgToSanitize.frameCount < 1) cfgToSanitize.frameCount = 1;
  if (cfgToSanitize.frameCount > 8) cfgToSanitize.frameCount = 8;
  if (cfgToSanitize.maxSequenceCount < 1) cfgToSanitize.maxSequenceCount = 1;
  if (cfgToSanitize.maxSequenceCount > 8) cfgToSanitize.maxSequenceCount = 8;

  if (cfgToSanitize.wakeHoldRefreshPolicy > 2) cfgToSanitize.wakeHoldRefreshPolicy = 0;
  if (cfgToSanitize.halfPressDuringBurstPolicy != 0) cfgToSanitize.halfPressDuringBurstPolicy = 0;
  if (cfgToSanitize.fullPressWithoutHpPolicy > 1) cfgToSanitize.fullPressWithoutHpPolicy = 0;
  if (cfgToSanitize.activityHalfPressHoldPolicy != 0) cfgToSanitize.activityHalfPressHoldPolicy = 0;
  if (cfgToSanitize.fpAfterMaxSeqCountPolicy > 1) cfgToSanitize.fpAfterMaxSeqCountPolicy = 0;

  // Reserved for future runtime wiring; currently clamped to safe defaults.
  cfgToSanitize.inputActivePolarity = 0;
  cfgToSanitize.outputDriveMode = 0;

  cfgToSanitize.powerSaveIdleMode = cfgToSanitize.powerSaveIdleMode ? 1 : 0;
  if (cfgToSanitize.fullPressIgnoreGapTenths < 5)   cfgToSanitize.fullPressIgnoreGapTenths = 5;
  if (cfgToSanitize.fullPressIgnoreGapTenths > 250) cfgToSanitize.fullPressIgnoreGapTenths = 250;
}

bool cameraActivityInProgress() {
  return cameraState != CAM_IDLE
      || cameraLogicActive
      || activityActive
      || hpOutAsserted
      || coldFpAcceptPending
      || fpOutReleaseMs != 0;
}

void configureRuntimeIo() {
#ifdef DEBUG_CAMERA_LOGIC_PINS
  dbgLogRuntimeIo("configureRuntimeIo.enter");
#endif
  detachInterrupt(digitalPinToInterrupt(FP_IN_PIN));
  detachInterrupt(digitalPinToInterrupt(HP_IN_PIN));

  pinMode(FP_OUT_PIN, INPUT);
  pinMode(HP_OUT_PIN, INPUT);
#ifdef DEBUG_CAMERA_LOGIC_PINS
  dbgLogRuntimeIo("configureRuntimeIo.outputsInput");
#endif

  if (cfg.deviceType == 1 /* CAMERA */) {
    pinMode(FP_IN_PIN, INPUT_PULLUP);
    pinMode(HP_IN_PIN, INPUT_PULLUP);

    if (camCfg.enabled) {
      attachInterrupt(digitalPinToInterrupt(FP_IN_PIN), onShutterPulse, FALLING);
      attachInterrupt(digitalPinToInterrupt(HP_IN_PIN), onHpPulse,      FALLING);
      pinMode(FP_OUT_PIN, INPUT);
      pinMode(HP_OUT_PIN, INPUT);
#ifdef DEBUG_CAMERA_LOGIC_PINS
      dbgLogRuntimeIo("configureRuntimeIo.cameraStateMachine");
#endif
    } else {
      pinMode(FP_OUT_PIN, OUTPUT);
      digitalWrite(FP_OUT_PIN, digitalRead(FP_IN_PIN));
      pinMode(HP_OUT_PIN, OUTPUT);
      digitalWrite(HP_OUT_PIN, digitalRead(HP_IN_PIN));
      attachInterrupt(digitalPinToInterrupt(FP_IN_PIN), onFpPassthrough, CHANGE);
      attachInterrupt(digitalPinToInterrupt(HP_IN_PIN), onHpPassthrough, CHANGE);
#ifdef DEBUG_CAMERA_LOGIC_PINS
      dbgLogRuntimeIo("configureRuntimeIo.cameraPassthrough");
#endif
    }
    return;
  }

  pinMode(FP_IN_PIN, INPUT_PULLUP);
  pinMode(HP_IN_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(FP_IN_PIN), onShutterPulse, FALLING);
#ifdef DEBUG_CAMERA_LOGIC_PINS
  dbgLogRuntimeIo("configureRuntimeIo.nonCamera");
#endif
}

void applyPendingCamCfgIfIdle() {
  if (cameraActivityInProgress()) return;

  if (pendingCamCfgApply) {
    camCfg = pendingCamCfg;
    sanitizeCameraConfig(camCfg);
    saveCameraSettings();
    populateCameraCharacteristics();
    pendingCamCfgApply = false;
    runtimeIoReconfigurePending = true;
  }

  if (runtimeIoReconfigurePending) {
    configureRuntimeIo();
    runtimeIoReconfigurePending = false;
  }
}

void refreshWakeHoldFromHp(uint32_t now) {
  (void)now;
  telCounters.hpRefreshCount++;
  markTelemetryChanged(TEL_EVT_HP_REFRESH, TEL_SC_NONE, false);
  // Customer requirement: HP_OUT release is based on max(initial HP assert + X, last frame + Z).
  // Therefore, repeated HP input pulses must not move wakeHoldDeadlineMs.
}

bool cameraControlWorkPending() {
  if (!(cfg.deviceType == 1 /* CAMERA */ && camCfg.enabled)) return false;
  return cameraLogicActive || hpPulseFlag || fpPulseFlag;
}

static bool budgetExpired(uint32_t startedUs, uint32_t budgetUs) {
  if (budgetUs == 0) return false;
  return (uint32_t)(micros() - startedUs) >= budgetUs;
}

static bool inPostSequenceWindow(uint32_t now) {
  if (lastActivityEndMs == 0) return false;
  return !timeReached(now, lastActivityEndMs + CONNECTED_POST_MODE_WINDOW_MS);
}

uint32_t connectedTelemetryIntervalMs(uint32_t now) {
  if (cameraActivityInProgress()) {
    return CONNECTED_ACTIVE_TELEMETRY_MIN_INTERVAL_MS;
  }
  if (inPostSequenceWindow(now)) {
    return CONNECTED_POST_TELEMETRY_MIN_INTERVAL_MS;
  }
  return CONNECTED_IDLE_TELEMETRY_MIN_INTERVAL_MS;
}

uint32_t connectedBleBudgetUs(uint32_t now) {
  if (cameraActivityInProgress()) {
    return CONNECTED_ACTIVE_BLE_BUDGET_US;
  }
  if (inPostSequenceWindow(now)) {
    return CONNECTED_POST_BLE_BUDGET_US;
  }
  return CONNECTED_IDLE_BLE_BUDGET_US;
}

void serviceConnectedManagementPlane(uint32_t now) {
  uint32_t serviceStartedUs = micros();
  uint32_t bleBudgetUs = connectedBleBudgetUs(now);

  if (shutterUpdated) {
    if (!cameraActivityInProgress() || timeReached(now, lastTelemetryServiceMs + connectedTelemetryIntervalMs(now))) {
      shutterUpdated = false;
      chrShutter.notify32(cfg.shutterCount);
      lastTelemetryServiceMs = now;
    }
  }

  if (budgetExpired(serviceStartedUs, bleBudgetUs)) return;

  bool telemetryDue = timeReached(now, lastTelemetryServiceMs + connectedTelemetryIntervalMs(now));
  if (telemetryDue) {
    bool telemetryWasUpdated = telemetryUpdated;
    populateTelemetryCharacteristic();
    if (telemetryWasUpdated) {
      telemetryUpdated = false;
      chrTelemetry.notify((uint8_t*)&telPayload, sizeof(telPayload));
    }
    lastTelemetryServiceMs = now;
  }

  if (budgetExpired(serviceStartedUs, bleBudgetUs)) return;

  // Persist telemetry outside active timing windows to avoid flash jitter.
  if (!cameraActivityInProgress()) {
    flushTelemetryIfDue(now);
  }
}

// ═════════════════════════════════════════════════════════════════════════════
// Flash storage – Camera telemetry counters
// ═════════════════════════════════════════════════════════════════════════════
void resetTelemetryCounters() {
  memset(&telCounters, 0, sizeof(telCounters));
  telCounters.version = TELEMETRY_VERSION;
}

void loadTelemetry() {
  resetTelemetryCounters();
  if (!InternalFS.exists(TELEMETRY_FILE)) return;
  if (telFile.open(TELEMETRY_FILE, FILE_O_READ)) {
    telFile.read(&telCounters, sizeof(telCounters));
    telFile.close();
  }
  if (telCounters.version != TELEMETRY_VERSION) resetTelemetryCounters();
}

void saveTelemetry() {
  CameraTelemetryCounters snapshot;
  noInterrupts();
  memcpy(&snapshot, &telCounters, sizeof(snapshot));
  interrupts();

  InternalFS.remove(TELEMETRY_FILE);
  if (telFile.open(TELEMETRY_FILE, FILE_O_WRITE)) {
    telFile.write((const uint8_t*)&snapshot, sizeof(snapshot));
    telFile.close();
  }
}

void markTelemetryChanged(uint8_t eventCode, uint8_t scenarioHint, bool flushSoon) {
  lastTelemetryEvent = eventCode;
  lastTelemetryScenario = scenarioHint;
  telemetryUpdated = true;
  telemetryDirty = true;

  uint32_t now = millis();
  if (flushSoon || nextTelemetryFlushMs == 0) {
    nextTelemetryFlushMs = flushSoon ? now : now + TELEMETRY_FLUSH_INTERVAL_MS;
  }
}

void markTelemetryEvent(uint8_t eventCode, uint8_t scenarioHint) {
  lastTelemetryEvent = eventCode;
  lastTelemetryScenario = scenarioHint;
  telemetryUpdated = true;
}

uint32_t remainingMs(uint32_t now, uint32_t target) {
  if (target == 0 || timeReached(now, target)) return 0;
  return target - now;
}

void populateTelemetryPayload() {
  uint32_t now = millis();
  memset(&telPayload, 0, sizeof(telPayload));
  telPayload.version = TELEMETRY_VERSION;
  telPayload.cameraState = (uint8_t)cameraState;
  telPayload.flags = (activityActive ? 0x01 : 0x00)
                   | (hpOutAsserted  ? 0x02 : 0x00);
  telPayload.framesFiredThisSequence = framesFired;
  telPayload.sequencesStartedThisActivity = sequencesStartedThisActivity;
  telPayload.lastEventCode = lastTelemetryEvent;
  telPayload.lastScenarioHint = lastTelemetryScenario;
  telPayload.msUntilWakeDeadline = remainingMs(now, wakeHoldDeadlineMs);
  telPayload.msUntilFpIgnoreClear = remainingMs(now, fullPressIgnoreUntilMs);
  telPayload.msUntilNextFrame = remainingMs(now, nextFrameMs);
  telPayload.msUntilPostHoldEnd = remainingMs(now, postShutterHoldUntilMs);
  noInterrupts();
  memcpy(&telPayload.counters, &telCounters, sizeof(telCounters));
  interrupts();
}

void populateTelemetryCharacteristic() {
  populateTelemetryPayload();
  chrTelemetry.write((const uint8_t*)&telPayload, sizeof(telPayload));
}

void flushTelemetryIfDue(uint32_t now) {
  if (!telemetryDirty) return;
  if (nextTelemetryFlushMs != 0 && !timeReached(now, nextTelemetryFlushMs)) return;
  telemetryDirty = false;
  nextTelemetryFlushMs = 0;
  saveTelemetry();
}

// ═════════════════════════════════════════════════════════════════════════════
// Flash storage – Calibration
// ═════════════════════════════════════════════════════════════════════════════
void loadCalibration() {
  deviceDividerRatio = EXT_BATT_DIVIDER_RATIO;
  if (!InternalFS.exists(CAL_FILE)) return;
  CalibrationConfig cal;
  if (calFile.open(CAL_FILE, FILE_O_READ)) {
    calFile.read(&cal, sizeof(cal));
    calFile.close();
    if (cal.version == CALIBRATION_VERSION &&
        cal.dividerRatio >= 1.0f && cal.dividerRatio <= 25.0f) {
      deviceDividerRatio = cal.dividerRatio;
    }
  }
}

void saveCalibration() {
  CalibrationConfig cal;
  cal.version = CALIBRATION_VERSION;
  memset(cal.reserved, 0, sizeof(cal.reserved));
  cal.dividerRatio = deviceDividerRatio;
  InternalFS.remove(CAL_FILE);
  if (calFile.open(CAL_FILE, FILE_O_WRITE)) {
    calFile.write((const uint8_t*)&cal, sizeof(cal));
    calFile.close();
  }
}

void applyPendingCal() {
  pendingCalApply = false;
  uint16_t targetMv = pendingCalTargetMv;

  if (targetMv == 0) {          // 0x0000 = reset to default
    deviceDividerRatio = EXT_BATT_DIVIDER_RATIO;
    InternalFS.remove(CAL_FILE);
    return;
  }

  analogReference(AR_INTERNAL_3_0);
  analogReadResolution(10);
  int total = 0;
  for (int i = 0; i < 5; i++) {
    total += analogRead(DEVICE_BATTERY_PIN);
    delayMicroseconds(100);
  }
  float avg = (float)(total / 5);
  if (avg < 1.0f) return;  // no battery present, ignore

  float raw_V    = (avg / ADC_MAX_VALUE) * ADC_REFERENCE_VOLTAGE;
  float target_V = targetMv / 1000.0f;
  float new_ratio = target_V / raw_V;

  if (new_ratio < 1.0f || new_ratio > 25.0f) return;  // sanity clamp

  deviceDividerRatio = new_ratio;
  saveCalibration();
}

void onCalSetWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 2) return;
  pendingCalTargetMv = (uint16_t)d[0] | ((uint16_t)d[1] << 8);
  pendingCalApply    = true;
}

// ─── Internal CR2032 calibration ─────────────────────────────────────────────
void loadIntCalibration() {
  intScaleFactor = 1.0f;
  if (!InternalFS.exists(INT_CAL_FILE)) return;
  IntCalibrationConfig cal;
  if (intCalFile.open(INT_CAL_FILE, FILE_O_READ)) {
    intCalFile.read(&cal, sizeof(cal));
    intCalFile.close();
    if (cal.version == CALIBRATION_VERSION &&
        cal.scaleFactor >= 0.5f && cal.scaleFactor <= 2.0f) {
      intScaleFactor = cal.scaleFactor;
    }
  }
}

void saveIntCalibration() {
  IntCalibrationConfig cal;
  cal.version = CALIBRATION_VERSION;
  memset(cal.reserved, 0, sizeof(cal.reserved));
  cal.scaleFactor = intScaleFactor;
  InternalFS.remove(INT_CAL_FILE);
  if (intCalFile.open(INT_CAL_FILE, FILE_O_WRITE)) {
    intCalFile.write((const uint8_t*)&cal, sizeof(cal));
    intCalFile.close();
  }
}

void applyPendingIntCal() {
  pendingIntCalApply = false;
  uint16_t targetMv = pendingIntCalTargetMv;

  if (targetMv == 0) {
    intScaleFactor = 1.0f;
    InternalFS.remove(INT_CAL_FILE);
    return;
  }

  // Use the last raw (unscaled) reading cached by readBatteryVoltage().
  // This guarantees the scale factor is computed from the same ADC state
  // and signal path as the value the display is showing, avoiding
  // reference-settling differences between a fresh sample here and the
  // subsequent warm reads in readBatteryVoltage().
  float raw_V = lastRawIntVoltage;

  if (raw_V < 0.1f) {
    // No cached reading yet (e.g. connected before first advertising cycle).
    // Take a direct sample with a warm-up discard read.
    analogReference(AR_INTERNAL_3_0);
    analogReadResolution(10);
    analogRead(BATTERY_PIN);       // discard – let reference settle
    delayMicroseconds(500);
    int total = 0;
    for (int i = 0; i < 5; i++) {
      total += analogRead(BATTERY_PIN);
      delayMicroseconds(100);
    }
    raw_V = ((float)(total / 5) / ADC_MAX_VALUE) * ADC_REFERENCE_VOLTAGE;
  }

  if (raw_V < 0.1f) return;

  float target_V = targetMv / 1000.0f;
  float new_scale = target_V / raw_V;

  if (new_scale < 0.5f || new_scale > 2.0f) return;  // sanity clamp

  intScaleFactor = new_scale;
  saveIntCalibration();
}

void onIntCalSetWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < 2) return;
  pendingIntCalTargetMv = (uint16_t)d[0] | ((uint16_t)d[1] << 8);
  pendingIntCalApply    = true;
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

void setupTelemetryGatt() {
  // ── Camera Telemetry (R + Notify, fixed binary payload) ──────────────────
  chrTelemetry.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  chrTelemetry.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  chrTelemetry.setFixedLen(sizeof(CameraTelemetryPayload));
  chrTelemetry.begin();
}

void setupCameraGatt() {
  // ── Camera Config (R/W, fixed 20 bytes) ──────────────────────────────────
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

  // ── Camera Config (R/W, 20 bytes) ────────────────────────────────────────
  setupCameraGatt();
  setupTelemetryGatt();

  // ── Calibration Set (Write only, 2 bytes little-endian uint16 mV) ────────
  chrCalSet.setProperties(CHR_PROPS_WRITE);
  chrCalSet.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chrCalSet.setFixedLen(2);
  chrCalSet.setWriteCallback(onCalSetWrite);
  chrCalSet.begin();

  chrIntCalSet.setProperties(CHR_PROPS_WRITE);
  chrIntCalSet.setPermission(SECMODE_NO_ACCESS, SECMODE_OPEN);
  chrIntCalSet.setFixedLen(2);
  chrIntCalSet.setWriteCallback(onIntCalSetWrite);
  chrIntCalSet.begin();

  // Seed characteristics with values loaded from flash
  populateCharacteristics();
  populateCameraCharacteristics();
  populateTelemetryCharacteristic();
}

// ═════════════════════════════════════════════════════════════════════════════
// Advertising – manufacturer-specific packet
// Offset after company ID (Android data[]):
//   [0]    Internal battery %
//   [1-2]  Internal voltage mV LE
//   [3]    External battery % (0xFF = not present)
//   [4-5]  External voltage mV LE (0xFFFF = not present)
//   [6]    Flags (configured | deviceType | chemistry)
//   [7]    Group ID
//   [8]    Cell count
//   [9-10] Shutter count LE uint16
//   [11]   Beacon layout version
//   [12]   Camera state snapshot
//   [13]   Camera live flags (bit0 activityActive, bit1 hpOutAsserted)
//   [14]   Build year low byte
//   [15]   Build year high byte
//   [16]   Build month (1-12)
//   [17]   Build day (1-31)
//   [18]   Build hour (0-23)
//   [19]   Build minute (0-59)
//   [20]   Build second (0-59)
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

  uint8_t mfgData[23];
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
  mfgData[13] = BEACON_LAYOUT_VERSION;
  mfgData[14] = (uint8_t)cameraState;
  mfgData[15] = (activityActive ? 0x01 : 0x00)
              | (hpOutAsserted  ? 0x02 : 0x00);
  mfgData[16] = BUILD_YEAR & 0xFF;
  mfgData[17] = (BUILD_YEAR >> 8) & 0xFF;
  mfgData[18] = BUILD_MONTH;
  mfgData[19] = BUILD_DAY;
  mfgData[20] = BUILD_HOUR;
  mfgData[21] = BUILD_MIN;
  mfgData[22] = BUILD_SEC;

  Bluefruit.Advertising.addData(BLE_GAP_AD_TYPE_MANUFACTURER_SPECIFIC_DATA, mfgData, 23);

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
  telemetryUpdated = true;
}

void onDisconnect(uint16_t connHdl, uint8_t reason) {
  (void)connHdl; (void)reason;
  isConnected = false;
  if (telemetryDirty) {
    telemetryDirty = false;
    nextTelemetryFlushMs = 0;
    saveTelemetry();
  }
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
  cfg.deviceType = d[0] & 0x03;
  if (cameraActivityInProgress()) {
    endActivity();
  }
  runtimeIoReconfigurePending = true;
  markConfigured();
  saveSettings();
  populateCharacteristics();
  telemetryUpdated = true;
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
  if (cameraActivityInProgress()) {
    endActivity();
  }
  resetToDefaults();
  resetCameraToDefaults();
  resetTelemetryCounters();
  pendingCamCfgApply = false;
  runtimeIoReconfigurePending = true;
  deviceDividerRatio = EXT_BATT_DIVIDER_RATIO;
  intScaleFactor = 1.0f;
  InternalFS.remove(CAMERA_SETTINGS_FILE);
  InternalFS.remove(TELEMETRY_FILE);
  InternalFS.remove(CAL_FILE);
  InternalFS.remove(INT_CAL_FILE);
  populateCharacteristics();
  populateCameraCharacteristics();
  populateTelemetryCharacteristic();
  Bluefruit.setName("Camtraptions Device");
  saveSettings();
  saveCameraSettings();
}

void onCamCfgWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  if (l < sizeof(camCfg)) return;
  CameraConfig incoming;
  memcpy(&incoming, d, sizeof(incoming));
  sanitizeCameraConfig(incoming);

  if (cameraActivityInProgress()) {
    pendingCamCfg = incoming;
    pendingCamCfgApply = true;
    runtimeIoReconfigurePending = true;
    return;
  }

  camCfg = incoming;
  saveCameraSettings();
  populateCameraCharacteristics();
  runtimeIoReconfigurePending = true;
}

// ═════════════════════════════════════════════════════════════════════════════
// FP_IN ISR (shutter pulse) – fires on FALLING edge of FP_IN_PIN
// Debounced in software. Does NOT call BLE stack (not ISR-safe).
// ═════════════════════════════════════════════════════════════════════════════
void onShutterPulse() {
  uint32_t now = millis();
  uint32_t debounceMs = (cfg.deviceType == 1 && camCfg.enabled)
                      ? camCfg.fpDebounceMs
                      : SHUTTER_DEBOUNCE_MS;
  if (now - lastShutterMs < debounceMs) {
    if (cfg.deviceType == 1 && camCfg.enabled) {
      telCounters.fpDebounceRejectCount++;
      markTelemetryChanged(TEL_EVT_FP_DEBOUNCE_REJECT, TEL_SC_DEBOUNCE, false);
    }
    return;
  }
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
  if (!hpOutAsserted) {
    assertPin(HP_OUT_PIN);
    hpAssertedMs = now;
    hpOutAsserted = true;
  }
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

void assertHpOut(uint32_t now) {
  bool transitioningActive = !hpOutAsserted;
  if (transitioningActive) {
    hpAssertedMs = now;
  }
  assertPin(HP_OUT_PIN);
  hpOutAsserted = true;
#ifdef DEBUG_CAMERA_LOGIC_PINS
  if (transitioningActive) {
    dbgLogPinTransition("HP_OUT", true, now, "assertHpOut", -1);
  }
#endif
}

void releaseHpOut() {
  uint32_t now = millis();
  int32_t holdMs = hpOutAsserted ? (int32_t)(now - hpAssertedMs) : -1;
  releasePin(HP_OUT_PIN);
  hpOutAsserted = false;
#ifdef DEBUG_CAMERA_LOGIC_PINS
  dbgLogPinTransition("HP_OUT", false, now, "releaseHpOut", holdMs);
#endif
}

void assertFpOut(uint32_t now, const char* reason) {
  assertPin(FP_OUT_PIN);
  lastFpOutStartMs = now;
#ifdef DEBUG_CAMERA_LOGIC_PINS
  dbgLogPinTransition("FP_OUT", true, now, reason, -1);
#endif
}

void releaseFpOut(uint32_t now, const char* reason) {
  int32_t pulseMs = (lastFpOutStartMs != 0) ? (int32_t)(now - lastFpOutStartMs) : -1;
  releasePin(FP_OUT_PIN);
#ifdef DEBUG_CAMERA_LOGIC_PINS
  dbgLogPinTransition("FP_OUT", false, now, reason, pulseMs);
#endif
}

bool timeReached(uint32_t now, uint32_t target) {
  return (int32_t)(now - target) >= 0;
}

uint32_t minHalfPressMs() {
  return (uint32_t)camCfg.minHalfPressBeforeShutter * 100UL;
}

uint32_t shutterPulseMs() {
  return (uint32_t)camCfg.shutterPulseDuration * 10UL;
}

uint32_t startFrameSpacingMs() {
  return (uint32_t)camCfg.startFrameSpacingTenths * 100UL;
}

uint32_t postShutterHoldMs() {
  return (uint32_t)camCfg.postShutterHpHoldTenths * 100UL;
}

uint32_t wakeHalfPressHoldMs() {
  return (uint32_t)camCfg.wakeHalfPressHoldSec * 1000UL;
}

uint32_t fullPressIgnoreGapMs() {
  return (uint32_t)camCfg.fullPressIgnoreGapTenths * 100UL;
}

bool hpLeadSatisfied(uint32_t now) {
  return hpOutAsserted && (now - hpAssertedMs >= minHalfPressMs());
}

bool underSequenceCap() {
  uint8_t safeMax = camCfg.maxSequenceCount;
  if (safeMax < 1) safeMax = 1;
  return sequencesStartedThisActivity < safeMax;
}

bool tryAcceptFp(uint32_t now) {
  fpAcceptedAtGapBoundary = false;
  if (!timeReached(now, fullPressIgnoreUntilMs)) {
    telCounters.ignoredFpDuringGapCount++;
    markTelemetryChanged(TEL_EVT_FP_REJECT_GAP, TEL_SC_FP_GAP_IGNORE, false);
    return false;
  }
  if (!underSequenceCap()) {
    telCounters.rejectedFpAtSequenceCapCount++;
    markTelemetryChanged(TEL_EVT_FP_REJECT_CAP, TEL_SC_SEQUENCE_CAP, false);
    return false;
  }
  if (fullPressIgnoreUntilMs != 0 && now == fullPressIgnoreUntilMs) {
    fpAcceptedAtGapBoundary = true;
  }
  return true;
}

void ensureHpOutAsserted(uint32_t now) {
  if (!hpOutAsserted) {
    hpOutRecoveryCount++;
    assertHpOut(now);
  }
}

void startSequence(uint32_t now) {
  bool wasColdFp = coldFpAcceptPending;

  // Guard against any unexpected HP output drop before a sequence starts.
  ensureHpOutAsserted(now);
  activityActive = true;
  coldFpAcceptPending = false;
  sequencesStartedThisActivity++;
  telCounters.acceptedFpCount++;
  if (wasColdFp) {
    telCounters.coldFpSequenceCount++;
  }
  uint8_t fpAcceptedEvent = TEL_EVT_FP_ACCEPTED;
  if (!wasColdFp && fpAcceptedAtGapBoundary) {
    fpAcceptedEvent = TEL_EVT_FP_ACCEPTED_AT_GAP_BOUNDARY;
  }
  markTelemetryChanged(fpAcceptedEvent,
                       wasColdFp ? TEL_SC_COLD_FP : TEL_SC_NONE,
                       false);
  fpAcceptedAtGapBoundary = false;

  sequenceStartMs = now;
  fullPressIgnoreUntilMs = now + fullPressIgnoreGapMs();

  framesFired = 0;
  fpOutReleaseMs = 0;
  lastFpOutStartMs = 0;
  nextFrameMs = now;
  cameraState = CAM_BURST_ACTIVE;
  cameraLogicActive = true;
}

void endActivity() {
  bool hadActivity = activityActive || hpOutAsserted || cameraState != CAM_IDLE;

  releaseFpOut(millis(), "endActivity");
  releaseHpOut();
  activityActive = false;
  coldFpAcceptPending = false;
  cameraLogicActive = false;
  cameraState = CAM_IDLE;
  framesFired = 0;
  sequencesStartedThisActivity = 0;
  fpOutReleaseMs = 0;
  nextFrameMs = 0;
  lastFpOutStartMs = 0;
  postShutterHoldUntilMs = 0;
  fullPressIgnoreUntilMs = 0;
  fpAcceptedAtGapBoundary = false;
  lastActivityEndMs = millis();

  if (hadActivity) {
    telCounters.activityCompletedCount++;
    if (lastTelemetryEvent == TEL_EVT_WAKE_TIMEOUT) {
      markTelemetryChanged(TEL_EVT_WAKE_TIMEOUT, TEL_SC_WAKE_TIMEOUT, true);
    } else {
      markTelemetryChanged(TEL_EVT_ACTIVITY_END, TEL_SC_NONE, true);
    }
  } else {
    markTelemetryEvent(TEL_EVT_ACTIVITY_END, TEL_SC_NONE);
  }
}

void handleFpAfterCap() {
  if (camCfg.fpAfterMaxSeqCountPolicy == 1) {
    endActivity();
  }
}

void runBurstScheduler(uint32_t now) {
  if (fpOutReleaseMs != 0 && timeReached(now, fpOutReleaseMs)) {
    releaseFpOut(now, "burstPulseComplete");
    fpOutReleaseMs = 0;
  }

  if (fpOutReleaseMs == 0 && framesFired < camCfg.frameCount && timeReached(now, nextFrameMs)) {
    if (!hpOutAsserted) {
      assertHpOut(now);
      nextFrameMs = now + minHalfPressMs();
      return;
    }

    if (!hpLeadSatisfied(now)) {
      nextFrameMs = hpAssertedMs + minHalfPressMs();
      return;
    }

    assertFpOut(now, "burstFrameFire");
    fpOutReleaseMs = now + shutterPulseMs();
    framesFired++;
    nextFrameMs = now + startFrameSpacingMs();
  }

  if (framesFired >= camCfg.frameCount && fpOutReleaseMs == 0) {
    telCounters.sequenceCompletedCount++;
    markTelemetryChanged(TEL_EVT_BURST_COMPLETE, TEL_SC_NONE, false);
    postShutterHoldUntilMs = now + postShutterHoldMs();
    cameraState = CAM_POST_SHUTTER_EXT;
  }
}

// ═════════════════════════════════════════════════════════════════════════════
// Camera logic state machine – called from loop() when deviceType==CAMERA
// ═════════════════════════════════════════════════════════════════════════════
void processCameraLogic() {
  uint32_t now = millis();

#ifdef DEBUG_CAMERA_LOGIC_PINS
  if (timeReached(now, nextHpSampleLogMs)) {
    dbgLogHpSample(now);
    nextHpSampleLogMs = now + 100;
  }
#endif

  // Consume ISR flags atomically
  bool hpTrig = false, fpTrig = false;
  noInterrupts();
  if (hpPulseFlag) { hpPulseFlag = false; hpTrig = true; }
  if (fpPulseFlag) { fpPulseFlag = false; fpTrig = true; }
  interrupts();

  switch (cameraState) {

    // ── IDLE ──────────────────────────────────────────────────────────────────
    case CAM_IDLE:
      cameraLogicActive = false;

      if (hpTrig) {
        assertHpOut(now);
        activityActive = false;
        sequencesStartedThisActivity = 0;
        wakeHoldDeadlineMs = now + wakeHalfPressHoldMs();
        fullPressIgnoreUntilMs = 0;
        cameraState = CAM_WAKE_AF;
        cameraLogicActive = true;
        markTelemetryEvent(TEL_EVT_HP_WAKE, TEL_SC_NONE);
      } else if (fpTrig && camCfg.fullPressWithoutHpPolicy == 0) {
        assertHpOut(now);
        activityActive = true;
        sequencesStartedThisActivity = 0;
        wakeHoldDeadlineMs = now + wakeHalfPressHoldMs();
        fullPressIgnoreUntilMs = 0;
        coldFpAcceptPending = true;
        cameraState = CAM_COLD_FP_WAIT;
        cameraLogicActive = true;
        markTelemetryEvent(TEL_EVT_COLD_FP, TEL_SC_COLD_FP);
      }
      break;

    // ── WAKE_AF ───────────────────────────────────────────────────────────────
    case CAM_WAKE_AF:
      cameraLogicActive = true;

      if (hpTrig) {
        refreshWakeHoldFromHp(now);
      }

      if (fpTrig) {
        if (tryAcceptFp(now)) {
          startSequence(now);
          runBurstScheduler(now);
        } else if (!underSequenceCap()) {
          handleFpAfterCap();
        }
        break;
      }

      if (!activityActive && timeReached(now, wakeHoldDeadlineMs)) {
        telCounters.wakeTimeoutCount++;
        markTelemetryChanged(TEL_EVT_WAKE_TIMEOUT, TEL_SC_WAKE_TIMEOUT, true);
        endActivity();
      }
      break;

    // ── COLD_FP_WAIT ──────────────────────────────────────────────────────────
    case CAM_COLD_FP_WAIT:
      cameraLogicActive = true;
      ensureHpOutAsserted(now);

      if (hpTrig) {
        refreshWakeHoldFromHp(now);
      }

      if (timeReached(now, wakeHoldDeadlineMs)) {
        telCounters.wakeTimeoutCount++;
        markTelemetryChanged(TEL_EVT_WAKE_TIMEOUT, TEL_SC_WAKE_TIMEOUT, true);
        endActivity();
        break;
      }

      if (!coldFpAcceptPending) {
        endActivity();
        break;
      }

      if (hpLeadSatisfied(now)) {
        if (tryAcceptFp(now)) {
          startSequence(now);
          runBurstScheduler(now);
        } else if (!underSequenceCap()) {
          handleFpAfterCap();
        } else {
          endActivity();
        }
      }
      break;

    // ── BURST_ACTIVE ──────────────────────────────────────────────────────────
    case CAM_BURST_ACTIVE:
      cameraLogicActive = true;
      ensureHpOutAsserted(now);

      // R14: HP input during burst is independent of scheduling; FP is ignored by R10.
      if (hpTrig) {
        telCounters.hpIgnoredDuringBurstCount++;
        markTelemetryChanged(TEL_EVT_HP_IGNORED_BURST, TEL_SC_HP_DURING_BURST, false);
      }
      if (fpTrig) {
        telCounters.ignoredFpDuringBurstCount++;
        if (!timeReached(now, fullPressIgnoreUntilMs)) {
          telCounters.ignoredFpDuringGapCount++;
        }
        markTelemetryChanged(TEL_EVT_FP_REJECT_GAP, TEL_SC_FP_GAP_IGNORE, false);
      }
      runBurstScheduler(now);
      break;

    // ── POST_SHUTTER_EXT ──────────────────────────────────────────────────────
    case CAM_POST_SHUTTER_EXT:
      cameraLogicActive = true;
      ensureHpOutAsserted(now);

      if (hpTrig) {
        refreshWakeHoldFromHp(now);
      }

      if (fpTrig) {
        if (tryAcceptFp(now)) {
          startSequence(now);
          runBurstScheduler(now);
        } else if (!underSequenceCap()) {
          handleFpAfterCap();
        }
        break;
      }

      if (timeReached(now, postShutterHoldUntilMs)) {
        if (!underSequenceCap()) {
          // Keep cap context alive until idle timeout so post-cap FP is rejected
          // according to fpAfterMaxSeqCountPolicy.
          activityActive = false;
          cameraState = CAM_WAKE_AF;
        } else {
          // Keep wake path alive between sequences; do not end solely on wake timer.
          activityActive = false;
          cameraState = CAM_WAKE_AF;
        }
      }
      break;
  }
}

void idleWaitWithCameraWake(uint32_t durationMs) {
  if (!(cfg.deviceType == 1 && camCfg.enabled && camCfg.powerSaveIdleMode)) {
    delay(durationMs);
    return;
  }

  uint32_t startMs = millis();
  while (millis() - startMs < durationMs) {
    if (cameraLogicActive || hpPulseFlag || fpPulseFlag) {
      processCameraLogic();
      if (cameraLogicActive) return;
      if (hpPulseFlag || fpPulseFlag) continue;
    }
    // GPIO interrupts wake WFE immediately, avoiding the old blind 950 ms delay.
    __WFE();
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
  float raw = ((float)(total / 5) / ADC_MAX_VALUE) * ADC_REFERENCE_VOLTAGE;
  lastRawIntVoltage = raw;
  return raw * intScaleFactor;
}

// ═════════════════════════════════════════════════════════════════════════════
// Battery reading – device/camera battery (A1)
// Returns true if a battery is detected (voltage >= 0.5V).
// On return: pct = 0–100, voltMv = millivolts.
// ═════════════════════════════════════════════════════════════════════════════
bool readDeviceBattery(int &pct, float &voltMv, uint8_t cellCount) {
  analogReference(AR_INTERNAL_3_0);
  analogReadResolution(10);
  int total = 0;
  for (int i = 0; i < 5; i++) {
    total += analogRead(DEVICE_BATTERY_PIN);
    delayMicroseconds(100);
  }
  float avg = (float)(total / 5);
  float voltage = (avg / ADC_MAX_VALUE) * ADC_REFERENCE_VOLTAGE * deviceDividerRatio;
  if (voltage < 0.5f) return false;  // not present
  voltMv = voltage * 1000.0f;        // full pack voltage sent to app
  uint8_t cells = (cellCount < 1) ? 1 : cellCount;
  float perCellVoltage = voltage / cells;
  pct = readLiPoPercentage(perCellVoltage);  // per-cell voltage for % lookup
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
// LiPo percentage (external camera battery, A1)
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

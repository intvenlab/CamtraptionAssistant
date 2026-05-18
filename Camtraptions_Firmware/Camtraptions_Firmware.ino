/*
 * Camtraptions BLE Battery Monitor – Seeed Studio XIAO nRF52840
 * Phase 2: Flash-persisted settings + GATT configuration service
 *
 * Setup Instructions:
 * 1. Install "Seeed nRF52 Boards" in Arduino IDE Board Manager
 * 2. Select "Seeed XIAO nRF52840" as your board
 * 3. Upload this sketch
 *
 * GATT UUIDs must match GattUuids in Android AppScreen.kt.
 */

#include <bluefruit.h>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>

using namespace Adafruit_LittleFS_Namespace;

// ─── Pins ────────────────────────────────────────────────────────────────────
#define BATTERY_PIN   A0  // Battery voltage ADC input
#define SHUTTER_PIN   1   // D1 – camera sync/shutter pulse (active LOW)

// ─── ADC ─────────────────────────────────────────────────────────────────────
#define VOLTAGE_DIVIDER_RATIO 1.0f
#define ADC_MAX_VALUE         1024.0f

// ─── Timing ──────────────────────────────────────────────────────────────────
#define ADVERTISING_DURATION_MS 50
#define SLEEP_INTERVAL_MS       1000
#define SHUTTER_DEBOUNCE_MS     100

// ─── Flash ───────────────────────────────────────────────────────────────────
#define SETTINGS_FILE    "/settings.bin"
#define SETTINGS_VERSION 1

// All device settings persisted to flash.
// Bump SETTINGS_VERSION if you add/remove/reorder fields.
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

static DeviceConfig cfg;
static File         cfgFile(InternalFS);

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

// ─── Runtime state ───────────────────────────────────────────────────────────
volatile bool     isConnected    = false;
volatile bool     settingsDirty  = false;  // shutter count changed in ISR
volatile bool     shutterUpdated = false;  // notify pending
volatile uint32_t lastShutterMs  = 0;

// ─── Forward declarations ─────────────────────────────────────────────────────
void loadSettings();
void saveSettings();
void resetToDefaults();
void populateCharacteristics();
void setupGatt();
void advertiseData(int percentage, float voltage);
float readBatteryVoltage();
int   readBatteryPercentage(float voltage);

void onConnect   (uint16_t connHdl);
void onDisconnect(uint16_t connHdl, uint8_t reason);
void onShutterPulse();

void onNameWrite     (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onGroupIdWrite  (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onGroupNameWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onDevTypeWrite  (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onChemWrite     (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onCellWrite     (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onResetWrite    (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);
void onFactoryWrite  (uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l);

// ═════════════════════════════════════════════════════════════════════════════
// setup()
// ═════════════════════════════════════════════════════════════════════════════
void setup() {
  // Battery ADC
  pinMode(BATTERY_PIN, INPUT);

  // Camera shutter pulse – active LOW (camera sync output or optocoupler)
  pinMode(SHUTTER_PIN, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(SHUTTER_PIN), onShutterPulse, FALLING);

  // LEDs off (active-low on XIAO nRF52840)
  pinMode(LED_RED,   OUTPUT); digitalWrite(LED_RED,   HIGH);
  pinMode(LED_GREEN, OUTPUT); digitalWrite(LED_GREEN, HIGH);
  pinMode(LED_BLUE,  OUTPUT); digitalWrite(LED_BLUE,  HIGH);

  // DC/DC converter for better efficiency; low-power CPU mode
  sd_power_dcdc_mode_set(NRF_POWER_DCDC_ENABLE);
  sd_power_mode_set(NRF_POWER_MODE_LOWPWR);

  // Load persisted settings from flash
  InternalFS.begin();
  loadSettings();

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
    // Push shutter count notification if ISR fired.
    if (shutterUpdated) {
      shutterUpdated = false;
      chrShutter.notify32(cfg.shutterCount);
    }
    delay(50);
    return;
  }

  // ── Advertisement cycle (not connected) ──────────────────────────────────
  float voltage = readBatteryVoltage();
  int   pct     = readBatteryPercentage(voltage);
  advertiseData(pct, voltage);
  delay(ADVERTISING_DURATION_MS);
  Bluefruit.Advertising.stop();

  // Flush any pending flash write (e.g. shutter count incremented by ISR)
  if (settingsDirty) {
    saveSettings();
    settingsDirty = false;
  }

  delay(SLEEP_INTERVAL_MS - ADVERTISING_DURATION_MS);
}

// ═════════════════════════════════════════════════════════════════════════════
// Flash storage
// ═════════════════════════════════════════════════════════════════════════════
void loadSettings() {
  // Apply safe defaults first
  memset(&cfg, 0, sizeof(cfg));
  cfg.version   = SETTINGS_VERSION;
  cfg.cellCount = 1;

  if (!InternalFS.exists(SETTINGS_FILE)) return;

  if (cfgFile.open(SETTINGS_FILE, FILE_O_READ)) {
    cfgFile.read(&cfg, sizeof(cfg));
    cfgFile.close();
  }

  // Reject data written by a different struct layout
  if (cfg.version != SETTINGS_VERSION) {
    memset(&cfg, 0, sizeof(cfg));
    cfg.version   = SETTINGS_VERSION;
    cfg.cellCount = 1;
  }
}

void saveSettings() {
  // Remove then rewrite (LittleFS doesn't support in-place overwrite reliably)
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
// GATT setup
// ═════════════════════════════════════════════════════════════════════════════

// Push current cfg values into GATT characteristics (called after boot and
// after factory reset so reads always reflect the stored state).
void populateCharacteristics() {
  size_t nameLen      = strlen(cfg.name);
  size_t groupNameLen = strlen(cfg.groupName);

  chrName.write(cfg.name, nameLen > 0 ? nameLen : 1);  // zero-length write can fail
  chrGroupId.write8(cfg.groupId);
  chrGroupName.write(cfg.groupName, groupNameLen > 0 ? groupNameLen : 1);
  chrDevType.write8(cfg.deviceType);
  chrChemistry.write8(cfg.chemistry);
  chrCellCount.write8(cfg.cellCount);
  chrShutter.write32(cfg.shutterCount);
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

  // Seed characteristics with values loaded from flash
  populateCharacteristics();
}

// ═════════════════════════════════════════════════════════════════════════════
// Advertising
// ═════════════════════════════════════════════════════════════════════════════
void advertiseData(int percentage, float voltage) {
  Bluefruit.Advertising.stop();
  Bluefruit.Advertising.clearData();
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);

  // Build flags byte:
  //   bit 0    = configured flag
  //   bits 1-2 = device type (0=battery, 1=camera, 2=strobe, 3=focus_light)
  //   bits 3-4 = battery chemistry (0=LiPo, 1=LiFePO4, 2=NiMH, 3=Alkaline)
  //   bits 5-7 = reserved (0)
  uint8_t flags = 0;
  if (cfg.configured)         flags |= 0x01;
  flags |= (cfg.deviceType & 0x03) << 1;
  flags |= (cfg.chemistry  & 0x03) << 3;

  uint16_t voltMv = (uint16_t)(voltage * 1000.0f);

  // Manufacturer-specific packet (company ID 0xFFFF = development/test):
  // [CompanyLow][CompanyHigh][pct][voltLo][voltHi][flags][groupId][cellCount][shutterLo][shutterHi]
  uint8_t mfgData[10];
  mfgData[0] = 0xFF;                              // Company ID low
  mfgData[1] = 0xFF;                              // Company ID high
  mfgData[2] = (uint8_t)percentage;              // Battery %
  mfgData[3] =  voltMv        & 0xFF;            // Voltage low byte
  mfgData[4] = (voltMv >> 8)  & 0xFF;            // Voltage high byte
  mfgData[5] = flags;                             // Flags byte
  mfgData[6] = cfg.groupId;                      // Group ID
  mfgData[7] = cfg.cellCount;                    // Cell count
  mfgData[8] =  cfg.shutterCount        & 0xFF;  // Shutter count low
  mfgData[9] = (cfg.shutterCount >> 8)  & 0xFF;  // Shutter count high

  Bluefruit.Advertising.addData(BLE_GAP_AD_TYPE_MANUFACTURER_SPECIFIC_DATA, mfgData, 10);

  // Device name goes in the scan response to stay within the 31-byte ad limit
  Bluefruit.ScanResponse.clearData();
  Bluefruit.ScanResponse.addName();

  Bluefruit.Advertising.setInterval(32, 32);         // 20 ms for fast discovery
  Bluefruit.Advertising.restartOnDisconnect(false);  // loop() manages restarts
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
  // loop() will restart advertising on the next iteration
}

// ═════════════════════════════════════════════════════════════════════════════
// GATT write callbacks
// Each: validate → update cfg → mark configured → save to flash
// ═════════════════════════════════════════════════════════════════════════════
static void markConfigured() { cfg.configured = 1; }

void onNameWrite(uint16_t h, BLECharacteristic* c, uint8_t* d, uint16_t l) {
  (void)h; (void)c;
  uint16_t len = (l < 20) ? l : 20;
  memcpy(cfg.name, d, len);
  cfg.name[len] = '\0';
  markConfigured();
  saveSettings();
  // Update the BLE local name so the next advertisement reflects the new name
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
  // App will disconnect; on next scan it will see the device as unconfigured
}

// ═════════════════════════════════════════════════════════════════════════════
// Camera shutter pulse ISR
// Fires on falling edge of SHUTTER_PIN. Debounced in software.
// Does NOT call BLE stack functions (not ISR-safe); sets flags for main loop.
// ═════════════════════════════════════════════════════════════════════════════
void onShutterPulse() {
  uint32_t now = millis();
  if (now - lastShutterMs < SHUTTER_DEBOUNCE_MS) return;
  lastShutterMs  = now;
  cfg.shutterCount++;
  settingsDirty  = true;  // save to flash at end of next loop iteration
  shutterUpdated = true;  // notify connected phone in loop()
}

// ═════════════════════════════════════════════════════════════════════════════
// Battery reading
// ═════════════════════════════════════════════════════════════════════════════
float readBatteryVoltage() {
  analogReference(AR_INTERNAL_3_0);
  analogReadResolution(10);
  int total = 0;
  for (int i = 0; i < 5; i++) {
    total += analogRead(BATTERY_PIN);
    delayMicroseconds(100);
  }
  // 3.0 V reference, 10-bit ADC, times divider ratio
  return ((float)(total / 5) / ADC_MAX_VALUE) * 3.6f * VOLTAGE_DIVIDER_RATIO;
}

int readBatteryPercentage(float voltage) {
  if (voltage > 4.2f) voltage = 4.2f;
  if (voltage < 3.0f) voltage = 3.0f;

  // Piecewise LiPo discharge curve approximation
  float pct;
  if      (voltage >= 4.1f) pct = 90.0f + (voltage - 4.1f) * 100.0f;
  else if (voltage >= 3.9f) pct = 70.0f + (voltage - 3.9f) * 100.0f;
  else if (voltage >= 3.7f) pct = 40.0f + (voltage - 3.7f) * 150.0f;
  else if (voltage >= 3.5f) pct = 20.0f + (voltage - 3.5f) * 100.0f;
  else if (voltage >= 3.3f) pct =  5.0f + (voltage - 3.3f) *  75.0f;
  else                      pct =         (voltage - 3.0f)  *  16.67f;

  int result = (int)pct;
  if (result > 100) result = 100;
  if (result < 0)   result = 0;
  return result;
}

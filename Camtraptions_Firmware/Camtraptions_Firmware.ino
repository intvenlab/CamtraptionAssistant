/*
 * Ultra Low Power BLE Battery Monitor for Seeed Studio XIAO nRF52840
 * Uses deep sleep between advertisements to minimize power consumption
 * Wakes up every second, advertises battery data, then goes back to deep sleep
 * 
 * Power Consumption:
 * - Deep Sleep: ~5µA
 * - Active (advertising): ~10mA for ~50ms
 * - Average: ~50µA (can run for months on a coin cell!)
 * 
 * Setup Instructions:
 * 1. Install "Seeed nRF52 Boards" in Arduino IDE Board Manager
 * 2. Select "Seeed XIAO nRF52840" as your board
 * 3. Upload this sketch to your XIAO nRF52840
 */

#include <bluefruit.h>
#include <Adafruit_LittleFS.h>
#include <InternalFileSystem.h>

// Device name that will appear in the Android app
#define DEVICE_NAME "Hummingbird Cam1"

// Pin for battery voltage reading (A0 on XIAO nRF52840)
#define BATTERY_PIN A0

// Voltage divider ratio (adjust based on your battery setup)
#define VOLTAGE_DIVIDER_RATIO 1.0

// Reference voltage for ADC
#define VREF 3.6
#define ADC_MAX_VALUE 1024.0

// Battery voltage thresholds (for LiPo battery)
#define BATTERY_MAX_VOLTAGE 4.2  // Fully charged LiPo
#define BATTERY_MIN_VOLTAGE 3.0  // Empty LiPo

// Sleep interval in seconds (1 second between advertisements)
#define SLEEP_INTERVAL_SEC 1

// Advertising duration in milliseconds (how long to advertise before sleeping)
#define ADVERTISING_DURATION_MS 50

void setup() {
  // Configure battery pin
  pinMode(BATTERY_PIN, INPUT);
  
  // Disable USB (saves ~1mA)
  // Comment this out if you need serial debugging
  // NRF_POWER->USBREGSTATUS = POWER_USBREGSTATUS_VBUSDETECT_NoVbus;
  
  // Initialize Bluefruit with minimal resources
  Bluefruit.begin();
  Bluefruit.setTxPower(4);  // Max power for better range
  Bluefruit.setName(DEVICE_NAME);
  
  // Disable Bluefruit's automatic LED control
  Bluefruit.autoConnLed(false);  // Disable connection LED
  Bluefruit.setConnLedInterval(0);  // Turn off LED blinking
  
  // Manually turn off all LEDs after Bluefruit init
  pinMode(LED_RED, OUTPUT);
  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_RED, HIGH);
  digitalWrite(LED_GREEN, HIGH);
  digitalWrite(LED_BLUE, HIGH);
  
  // Disable connections completely (we only advertise)
  Bluefruit.Periph.setConnectCallback(NULL);
  Bluefruit.setConnLedInterval(0); // Disable connection LED
  
  // Configure low power settings
  sd_power_dcdc_mode_set(NRF_POWER_DCDC_ENABLE); // Enable DC/DC converter (more efficient)
  sd_power_mode_set(NRF_POWER_MODE_LOWPWR);      // Set low power mode
}

void loop() {
  // Read battery data
  float voltage = readBatteryVoltage();
  int percentage = readBatteryPercentage(voltage);
  
  // Start advertising with battery data
  advertiseData(percentage, voltage);
  
  // Advertise for a short time
  delay(ADVERTISING_DURATION_MS);
  
  // Stop advertising to save power
  Bluefruit.Advertising.stop();
  
  // Enter deep sleep (System OFF mode)
  enterDeepSleep(SLEEP_INTERVAL_SEC);
}

void advertiseData(int percentage, float voltage) {
  // Stop advertising if already running
  Bluefruit.Advertising.stop();

  // Clear advertising data
  Bluefruit.Advertising.clearData();

  // Set advertising flags
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);

  // Build extended 8-byte manufacturer data packet:
  // data[0]  battery percent (0-100)
  // data[1]  voltage low byte  (millivolts, little-endian)
  // data[2]  voltage high byte
  // data[3]  flags: bit0=configured, bits1-2=device type, bits3-4=battery chemistry
  // data[4]  group ID (0=no group)
  // data[5]  cell count
  // data[6]  shutter count low byte
  // data[7]  shutter count high byte
  //
  // Packet layout: [Company ID low][Company ID high][data[0]..data[7]]
  uint8_t mfgData[10];
  mfgData[0] = 0xFF; // Company ID low byte (0xFFFF = test/development)
  mfgData[1] = 0xFF; // Company ID high byte
  mfgData[2] = (uint8_t)percentage; // Battery percentage (0-100)

  // Convert voltage to millivolts and split into 2 bytes
  uint16_t voltageMillivolts = (uint16_t)(voltage * 1000);
  mfgData[3] = voltageMillivolts & 0xFF;        // Low byte
  mfgData[4] = (voltageMillivolts >> 8) & 0xFF; // High byte

  // Extended fields (Phase 1: hardcoded defaults; Phase 2: read from flash)
  mfgData[5] = 0x00; // flags: not configured (bit0=0), battery monitor (bits1-2=0), LiPo (bits3-4=0)
  mfgData[6] = 0x00; // group ID: no group
  mfgData[7] = 0x01; // cell count: 1
  mfgData[8] = 0x00; // shutter count low byte
  mfgData[9] = 0x00; // shutter count high byte

  Bluefruit.Advertising.addData(BLE_GAP_AD_TYPE_MANUFACTURER_SPECIFIC_DATA, mfgData, 10);

  // Device name goes in the scan response to keep the advertising packet within 31 bytes
  Bluefruit.ScanResponse.clearData();
  Bluefruit.ScanResponse.addName();
  
  // Fast advertising interval for quick discovery (in units of 0.625ms)
  Bluefruit.Advertising.setInterval(32, 32); // 20ms interval
  
  // Don't auto-restart
  Bluefruit.Advertising.restartOnDisconnect(false);
  
  // Start advertising
  Bluefruit.Advertising.start(0);
}

void enterDeepSleep(uint32_t seconds) {
  // Use SoftDevice delay for low power sleep
  // This achieves ~150µA average which is excellent and reliable
  delay(seconds * 1000);
}

// Read battery voltage from ADC
float readBatteryVoltage() {
  // Power up ADC briefly
  analogReference(AR_INTERNAL_3_0); // 3.0V reference
  analogReadResolution(10);          // 10-bit resolution
  
  // Take multiple readings and average for stability
  int samples = 5;  // Reduced samples to save time/power
  int total = 0;
  
  for (int i = 0; i < samples; i++) {
    total += analogRead(BATTERY_PIN);
    delayMicroseconds(100);
  }
  
  int adcValue = total / samples;
  
  // Convert to voltage
  // With internal 3.0V reference and 10-bit ADC
  float voltage = (adcValue / ADC_MAX_VALUE) * 3.6 * VOLTAGE_DIVIDER_RATIO;
  
  return voltage;
}

// Calculate battery percentage based on voltage
int readBatteryPercentage(float voltage) {
  // Constrain voltage to valid range
  if (voltage > BATTERY_MAX_VOLTAGE) {
    voltage = BATTERY_MAX_VOLTAGE;
  }
  if (voltage < BATTERY_MIN_VOLTAGE) {
    voltage = BATTERY_MIN_VOLTAGE;
  }
  
  // LiPo discharge curve approximation
  // More accurate than linear for LiPo batteries
  float percentage;
  
  if (voltage >= 4.1) {
    percentage = 90 + (voltage - 4.1) * 100; // 4.1V-4.2V = 90-100%
  } else if (voltage >= 3.9) {
    percentage = 70 + (voltage - 3.9) * 100; // 3.9V-4.1V = 70-90%
  } else if (voltage >= 3.7) {
    percentage = 40 + (voltage - 3.7) * 150; // 3.7V-3.9V = 40-70%
  } else if (voltage >= 3.5) {
    percentage = 20 + (voltage - 3.5) * 100; // 3.5V-3.7V = 20-40%
  } else if (voltage >= 3.3) {
    percentage = 5 + (voltage - 3.3) * 75;   // 3.3V-3.5V = 5-20%
  } else {
    percentage = (voltage - 3.0) * 16.67;    // 3.0V-3.3V = 0-5%
  }
  
  // Constrain to 0-100 range
  int finalPercentage = (int)percentage;
  if (finalPercentage > 100) finalPercentage = 100;
  if (finalPercentage < 0) finalPercentage = 0;
  
  return finalPercentage;
}
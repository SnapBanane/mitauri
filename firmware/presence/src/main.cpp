// A dumb presence sensor. It listens for Mitauri's Bluetooth beacon and reports what it hears over USB.
// It can't tell whose beacon it is: the server checks the rotating token.
// Once given a key over USB it also broadcasts a signed beacon, so it can sit on plain power and the phone hears it.
#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <mbedtls/md.h>

static const uint16_t COMPANY_ID = 0xFFFF;
static const uint8_t MAGIC[2] = {'M', 'T'};
static const size_t TOKEN_BYTES = 8;
static const unsigned long HEARTBEAT_MS = 10000;

static const uint8_t BEACON_MAGIC[2] = {'M', 'S'};
static const size_t KEY_BYTES = 32;
static const unsigned long BEACON_WINDOW_MS = 60000;
static const uint32_t MAX_WINDOW = 0xFFFF;

static Preferences storage;
static uint8_t beaconKey[KEY_BYTES];
static bool hasBeaconKey = false;
static uint32_t bootCount = 0;
static uint32_t beaconWindow = 0;

static void broadcast() {
    // No clock survives a power cut, so a counter that only grows takes its place: the boot count, then minutes.
    const uint32_t counter = (bootCount << 16) | beaconWindow;
    const uint8_t counterBytes[4] = {uint8_t(counter >> 24), uint8_t(counter >> 16), uint8_t(counter >> 8), uint8_t(counter)};
    uint8_t digest[32];
    mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), beaconKey, KEY_BYTES, counterBytes, 4, digest);

    std::string data;
    data.push_back(char(COMPANY_ID & 0xFF));
    data.push_back(char(COMPANY_ID >> 8));
    data.append(reinterpret_cast<const char *>(BEACON_MAGIC), 2);
    data.append(reinterpret_cast<const char *>(counterBytes), 4);
    data.append(reinterpret_cast<const char *>(digest), TOKEN_BYTES);

    NimBLEAdvertisementData advert;
    advert.setFlags(0x06);
    advert.setManufacturerData(data);
    NimBLEAdvertising *advertising = NimBLEDevice::getAdvertising();
    advertising->stop();
    advertising->setAdvertisementData(advert);
    advertising->setAdvertisementType(BLE_GAP_CONN_MODE_NON);
    advertising->setMinInterval(400);  // 250 ms
    advertising->setMaxInterval(480);
    advertising->start();
}

static int hexValue(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

// Takes "KEY <64 hex digits>" from USB. The key is never printed back.
static void handleCommand(const String &line) {
    if (!line.startsWith("KEY ") || line.length() != 4 + KEY_BYTES * 2) return;
    uint8_t key[KEY_BYTES];
    for (size_t i = 0; i < KEY_BYTES; i++) {
        const int high = hexValue(line[4 + i * 2]), low = hexValue(line[5 + i * 2]);
        if (high < 0 || low < 0) return;
        key[i] = uint8_t(high << 4 | low);
    }
    storage.putBytes("key", key, KEY_BYTES);
    memcpy(beaconKey, key, KEY_BYTES);
    hasBeaconKey = true;
    broadcast();
    Serial.println("{\"key\":\"saved\"}");
}

static void readCommands() {
    static String line;
    while (Serial.available()) {
        const char c = Serial.read();
        if (c == '\n') {
            handleCommand(line);
            line = "";
        } else if (line.length() < 80) {
            line += c;
        }
    }
}

class Listener : public NimBLEAdvertisedDeviceCallbacks {
    void onResult(NimBLEAdvertisedDevice *device) override {
        if (!device->haveManufacturerData()) return;
        const std::string data = device->getManufacturerData();
        if (data.size() != 4 + TOKEN_BYTES) return;
        const uint8_t *bytes = reinterpret_cast<const uint8_t *>(data.data());
        if ((bytes[0] | (bytes[1] << 8)) != COMPANY_ID || bytes[2] != MAGIC[0] || bytes[3] != MAGIC[1]) return;
        Serial.print("{\"t\":\"");
        for (size_t i = 0; i < TOKEN_BYTES; i++) Serial.printf("%02x", bytes[4 + i]);
        Serial.printf("\",\"r\":%d}\n", device->getRSSI());
    }
};

void setup() {
    Serial.begin(115200);
    NimBLEDevice::init("");
    NimBLEScan *scan = NimBLEDevice::getScan();
    scan->setAdvertisedDeviceCallbacks(new Listener(), true);  // true: report repeat sightings
    scan->setActiveScan(false);  // only listens, and never transmits a request
    scan->setInterval(160);
    scan->setWindow(80);
    scan->start(0, nullptr, false);

    storage.begin("mitauri");
    bootCount = (storage.getUInt("boots", 0) + 1) & 0xFFFF;  // 65,000 power cuts away from wrapping
    storage.putUInt("boots", bootCount);
    hasBeaconKey = storage.getBytes("key", beaconKey, KEY_BYTES) == KEY_BYTES;
    if (hasBeaconKey) broadcast();
}

void loop() {
    static unsigned long last = 0;
    if (millis() - last >= HEARTBEAT_MS) {
        last = millis();
        Serial.println("{\"hb\":1}");
    }
    readCommands();
    const uint32_t window = millis() / BEACON_WINDOW_MS;
    if (hasBeaconKey && window != beaconWindow) {
        if (window > MAX_WINDOW) ESP.restart();  // about 45 days: a new boot count keeps the counter growing
        beaconWindow = window;
        broadcast();
    }
    delay(50);
}

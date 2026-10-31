// A dumb presence sensor. It listens for Mitauri's Bluetooth beacon and reports what it hears over USB.
// It holds no keys and can't tell whose beacon it is: the server checks the rotating token.
#include <Arduino.h>
#include <NimBLEDevice.h>

static const uint16_t COMPANY_ID = 0xFFFF;
static const uint8_t MAGIC[2] = {'M', 'T'};
static const size_t TOKEN_BYTES = 8;
static const unsigned long HEARTBEAT_MS = 10000;

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
}

void loop() {
    static unsigned long last = 0;
    if (millis() - last >= HEARTBEAT_MS) {
        last = millis();
        Serial.println("{\"hb\":1}");
    }
    delay(50);
}

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <ESP32Servo.h>

// ==================================================
// CONFIGURA AQUÍ EL ZAPATO
// ==================================================
#define SHOE_SIDE "A"   // Cambia a "B" para el segundo zapato

// ==================================================
// PINES
// ==================================================
const int servoPin = D1;

// ==================================================
// SERVO CONFIG
// ==================================================
Servo myservo;

const int US_BLOQUEO = 750;
const int US_DISPARO = 2350;

bool servoAttached = false;
unsigned long motorStart = 0;
bool motorOn = false;
uint32_t currentMotorPulseMs = 180;
unsigned long lastAdvertisingCheck = 0;

// ==================================================
// BLE CONFIG
// ==================================================
String bleNameString = String("SmartShoe_Oz_") + SHOE_SIDE;
const char* BLE_DEVICE_NAME = bleNameString.c_str();

static NimBLEUUID SERVICE_UUID("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
static NimBLEUUID CHAR_RX_UUID("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
static NimBLEUUID CHAR_TX_UUID("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

NimBLECharacteristic* txChar = nullptr;
bool deviceConnected = false;
String rxBuffer = "";

// ==================================================
// SCHEDULER
// ==================================================
struct ScheduledEvent {
  bool active = false;
  uint32_t targetMs = 0;
  uint16_t durationMs = 150;
  uint8_t intensity = 180;
};

const int MAX_EVENTS = 12;
ScheduledEvent eventQueue[MAX_EVENTS];

// ==================================================
// SERVO
// ==================================================
void ensureServoAttached() {
  if (!servoAttached) {
    myservo.attach(servoPin, 500, 2500);
    servoAttached = true;
  }
}

void moveServoSmooth(int fromUs, int toUs, int stepUs = 20, int stepDelay = 3) {
  ensureServoAttached();

  if (fromUs < toUs) {
    for (int us = fromUs; us <= toUs; us += stepUs) {
      myservo.writeMicroseconds(us);
      delay(stepDelay);
    }
  } else {
    for (int us = fromUs; us >= toUs; us -= stepUs) {
      myservo.writeMicroseconds(us);
      delay(stepDelay);
    }
  }

  myservo.writeMicroseconds(toUs);
}

void motorStop() {
  Serial.println("Volviendo a bloqueo...");
  myservo.writeMicroseconds(US_BLOQUEO);
  motorOn = false;
}

void triggerMotor(uint16_t durationMs, uint8_t intensity) {
  Serial.print("DISPARO SERVO ");
  Serial.print(SHOE_SIDE);
  Serial.print(" | duration=");
  Serial.print(durationMs);
  Serial.print(" | intensity=");
  Serial.println(intensity);

  currentMotorPulseMs = durationMs;

  moveServoSmooth(US_BLOQUEO, US_DISPARO, 20, 3);

  motorOn = true;
  motorStart = millis();
}

// ==================================================
// NOTIFY
// ==================================================
void notifyText(const char* msg) {
  if (txChar && deviceConnected) {
    txChar->setValue(msg);
    txChar->notify();
  }
}

// ==================================================
// EVENT QUEUE
// ==================================================
bool enqueueEvent(uint32_t targetMs, uint16_t durationMs, uint8_t intensity) {
  for (int i = 0; i < MAX_EVENTS; i++) {
    if (!eventQueue[i].active) {
      eventQueue[i].active = true;
      eventQueue[i].targetMs = targetMs;
      eventQueue[i].durationMs = durationMs;
      eventQueue[i].intensity = intensity;

      Serial.print("Evento programado ");
      Serial.print(SHOE_SIDE);
      Serial.print(" -> targetMs=");
      Serial.print(targetMs);
      Serial.print(" duration=");
      Serial.print(durationMs);
      Serial.print(" intensity=");
      Serial.println(intensity);

      return true;
    }
  }

  Serial.println("Cola llena");
  return false;
}

void clearScheduledEvents() {
  for (int i = 0; i < MAX_EVENTS; i++) {
    eventQueue[i].active = false;
  }
}

void processScheduledEvents() {
  uint32_t now = millis();

  for (int i = 0; i < MAX_EVENTS; i++) {
    if (!eventQueue[i].active) continue;

    if ((int32_t)(now - eventQueue[i].targetMs) >= 0) {
      eventQueue[i].active = false;
      triggerMotor(eventQueue[i].durationMs, eventQueue[i].intensity);
    }
  }
}

// ==================================================
// COMMAND PARSER
// ==================================================
void processLine(String line) {
  line.trim();
  if (line.length() == 0) return;

  Serial.print("RX ");
  Serial.print(SHOE_SIDE);
  Serial.print(": ");
  Serial.println(line);

  if (line == "B") {
    bool ok = enqueueEvent(millis() + 10, 150, 180);
    notifyText(ok ? "OK:B" : "ERR:QUEUE_FULL");
    return;
  }

  if (line == "STOP") {
    clearScheduledEvents();
    motorStop();
    notifyText("OK:STOP");
    return;
  }

  if (line.startsWith("PULSE:")) {
    int v = line.substring(6).toInt();

    if (v >= 10 && v <= 10000) {
      currentMotorPulseMs = (uint32_t)v;
      notifyText("OK:PULSE");
    } else {
      notifyText("ERR:PULSE_RANGE");
    }
    return;
  }
  // Formato relativo: D,delayMs,durationMs,intensity
// Ejemplo: D,30,70,180
if (line.startsWith("D,")) {
  int p1 = line.indexOf(',');
  int p2 = line.indexOf(',', p1 + 1);
  int p3 = line.indexOf(',', p2 + 1);

  if (p1 < 0 || p2 < 0 || p3 < 0) {
    notifyText("ERR:D_FORMAT");
    return;
  }

  uint32_t delayMs = (uint32_t) line.substring(p1 + 1, p2).toInt();
  uint16_t durationMs = (uint16_t) line.substring(p2 + 1, p3).toInt();
  uint8_t intensity = (uint8_t) line.substring(p3 + 1).toInt();

  if (durationMs < 10 || durationMs > 10000) {
    notifyText("ERR:DURATION_RANGE");
    return;
  }

  uint32_t targetMs = millis() + delayMs;

  bool ok = enqueueEvent(targetMs, durationMs, intensity);
  notifyText(ok ? "OK:D" : "ERR:QUEUE_FULL");
  return;
}

  // Formato nuevo: T,targetMs,durationMs,intensity
  // Ejemplo: T,45230,150,180
  if (line.startsWith("T,")) {
    int p1 = line.indexOf(',');
    int p2 = line.indexOf(',', p1 + 1);
    int p3 = line.indexOf(',', p2 + 1);

    if (p1 < 0 || p2 < 0 || p3 < 0) {
      notifyText("ERR:T_FORMAT");
      return;
    }

    uint32_t targetMs = (uint32_t) line.substring(p1 + 1, p2).toInt();
    uint16_t durationMs = (uint16_t) line.substring(p2 + 1, p3).toInt();
    uint8_t intensity = (uint8_t) line.substring(p3 + 1).toInt();

    if (durationMs < 10 || durationMs > 10000) {
      notifyText("ERR:DURATION_RANGE");
      return;
    }

    bool ok = enqueueEvent(targetMs, durationMs, intensity);
    notifyText(ok ? "OK:T" : "ERR:QUEUE_FULL");
    return;
  }

  notifyText("ERR:UNKNOWN");
}
void startBleAdvertising() {
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->stop();
  delay(100);

  adv->addServiceUUID(SERVICE_UUID);
  adv->setName(BLE_DEVICE_NAME);
  adv->start();

  Serial.print("BLE advertising activo como ");
  Serial.println(BLE_DEVICE_NAME);
}
// ==================================================
// BLE CALLBACKS
// ==================================================
class RxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c) override {
    std::string value = c->getValue();
    if (value.empty()) return;

    for (char ch : value) {
      if (ch == '\n') {
        processLine(rxBuffer);
        rxBuffer = "";
      } else if (ch != '\r') {
        rxBuffer += ch;
      }
    }

    if (rxBuffer == "B" || rxBuffer == "STOP" || rxBuffer.startsWith("PULSE:")) {
      processLine(rxBuffer);
      rxBuffer = "";
    }
  }
};

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* pServer) override {
    deviceConnected = true;
    Serial.print("Cliente BLE conectado a zapato ");
    Serial.println(SHOE_SIDE);
  }

  void onDisconnect(NimBLEServer* pServer) override {
    deviceConnected = false;
    Serial.print("Cliente BLE desconectado de zapato ");
    Serial.println(SHOE_SIDE);

    //delay(100);
    delay(300);
    startBleAdvertising();
  }
};

// ==================================================
// SETUP
// ==================================================
void setup() {
  Serial.begin(115200);
  delay(800);

  Serial.print("Iniciando ");
  Serial.println(BLE_DEVICE_NAME);

  ESP32PWM::allocateTimer(0);
  myservo.setPeriodHertz(50);

  ensureServoAttached();
  myservo.writeMicroseconds(US_BLOQUEO);
  delay(600);

  Serial.println("Servo en posición de bloqueo");

  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* service = server->createService(SERVICE_UUID);

  NimBLECharacteristic* rxChar = service->createCharacteristic(
    CHAR_RX_UUID,
    NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR
  );
  rxChar->setCallbacks(new RxCallbacks());

  txChar = service->createCharacteristic(
    CHAR_TX_UUID,
    NIMBLE_PROPERTY::NOTIFY
  );

  service->start();

  startBleAdvertising();

  Serial.print("BLE anunciando como ");
  Serial.println(BLE_DEVICE_NAME);
}

// ==================================================
// LOOP
// ==================================================
void loop() {
  processScheduledEvents();

  if (motorOn && (millis() - motorStart >= currentMotorPulseMs)) {
    motorStop();
  }
  if (!deviceConnected && millis() - lastAdvertisingCheck > 5000) {
    lastAdvertisingCheck = millis();
    startBleAdvertising();
  }
}
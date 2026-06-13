#include <Wire.h>
#include <MPU6050.h>
#include <VL53L0X.h>
#include <WiFi.h>
#include <WebServer.h>

// -------------------- Pins --------------------
#define MOTOR_PIN    23
#define BUZZER_PIN   19
#define LED_PIN      2

#define US1_TRIG_PIN 5
#define US1_ECHO_PIN 18
#define US2_TRIG_PIN 17
#define US2_ECHO_PIN 25

#define I2C_SDA 21
#define I2C_SCL 22

// -------------------- Distance thresholds --------------------
#define ALERT_START_CM 30
#define CRITICAL_CM    5

// -------------------- Timing --------------------
#define READ_INTERVAL_MS          90
#define DASHBOARD_LOG_INTERVAL_MS 1000
#define WIFI_CHECK_INTERVAL_MS    5000
#define PULSE_TIMEOUT             25000UL
#define TOF_TIMEOUT_MS            120
#define TOF_BUDGET_US             33000

// -------------------- WiFi --------------------
const char* SSID1 = "YOUR_WIFI_NAME";
const char* PASSWORD1 = "YOUR_WIFI_PASSWORD";
const char* SSID12 = "YOUR_WIFI_NAME";
const char* PASSWORD2 = "YOUR_WIFI_PASSWORD";

// Active credentials (set during connection attempt)
const char* activeSSID     = nullptr;
const char* activePassword = nullptr;

// -------------------- Buzzer --------------------
#define BEEP_FREQ     1800
#define BEEP_ON_MS    60

// -------------------- Sensors --------------------
MPU6050   mpu;
VL53L0X   tof;
WebServer server(80);

bool mpuReady = false;
bool tofReady = false;

// -------------------- MPU --------------------
int16_t ax=0,ay=0,az=0,gx=0,gy=0,gz=0;
float   axG=0,ayG=0,azG=0;

// -------------------- Distances --------------------
int   ultrasonic1Cm=999, ultrasonic2Cm=999, tofMm=9999;
float ultrasonic1ValueCm=999, ultrasonic2ValueCm=999, tofValueCm=999;
float rawDistanceCm=999, distanceCm=999, previousDistanceCm=999;

String chosenSensor = "none";
String alertLevel   = "clear";

// -------------------- State --------------------
bool motorOn=false, buzzerOn=false;
bool tilt=false, slopeDown=false, slopeUp=false;
bool tiltLeft=false, tiltRight=false;
bool shake=false, emergency=false;

// -------------------- Timers --------------------
unsigned long lastReadMs=0, lastWifiCheckMs=0, lastLogMs=0;
unsigned long lastLedMs=0,  lastSirenMs=0;
unsigned long beepTimer=0;

bool ledState    = false;
bool beepOn      = false;
bool sirenToggle = false;

// ============================================================
//  BUZZER
// ============================================================

void buzzerTone(uint32_t hz) {
  ledcWriteTone(BUZZER_PIN, hz);
  buzzerOn = true;
}

void buzzerOff() {
  ledcWriteTone(BUZZER_PIN, 0);
  buzzerOn = false;
}

// ============================================================
//  COMPUTE BEEP GAP FROM DISTANCE
//  30cm → 800ms gap (slow, relaxed)
//  15cm → 300ms gap (medium)
//   5cm →   0ms gap (solid continuous tone)
//  Linear mapping between ALERT_START_CM and CRITICAL_CM
// ============================================================

unsigned long beepGapMs(float cm) {
  if (cm >= ALERT_START_CM) return 800;
  if (cm <= CRITICAL_CM)    return 0;
  float ratio = (cm - CRITICAL_CM) / (float)(ALERT_START_CM - CRITICAL_CM);
  return (unsigned long)(ratio * 800.0f);
}

// ============================================================
//  LED
// ============================================================

void updateLed() {
  unsigned long now = millis();

  if (emergency) {
    digitalWrite(LED_PIN, HIGH);
    return;
  }

  if (alertLevel == "clear") {
    digitalWrite(LED_PIN, LOW);
    return;
  }

  unsigned long gap     = beepGapMs(distanceCm);
  unsigned long blinkMs = (gap == 0) ? 0 : max(50UL, gap / 2);

  if (blinkMs == 0) {
    digitalWrite(LED_PIN, HIGH);
    return;
  }

  if (now - lastLedMs >= blinkMs) {
    lastLedMs = now;
    ledState  = !ledState;
    digitalWrite(LED_PIN, ledState ? HIGH : LOW);
  }
}

// ============================================================
//  ULTRASONIC
// ============================================================

int readUltrasonicCm(int trig, int echo) {
  digitalWrite(trig, LOW);  delayMicroseconds(3);
  digitalWrite(trig, HIGH); delayMicroseconds(10);
  digitalWrite(trig, LOW);
  long d = pulseIn(echo, HIGH, PULSE_TIMEOUT);
  if (d == 0) return 999;
  int cm = (int)(d * 0.0343f / 2.0f);
  return (cm <= 1 || cm > 400) ? 999 : cm;
}

// ============================================================
//  TOF
// ============================================================

int readTofMm() {
  if (!tofReady) return 9999;
  uint16_t mm = tof.readRangeSingleMillimeters();
  if (tof.timeoutOccurred() || mm <= 0 || mm > 2000) return 9999;
  return (int)mm;
}

// ============================================================
//  SENSOR FUSION
// ============================================================

float chooseBestDistanceCm(float us1, float us2, float tc) {
  bool v1 = us1 > 1 && us1 < 999;
  bool v2 = us2 > 1 && us2 < 999;
  bool vt = tc  > 1 && tc  < 999;
  chosenSensor = "none";
  float best = 999;
  if (v1)             { best = us1; chosenSensor = "ultrasonic_1"; }
  if (v2 && us2<best) { best = us2; chosenSensor = "ultrasonic_2"; }
  if (vt && tc <best) { best = tc;  chosenSensor = "tof"; }
  if (best >= 999) chosenSensor = "none";
  return best;
}

float smoothDistance(float cur, float prev) {
  if (cur  >= 999) return cur;
  if (prev >= 999) return cur;
  if (cur - prev > 80) return prev;
  return (cur < prev)
    ? cur * 0.75f + prev * 0.25f
    : cur * 0.35f + prev * 0.65f;
}

void updateDistance() {
  ultrasonic1Cm = readUltrasonicCm(US1_TRIG_PIN, US1_ECHO_PIN);
  delay(8);
  ultrasonic2Cm = readUltrasonicCm(US2_TRIG_PIN, US2_ECHO_PIN);
  tofMm         = readTofMm();

  ultrasonic1ValueCm = (ultrasonic1Cm < 999) ? (float)ultrasonic1Cm : 999;
  ultrasonic2ValueCm = (ultrasonic2Cm < 999) ? (float)ultrasonic2Cm : 999;
  tofValueCm         = (tofMm < 9999)        ? tofMm / 10.0f        : 999;

  rawDistanceCm      = chooseBestDistanceCm(ultrasonic1ValueCm, ultrasonic2ValueCm, tofValueCm);
  distanceCm         = smoothDistance(rawDistanceCm, previousDistanceCm);
  previousDistanceCm = distanceCm;

  if      (distanceCm <= CRITICAL_CM)    alertLevel = "critical";
  else if (distanceCm <= 12)             alertLevel = "danger";
  else if (distanceCm <= 20)             alertLevel = "warning";
  else if (distanceCm <= ALERT_START_CM) alertLevel = "near";
  else                                   alertLevel = "clear";
}

// ============================================================
//  MPU
// ============================================================

void updateMpuFlags() {
  if (!mpuReady) return;
  mpu.getMotion6(&ax,&ay,&az,&gx,&gy,&gz);
  axG = ax / 16384.0f;
  ayG = ay / 16384.0f;
  azG = az / 16384.0f;
  slopeDown = axG >  0.45f;
  slopeUp   = axG < -0.45f;
  tiltLeft  = ayG >  0.45f;
  tiltRight = ayG < -0.45f;
  shake     = abs(gx)>18000 || abs(gy)>18000 || abs(gz)>18000;
  tilt      = slopeDown || slopeUp || tiltLeft || tiltRight;
  emergency = shake || abs(axG)>2.0f || abs(ayG)>2.0f || abs(azG-1.0f)>2.0f;
}

// ============================================================
//  MOTOR
// ============================================================

void setMotor(bool on) {
  motorOn = on;
  digitalWrite(MOTOR_PIN, on ? HIGH : LOW);
}

void stopAlerts() {
  beepOn = false;
  setMotor(false);
  buzzerOff();
}

// ============================================================
//  CORE BEEP ENGINE
// ============================================================

void updateAlertOutputs() {
  unsigned long now = millis();

  // --- EMERGENCY: warbling siren ---
  if (emergency) {
    setMotor(true);
    if (now - lastSirenMs >= 70) {
      lastSirenMs  = now;
      sirenToggle  = !sirenToggle;
      buzzerTone(sirenToggle ? 3000 : 900);
    }
    return;
  }

  // --- CLEAR: silence ---
  if (distanceCm <= 0 || distanceCm > ALERT_START_CM) {
    stopAlerts();
    return;
  }

  unsigned long gap = beepGapMs(distanceCm);

  // --- CONTINUOUS tone when <=5cm ---
  if (gap == 0) {
    setMotor(true);
    buzzerTone(BEEP_FREQ);
    beepOn = true;
    return;
  }

  // --- BEEPING ---
  if (beepOn) {
    if (now - beepTimer >= BEEP_ON_MS) {
      beepOn = false;
      beepTimer = now;
      buzzerOff();
      setMotor(false);
    }
  } else {
    if (now - beepTimer >= gap) {
      beepOn    = true;
      beepTimer = now;
      buzzerTone(BEEP_FREQ);
      setMotor(true);
    }
  }
}

// ============================================================
//  SENSOR CYCLE
// ============================================================

void readSensors() {
  updateDistance();
  updateMpuFlags();
  updateAlertOutputs();
  updateLed();
}

// ============================================================
//  WEB SERVER
// ============================================================

String boolJson(bool v) { return v ? "true" : "false"; }

void sendCors() {
  server.sendHeader("Access-Control-Allow-Origin",  "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}

void handleData() {
  String j = "{";
  j += "\"distance\":"       + String(distanceCm,1)    + ",";
  j += "\"rawDistance\":"    + String(rawDistanceCm,1) + ",";
  j += "\"alertLevel\":\""   + alertLevel              + "\",";
  j += "\"chosenSensor\":\"" + chosenSensor            + "\",";
  j += "\"ultrasonic1_cm\":" + String(ultrasonic1Cm)   + ",";
  j += "\"ultrasonic2_cm\":" + String(ultrasonic2Cm)   + ",";
  j += "\"tof_mm\":"         + String(tofMm)           + ",";
  j += "\"tof_cm\":"         + String(tofValueCm,1)    + ",";
  j += "\"tofReady\":"       + boolJson(tofReady)      + ",";
  j += "\"tilt\":"           + boolJson(tilt)          + ",";
  j += "\"slopeDown\":"      + boolJson(slopeDown)     + ",";
  j += "\"slopeUp\":"        + boolJson(slopeUp)       + ",";
  j += "\"tiltLeft\":"       + boolJson(tiltLeft)      + ",";
  j += "\"tiltRight\":"      + boolJson(tiltRight)     + ",";
  j += "\"shake\":"          + boolJson(shake)         + ",";
  j += "\"emergency\":"      + boolJson(emergency)     + ",";
  j += "\"ax\":"  + String(ax)  +",\"ay\":"  + String(ay)  +",\"az\":"  + String(az)  + ",";
  j += "\"gx\":"  + String(gx)  +",\"gy\":"  + String(gy)  +",\"gz\":"  + String(gz)  + ",";
  j += "\"axG\":" + String(axG,3)+",\"ayG\":" + String(ayG,3)+",\"azG\":" + String(azG,3) + ",";
  j += "\"motor\":"  + boolJson(motorOn)  + ",";
  j += "\"buzzer\":" + boolJson(buzzerOn);
  j += "}";
  sendCors();
  server.send(200, "application/json", j);
}

void handleStatus() {
  String t = "VisionAid ESP32\n";
  t += "Distance: " + String(distanceCm,1) + " cm\n";
  t += "Alert: "    + alertLevel           + "\n";
  t += "Motor: "    + String(motorOn   ? "ON" : "OFF") + "\n";
  t += "Buzzer: "   + String(buzzerOn  ? "ON" : "OFF") + "\n";
  t += "Emergency: "+ String(emergency ? "YES" : "NO") + "\n";
  sendCors();
  server.send(200, "text/plain", t);
}

void handleRoot() {
  String html = R"rawhtml(
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>VisionAid ESP32 Dashboard</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:'Segoe UI',sans-serif;background:#0f1117;color:#e0e0e0;min-height:100vh;padding:20px}
h1{text-align:center;font-size:1.6rem;margin-bottom:24px;color:#7eb8ff;letter-spacing:1px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:16px;max-width:1100px;margin:0 auto}
.card{background:#1a1d27;border-radius:12px;padding:20px;border:1px solid #2a2d3a;text-align:center}
.card h3{font-size:.78rem;text-transform:uppercase;letter-spacing:1px;color:#888;margin-bottom:10px}
.val{font-size:1.8rem;font-weight:700;color:#7eb8ff;word-break:break-word}
.unit{font-size:.85rem;color:#666;margin-top:4px}
.badge{display:inline-block;padding:6px 18px;border-radius:20px;font-weight:700;font-size:.9rem;margin-top:8px}
.on{background:#1a3a1a;color:#4caf50;border:1px solid #4caf50}
.off{background:#2a1a1a;color:#f44336;border:1px solid #f44336}
footer{text-align:center;margin-top:28px;font-size:.75rem;color:#444}
</style>
</head>
<body>
<h1>VisionAid ESP32 Dashboard</h1>
<div class="grid">
  <div class="card"><h3>Final Distance</h3><div class="val" id="distance">--</div><div class="unit">cm</div></div>
  <div class="card"><h3>Chosen Sensor</h3><div class="val" id="chosen">--</div></div>
  <div class="card"><h3>Alert Level</h3><div class="val" id="alert">--</div></div>
  <div class="card"><h3>Ultrasonic 1</h3><div class="val" id="us1">--</div><div class="unit">cm</div></div>
  <div class="card"><h3>Ultrasonic 2</h3><div class="val" id="us2">--</div><div class="unit">cm</div></div>
  <div class="card"><h3>VL53L0X ToF</h3><div class="val" id="tof">--</div><div class="unit">mm</div></div>
  <div class="card"><h3>Accel X</h3><div class="val" id="ax">--</div><div class="unit">raw</div></div>
  <div class="card"><h3>Accel Y</h3><div class="val" id="ay">--</div><div class="unit">raw</div></div>
  <div class="card"><h3>Accel Z</h3><div class="val" id="az">--</div><div class="unit">raw</div></div>
  <div class="card"><h3>Gyro X</h3><div class="val" id="gx">--</div><div class="unit">raw</div></div>
  <div class="card"><h3>Gyro Y</h3><div class="val" id="gy">--</div><div class="unit">raw</div></div>
  <div class="card"><h3>Gyro Z</h3><div class="val" id="gz">--</div><div class="unit">raw</div></div>
  <div class="card"><h3>Motor</h3><div id="motor" class="badge off">OFF</div></div>
  <div class="card"><h3>Buzzer</h3><div id="buzzer" class="badge off">OFF</div></div>
  <div class="card"><h3>Emergency</h3><div id="emergency" class="badge off">NO</div></div>
</div>
<footer>Open /data for JSON · /status for text</footer>
<script>
async function poll(){
  try{
    const d=await(await fetch('/data')).json();
    document.getElementById('distance').textContent=d.distance>=999?'N/A':d.distance;
    document.getElementById('chosen').textContent=d.chosenSensor;
    document.getElementById('alert').textContent=d.alertLevel;
    document.getElementById('us1').textContent=d.ultrasonic1_cm>=999?'N/A':d.ultrasonic1_cm;
    document.getElementById('us2').textContent=d.ultrasonic2_cm>=999?'N/A':d.ultrasonic2_cm;
    document.getElementById('tof').textContent=d.tof_mm>=9999?'N/A':d.tof_mm;
    document.getElementById('ax').textContent=d.ax;
    document.getElementById('ay').textContent=d.ay;
    document.getElementById('az').textContent=d.az;
    document.getElementById('gx').textContent=d.gx;
    document.getElementById('gy').textContent=d.gy;
    document.getElementById('gz').textContent=d.gz;
    setBadge('motor',d.motor,'ON','OFF');
    setBadge('buzzer',d.buzzer,'ON','OFF');
    setBadge('emergency',d.emergency,'YES','NO');
  }catch(e){}
}
function setBadge(id,on,y,n){
  const el=document.getElementById(id);
  el.textContent=on?y:n;
  el.className='badge '+(on?'on':'off');
}
poll();
setInterval(poll,500);
</script>
</body>
</html>
)rawhtml";
  sendCors();
  server.send(200, "text/html", html);
}

// ============================================================
//  WIFI — try SSID1 first, fall back to SSID2
// ============================================================

bool connectToWiFi(const char* ssid, const char* password, int maxAttempts = 40) {
  Serial.printf("[WiFi] Trying %s", ssid);
  WiFi.disconnect(true);
  delay(200);
  WiFi.begin(ssid, password);
  for (int i = 0; i < maxAttempts && WiFi.status() != WL_CONNECTED; i++) {
    delay(300);
    Serial.print(".");
  }
  Serial.println();
  return WiFi.status() == WL_CONNECTED;
}

void checkWiFiReconnect() {
  unsigned long now = millis();
  if (now - lastWifiCheckMs < WIFI_CHECK_INTERVAL_MS) return;
  lastWifiCheckMs = now;
  if (WiFi.status() == WL_CONNECTED) return;

  Serial.println("[WiFi] Disconnected — reconnecting...");
  if (activeSSID != nullptr) {
    // Try last known good network first
    if (connectToWiFi(activeSSID, activePassword)) {
      Serial.printf("[WiFi] Reconnected to %s  IP: %s\n",
        activeSSID, WiFi.localIP().toString().c_str());
      return;
    }
  }
  // Fall back: try both
  if (connectToWiFi(SSID1, PASSWORD1)) {
    activeSSID     = SSID1;
    activePassword = PASSWORD1;
    Serial.printf("[WiFi] Connected to %s  IP: %s\n",
      SSID1, WiFi.localIP().toString().c_str());
  } else if (connectToWiFi(SSID2, PASSWORD2)) {
    activeSSID     = SSID2;
    activePassword = PASSWORD2;
    Serial.printf("[WiFi] Connected to %s  IP: %s\n",
      SSID2, WiFi.localIP().toString().c_str());
  } else {
    Serial.println("[WiFi] Both networks unreachable");
  }
}

// ============================================================
//  SERIAL LOG
// ============================================================

void logStatus() {
  unsigned long now = millis();
  if (now - lastLogMs < DASHBOARD_LOG_INTERVAL_MS) return;
  lastLogMs = now;
  Serial.printf("D=%.1fcm | gap=%lums | %s | Alert=%s | Motor=%s | Buzzer=%s | Emg=%s\n",
    distanceCm, beepGapMs(distanceCm), chosenSensor.c_str(), alertLevel.c_str(),
    motorOn?"ON":"OFF", buzzerOn?"ON":"OFF", emergency?"YES":"NO");
}

void printLinks() {
  if (WiFi.status() != WL_CONNECTED) return;
  Serial.println("================================");
  Serial.printf("Dashboard: http://%s\n",      WiFi.localIP().toString().c_str());
  Serial.printf("JSON:      http://%s/data\n",  WiFi.localIP().toString().c_str());
  Serial.println("================================");
}

// ============================================================
//  SETUP
// ============================================================

void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n===== VisionAid ESP32 Boot =====");

  pinMode(MOTOR_PIN, OUTPUT);
  pinMode(LED_PIN,   OUTPUT);
  pinMode(US1_TRIG_PIN, OUTPUT); pinMode(US1_ECHO_PIN, INPUT);
  pinMode(US2_TRIG_PIN, OUTPUT); pinMode(US2_ECHO_PIN, INPUT);
  digitalWrite(US1_TRIG_PIN, LOW);
  digitalWrite(US2_TRIG_PIN, LOW);
  digitalWrite(LED_PIN, LOW);

  ledcAttach(BUZZER_PIN, 2000, 8);
  ledcWriteTone(BUZZER_PIN, 0);

  stopAlerts();

  Wire.begin(I2C_SDA, I2C_SCL);
  Wire.setClock(400000);
  delay(100);

  Serial.print("[MPU6050] Init... ");
  mpu.initialize();
  mpuReady = mpu.testConnection();
  Serial.println(mpuReady ? "OK" : "FAILED");

  Serial.print("[VL53L0X] Init... ");
  tof.setTimeout(TOF_TIMEOUT_MS);
  if (tof.init()) {
    tofReady = true;
    tof.setMeasurementTimingBudget(TOF_BUDGET_US);
    Serial.println("OK");
  } else {
    tofReady = false;
    Serial.println("FAILED - ultrasonic only");
  }

  // Try SSID1 first, fall back to SSID2
  WiFi.mode(WIFI_STA);
  if (connectToWiFi(SSID1, PASSWORD1)) {
    activeSSID     = SSID1;
    activePassword = PASSWORD1;
    Serial.printf("[WiFi] Connected to %s  IP: %s\n",
      SSID1, WiFi.localIP().toString().c_str());
  } else if (connectToWiFi(SSID2, PASSWORD2)) {
    activeSSID     = SSID2;
    activePassword = PASSWORD2;
    Serial.printf("[WiFi] Connected to %s  IP: %s\n",
      SSID2, WiFi.localIP().toString().c_str());
  } else {
    Serial.println("[WiFi] Both networks FAILED - offline mode");
  }

  server.on("/",       handleRoot);
  server.on("/data",   handleData);
  server.on("/sensor", handleData);
  server.on("/status", handleStatus);
  server.begin();
  Serial.println("[Web] Server ready");
  printLinks();
}

// ============================================================
//  LOOP
// ============================================================

void loop() {
  server.handleClient();
  checkWiFiReconnect();

  unsigned long now = millis();
  if (now - lastReadMs >= READ_INTERVAL_MS) {
    lastReadMs = now;
    readSensors();
  }

  logStatus();
}

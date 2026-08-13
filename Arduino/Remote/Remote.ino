#include <M5Unified.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
// ======================== 设备标识 ========================
const char* BLE_DEVICE_NAME = "ARDUINO NESSO N1";
#define MANUFACTURER_COMPANY_ID 0xE5E5
#define MANUFACTURER_PRODUCT_ID 0x01
#define SERVICE_UUID "0000ffe0-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_VIDEO_UUID "0000ffe1-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_CTRL_UUID "0000ffe2-0000-1000-8000-00805f9b34fb"
// ======================== 视频双缓冲区 ========================
#define BUF_SIZE 16384 
uint8_t jpegBufA[BUF_SIZE];
uint8_t jpegBufB[BUF_SIZE];
uint8_t* writeBuf = jpegBufA;
uint8_t* readBuf = jpegBufB;
volatile uint16_t expectLen = 0;
volatile uint16_t recvCnt = 0;
volatile uint16_t readyFrameLen = 0;
volatile bool frameReady = false;
portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
// ======================== BLE 对象 ========================
NimBLEClient* pClient = nullptr;
NimBLERemoteCharacteristic* pVideoChar = nullptr;
NimBLERemoteCharacteristic* pCtrlChar = nullptr;
// ======================== 状态机 ========================
enum SystemState { STATE_BOOT,
                   STATE_AUTO_CONNECT,
                   STATE_SCANNING,
                   STATE_DEVICE_LIST,
                   STATE_CONNECTING,
                   STATE_VIDEO_MODE,
                   STATE_RECONNECTING,
                   STATE_OFFLINE };
SystemState sysState = STATE_BOOT;
// ======================== 设备发现 ========================
#define MAX_DEVICES 8
struct DeviceInfo {
  std::string name;
  std::string addrStr;
};
DeviceInfo foundDevices[MAX_DEVICES];
int deviceCount = 0;
int scrollOffset = 0;
int selectedDevice = -1;
// ======================== NVS 持久化 ========================
Preferences prefs;
String savedAddrStr = "";
bool hasSavedDevice = false;
// ======================== 连接状态 ========================
volatile bool connected = false;
volatile bool needReconnect = false;
#define MAX_RECONNECT_TIMES 5
int reconnectCount = 0;
bool deviceOffline = false;
bool ledState = false;
// ======================== 摇杆 / IMU ========================
int8_t lastX = 0;
int8_t lastY = 0;
uint32_t lastImuSendTime = 0;
#define IMU_SEND_INTERVAL 30
// ======================== 触摸：云台控制 ========================
#define FILTER_SIZE 3 
int16_t deltaYBuffer[FILTER_SIZE] = { 0 };
uint8_t filterIndex = 0;
int32_t totalDeltaY = 0;
const int16_t SLIDE_TRIGGER = 15;
const uint32_t COOL_MS = 60;
uint32_t lastSendTime = 0;
bool isTouching = false;
int16_t lastTouchY = 0;
// ======================== 触摸：点击检测 ========================
uint32_t touchStartTime = 0;
int16_t touchStartX = 0;
int16_t touchStartY = 0;
bool touchHasMoved = false;
const uint32_t TAP_DURATION_MS = 300;
const int16_t TAP_DRIFT = 15;
// ======================== 触摸：列表交互 ========================
uint32_t lastTouchTime = 0;
int16_t listTouchStartY = 0;
int16_t listTouchStartX = 0;
bool listTouchActive = false;
bool listTouchMoved = false;
const uint16_t TOUCH_DEBOUNCE_MS = 300;
const uint16_t TAP_THRESHOLD = 10;
const int ITEM_HEIGHT = 35;
const int LIST_TOP = 38;
const int MAX_VISIBLE = 3;
// ======================== 扫描参数 ========================
uint32_t scanStartTime = 0;
#define SCAN_DURATION_MS 5000
// ======================== UI 配色 ========================
#define UI_BG_DARK 0x18C3
#define UI_BG_BLACK 0x0000
#define UI_CYAN 0x07FF
#define UI_ORANGE 0xFDA0
#define UI_RED 0xF800
#define UI_WHITE 0xFFFF
#define UI_LIGHT_GRAY 0xD6BA
#define UI_DIM_GRAY 0x4228
// ======================== UI 运行时 ========================
uint32_t offlineStartTime = 0;
M5Canvas canvas(&M5.Lcd);
bool uiDirty = true;
// ======================== 前置声明 ========================
void sendJoystick(int8_t x, int8_t y);
void sendServoInc();
void sendServoDec();
void sendBeepCmd();
void sendLedCmd(bool enable);
void imuToJoystick(int8_t& x, int8_t& y);
void videoNotifyCallback(NimBLERemoteCharacteristic*, uint8_t*, size_t, bool);
void saveDeviceToNVS(const std::string& addr);
void loadDeviceFromNVS();
void clearDeviceNVS();
void drawOSDText(const char* text, int x, int y, uint16_t color, uint8_t size = 1, uint8_t datum = middle_center);
void drawHUDPopup(const char* title, const char* sub, uint16_t color);
bool connectToAddressStr(const std::string& targetAddrStr);
// ======================== BLE 回调 ========================
class ClientCallbacks : public NimBLEClientCallbacks {
public:
  void onDisconnect(NimBLEClient* pClient, int reason) override {
    connected = false;
    needReconnect = true;
    pVideoChar = nullptr;
    pCtrlChar = nullptr;
    expectLen = 0;
    recvCnt = 0;
    frameReady = false;
  }
};
ClientCallbacks clientCB;
// ======================== NVS ========================
void saveDeviceToNVS(const std::string& addr) {
  prefs.begin("espauto", false);
  prefs.putString("dev_addr", addr.c_str());
  prefs.end();
}
void loadDeviceFromNVS() {
  prefs.begin("espauto", true);
  String addrStr = prefs.getString("dev_addr", "");
  prefs.end();
  if (addrStr.length() == 17) {
    savedAddrStr = addrStr;
    hasSavedDevice = true;
  } else {
    hasSavedDevice = false;
  }
}
void clearDeviceNVS() {
  prefs.begin("espauto", false);
  prefs.clear();
  prefs.end();
  hasSavedDevice = false;
}
// ======================== 控制指令 ========================
void sendJoystick(int8_t x, int8_t y) {
  if (!connected || !pCtrlChar) return;
  uint8_t buf[5] = { 0xAA, 0xFF, 0x15, (uint8_t)x, (uint8_t)y };
  pCtrlChar->writeValue(buf, 5, false);
}
void sendServoInc() {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, 0x13 };
  pCtrlChar->writeValue(cmd, 3, false);
}
void sendServoDec() {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, 0x14 };
  pCtrlChar->writeValue(cmd, 3, false);
}
void sendBeepCmd() {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, 0x12 };
  pCtrlChar->writeValue(cmd, 3, true);
}
void sendLedCmd(bool enable) {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, (uint8_t)(enable ? 0x10 : 0x11) };
  pCtrlChar->writeValue(cmd, 3, true);
}
void imuToJoystick(int8_t& x, int8_t& y) {
  if (!M5.Imu.update()) {
    x = 0;
    y = 0;
    return;
  }
  auto data = M5.Imu.getImuData();
  int16_t rawY = (int16_t)(-data.accel.x * 100);
  int16_t rawX = (int16_t)(-data.accel.y * 100);
  y = (rawY > 100) ? 100 : ((rawY < -100) ? -100 : rawY);
  x = (rawX > 100) ? 100 : ((rawX < -100) ? -100 : rawX);
}
// ======================== 视频回调 ========================
void videoNotifyCallback(NimBLERemoteCharacteristic* pRemoteCharacteristic, uint8_t* pData, size_t length, bool isNotify) {
  portENTER_CRITICAL_ISR(&mux);
  if (length == 4 && pData[0] == 0xAB && pData[1] == 0xCD) {
    expectLen = (pData[2] << 8) | pData[3];
    recvCnt = 0;
    portEXIT_CRITICAL_ISR(&mux);
    return;
  }
  if (expectLen == 0 || recvCnt >= BUF_SIZE || frameReady) {
    portEXIT_CRITICAL_ISR(&mux);
    return;
  }
  size_t copyLen = length;
  if (recvCnt + copyLen > BUF_SIZE) copyLen = BUF_SIZE - recvCnt;
  memcpy(writeBuf + recvCnt, pData, copyLen);
  recvCnt += copyLen;
  if (recvCnt >= expectLen) {
    readyFrameLen = expectLen;
    frameReady = true;
    expectLen = 0;
    uint8_t* temp = writeBuf;
    writeBuf = readBuf;
    readBuf = temp;
  }
  portEXIT_CRITICAL_ISR(&mux);
}
// ======================== OSD 绘制 ========================
void drawOSDText(const char* text, int x, int y, uint16_t color, uint8_t size, uint8_t datum) {
  canvas.setTextSize(size);
  canvas.setTextDatum(datum);
  canvas.setTextColor(color);
  canvas.drawString(text, x, y);
}
void drawHUDPopup(const char* title, const char* sub, uint16_t color) {
  int w = canvas.width();
  int h = canvas.height();
  canvas.fillSprite(UI_BG_BLACK);
  int pw = 240, ph = 90;
  int px = (w - pw) / 2, py = (h - ph) / 2;
  canvas.fillRoundRect(px, py, pw, ph, 4, UI_BG_DARK);
  canvas.drawRoundRect(px, py, pw, ph, 4, color);
  canvas.drawFastHLine(px, py + 30, pw, color);
  drawOSDText(title, w / 2, py + 15, color, 2);
  drawOSDText(sub, w / 2, py + 60, UI_WHITE, 1);
  canvas.pushSprite(0, 0);
}
void bootAnimation() {
  int w = canvas.width();
  int h = canvas.height();
  int barW = w * 0.6, barH = 4;
  int barX = (w - barW) / 2, barY = h / 2 + 30;
  for (int i = 0; i <= 100; i += 4) {
    canvas.fillSprite(UI_BG_BLACK);
    drawOSDText("ARDUINO NESSO N1", w / 2, h / 2 - 25, UI_CYAN, 2);
    drawOSDText("INITIALIZING SYSTEM...", w / 2, h / 2 + 5, UI_WHITE, 1);
    canvas.drawRect(barX, barY, barW, barH, 0x5AEB);
    canvas.fillRect(barX + 1, barY + 1, (barW - 2) * i / 100, barH - 2, UI_CYAN);
    canvas.pushSprite(0, 0);
    delay(15);
  }
  delay(200);
}
// ======================== 扫描 UI ========================
void showScanningUI() {
  uint32_t elapsed = millis() - scanStartTime;
  int progress = min((int)(elapsed * 100 / SCAN_DURATION_MS), 100);
  static int dots = 0;
  char sub[32];
  snprintf(sub, sizeof(sub), "SEARCHING DEVICE%s", dots == 0 ? "." : (dots == 1 ? ".." : "..."));
  dots = (dots + 1) % 3;
  int w = canvas.width(), h = canvas.height();
  canvas.fillSprite(UI_BG_BLACK);
  int pw = 240, ph = 100;
  int px = (w - pw) / 2, py = (h - ph) / 2;
  canvas.fillRoundRect(px, py, pw, ph, 4, UI_BG_DARK);
  canvas.drawRoundRect(px, py, pw, ph, 4, UI_CYAN);
  canvas.drawFastHLine(px, py + 30, pw, UI_CYAN);
  drawOSDText("SCANNING", w / 2, py + 15, UI_CYAN, 2);
  drawOSDText(sub, w / 2, py + 50, UI_WHITE, 1);
  int barX = px + 15, barY = py + 70, barW = pw - 30, barH = 6;
  canvas.drawRect(barX, barY, barW, barH, UI_CYAN);
  canvas.fillRect(barX + 1, barY + 1, (barW - 2) * progress / 100, barH - 2, UI_CYAN);
  char pctBuf[8];
  snprintf(pctBuf, sizeof(pctBuf), "%d%%", progress);
  drawOSDText(pctBuf, w / 2, py + 86, UI_LIGHT_GRAY, 1);
  canvas.pushSprite(0, 0);
}
// ======================== 设备列表 UI ========================
void renderDeviceList() {
  int w = canvas.width(), h = canvas.height();
  canvas.fillSprite(UI_BG_BLACK);
  if (deviceCount == 0) {
    drawHUDPopup("NO DEVICE", "TAP SCREEN TO RESCAN", UI_CYAN);
    return;
  }
  int pw = 240;
  int px = (w - pw) / 2;
  canvas.fillRoundRect(px, 4, pw, 28, 4, UI_BG_DARK);
  canvas.drawRoundRect(px, 4, pw, 28, 4, UI_CYAN);
  drawOSDText("SELECT DEVICE", w / 2, 18, UI_CYAN, 1);
  int maxVisible = min((int)MAX_VISIBLE, deviceCount);
  for (int i = 0; i < maxVisible; i++) {
    int idx = scrollOffset + i;
    if (idx >= deviceCount) break;
    int y = LIST_TOP + i * ITEM_HEIGHT;
    canvas.fillRoundRect(px, y, pw, ITEM_HEIGHT - 2, 4, UI_BG_DARK);
    canvas.drawRoundRect(px, y, pw, ITEM_HEIGHT - 2, 4, UI_CYAN);

    char idxBuf[4];
    snprintf(idxBuf, sizeof(idxBuf), "%d", idx + 1);
    drawOSDText(idxBuf, px + 16, y + (ITEM_HEIGHT - 2) / 2, UI_CYAN, 1);
    canvas.drawLine(px + 30, y + 5, px + 30, y + ITEM_HEIGHT - 7, UI_DIM_GRAY);

    String name = foundDevices[idx].name.c_str();
    if (name.length() > 13) name = name.substring(0, 13);
    char nameBuf[16];
    strncpy(nameBuf, name.c_str(), sizeof(nameBuf) - 1);
    nameBuf[sizeof(nameBuf) - 1] = '\0';
    drawOSDText(nameBuf, px + 40, y + (ITEM_HEIGHT - 2) / 2, UI_WHITE, 2, middle_left);
    drawOSDText(">", px + pw - 12, y + (ITEM_HEIGHT - 2) / 2, UI_CYAN, 1);
  }
  if (scrollOffset > 0)
    drawOSDText("^", w / 2, LIST_TOP - 2, UI_CYAN, 1, bottom_center);
  if (scrollOffset + maxVisible < deviceCount)
    drawOSDText("v", w / 2, LIST_TOP + maxVisible * ITEM_HEIGHT + 1, UI_CYAN, 1, top_center);
  canvas.pushSprite(0, 0);
}
// ======================== 连接/状态 UI ========================
void showConnectingNamedUI(const char* name) {
  static int dots = 0;
  char sub[32];
  snprintf(sub, sizeof(sub), "%s%s", name, dots == 0 ? "." : (dots == 1 ? ".." : "..."));
  dots = (dots + 1) % 3;
  drawHUDPopup("CONNECTING", sub, UI_CYAN);
}
void showConnectedUI() {
  drawHUDPopup("LINK SECURE", "STREAM ONLINE", UI_CYAN);
}
void showReconnectProgressUI() {
  char buf[32];
  snprintf(buf, sizeof(buf), "RECONNECTING %d/%d", reconnectCount + 1, MAX_RECONNECT_TIMES);
  drawHUDPopup("LINK LOST", buf, UI_ORANGE);
}
void showOfflineUI() {
  uint32_t elapsed = offlineStartTime != 0 ? millis() - offlineStartTime : 0;
  int32_t remain = (10000 - elapsed + 999) / 1000;
  if (remain < 0) remain = 0;
  char buf[32];
  snprintf(buf, sizeof(buf), "AUTO POWER OFF IN %dS", (int)remain);
  drawHUDPopup("DEVICE OFFLINE", buf, UI_RED);
}
// ======================== 视频模式触摸处理 ========================
bool handleVideoTouch() {
  uint8_t touchCount = M5.Touch.getCount();
  if (touchCount == 0) {
    if (isTouching) {
      uint32_t duration = millis() - touchStartTime;
      isTouching = false;
      totalDeltaY = 0;
      filterIndex = 0;
      memset(deltaYBuffer, 0, sizeof(deltaYBuffer));
      if (duration < TAP_DURATION_MS && !touchHasMoved) { return true; }
    }
    touchHasMoved = false;
    return false;
  }
  auto detail = M5.Touch.getDetail(0);
  int16_t currentX = detail.x;
  int16_t currentY = detail.y;

  if (!isTouching) {
    isTouching = true;
    touchStartTime = millis();
    touchStartX = currentX;
    touchStartY = currentY;
    lastTouchY = currentY;
    totalDeltaY = 0;
    touchHasMoved = false;
    return false;
  }

  int dx = currentX - touchStartX;
  int dy = currentY - touchStartY;
  if (abs(dx) > TAP_DRIFT || abs(dy) > TAP_DRIFT) {
    touchHasMoved = true;
  }

  int16_t rawDeltaY = currentY - lastTouchY;
  lastTouchY = currentY;

  deltaYBuffer[filterIndex] = rawDeltaY;
  filterIndex = (filterIndex + 1) % FILTER_SIZE;

  int32_t sum = 0;
  for (uint8_t i = 0; i < FILTER_SIZE; i++)
    sum += deltaYBuffer[i];
  totalDeltaY += (sum / FILTER_SIZE);

  if (millis() - lastSendTime < COOL_MS) return false;

  if (totalDeltaY <= -SLIDE_TRIGGER) {
    sendServoInc();
    totalDeltaY += SLIDE_TRIGGER;
    lastSendTime = millis();
  } else if (totalDeltaY >= SLIDE_TRIGGER) {
    sendServoDec();
    totalDeltaY -= SLIDE_TRIGGER;
    lastSendTime = millis();
  }

  return false;
}
// ======================== 列表触摸处理 ========================
void handleListTouch() {
  uint8_t touchCount = M5.Touch.getCount();
  if (touchCount == 0) {
    if (listTouchActive && !listTouchMoved) {
      uint32_t now = millis();
      if (now - lastTouchTime > TOUCH_DEBOUNCE_MS) {
        if (deviceCount == 0) {
          sysState = STATE_SCANNING;
          scanStartTime = millis();
          uiDirty = true;
        } else {
          int listRelY = listTouchStartY - LIST_TOP;
          if (listRelY >= 0) {
            int itemIdx = listRelY / ITEM_HEIGHT + scrollOffset;
            if (itemIdx >= 0 && itemIdx < deviceCount) {
              selectedDevice = itemIdx;
              sysState = STATE_CONNECTING;
              scanStartTime = millis();
              uiDirty = true;
            }
          }
        }
        lastTouchTime = now;
      }
    }
    listTouchActive = false;
    listTouchMoved = false;
    return;
  }
  auto detail = M5.Touch.getDetail(0);
  int16_t cx = detail.x;
  int16_t cy = detail.y;

  if (!listTouchActive) {
    listTouchActive = true;
    listTouchStartX = cx;
    listTouchStartY = cy;
    listTouchMoved = false;
  } else {
    int dx = cx - listTouchStartX;
    int dy = cy - listTouchStartY;
    if (abs(dx) > TAP_THRESHOLD || abs(dy) > TAP_THRESHOLD) {
      listTouchMoved = true;
    }
    if (listTouchMoved && deviceCount > MAX_VISIBLE) {
      int maxScroll = deviceCount - MAX_VISIBLE;
      if (dy < -8) {
        scrollOffset = min(scrollOffset + 1, maxScroll);
        listTouchStartY = cy;
        uiDirty = true;
      } else if (dy > 8) {
        scrollOffset = max(scrollOffset - 1, 0);
        listTouchStartY = cy;
        uiDirty = true;
      }
    }
  }
}
// ======================== BLE 扫描 ========================
bool scanForVehicles() {
  NimBLEScan* pScan = NimBLEDevice::getScan();
  pScan->setActiveScan(true);
  pScan->setInterval(45);
  pScan->setWindow(15);
  pScan->clearResults();
  pScan->start(SCAN_DURATION_MS, false);
  uint32_t start = millis();
  while (millis() - start < SCAN_DURATION_MS) {
    showScanningUI();
    if (M5.Touch.getCount() > 0) {
      uint32_t now = millis();
      if (now - lastTouchTime > TOUCH_DEBOUNCE_MS) {
        lastTouchTime = now;
        break;
      }
    }
    if (M5.BtnA.wasPressed() || M5.BtnB.wasPressed()) break;
    delay(30);
  }
  pScan->stop();

  auto results = pScan->getResults();
  deviceCount = 0;
  for (size_t j = 0; j < results.getCount() && deviceCount < MAX_DEVICES; j++) {
    auto dev = results.getDevice(j);
    if (dev->haveManufacturerData()) {
      auto mfgData = dev->getManufacturerData();
      if (mfgData.length() >= 3) {
        uint16_t companyId = (uint8_t)mfgData[0] | ((uint8_t)mfgData[1] << 8);
        uint8_t productId = (uint8_t)mfgData[2];
        if (companyId == MANUFACTURER_COMPANY_ID && productId == MANUFACTURER_PRODUCT_ID) {
          std::string addr = dev->getAddress().toString();
          bool dup = false;
          for (int k = 0; k < deviceCount; k++) {
            if (foundDevices[k].addrStr == addr) {
              dup = true;
              break;
            }
          }
          if (!dup) {
            foundDevices[deviceCount].name = dev->getName();
            foundDevices[deviceCount].addrStr = addr;
            if (foundDevices[deviceCount].name.empty()) {
              std::string suffix = addr.length() >= 5 ? addr.substr(addr.length() - 5) : addr;
              foundDevices[deviceCount].name = std::string("DEVICE_") + suffix;
            }
            deviceCount++;
          }
        }
      }
    }
  }
  pScan->clearResults();
  scrollOffset = 0;
  return deviceCount > 0;
}
// ======================== BLE 按地址连接 ========================
bool connectToAddressStr(const std::string& targetAddrStr) {
  if (pClient != nullptr) {
    if (pClient->isConnected()) {
      if (pVideoChar != nullptr) pVideoChar->unsubscribe();
      pClient->disconnect();
      delay(100);
    }
    NimBLEDevice::deleteClient(pClient);
    pClient = nullptr;
    pVideoChar = nullptr;
    pCtrlChar = nullptr;
  }
  connected = false;
  NimBLEScan* pScan = NimBLEDevice::getScan();
  pScan->setActiveScan(true);
  pScan->setInterval(45);
  pScan->setWindow(15);
  pScan->clearResults();
  pScan->start(5000, false);

  const NimBLEAdvertisedDevice* target = nullptr;
  uint32_t start = millis();
  while (millis() - start < 5000) {
    auto results = pScan->getResults();
    for (size_t j = 0; j < results.getCount(); j++) {
      auto dev = results.getDevice(j);
      if (dev->getAddress().toString() == targetAddrStr) {
        target = dev;
        break;
      }
    }
    if (target) break;
    delay(30);
  }
  pScan->stop();
  pScan->clearResults();
  if (!target) return false;

  pClient = NimBLEDevice::createClient();
  pClient->setClientCallbacks(&clientCB, false);
  pClient->setConnectionParams(6, 12, 0, 200);
  pClient->setConnectTimeout(2000);
  if (!pClient->connect(target)) {
    NimBLEDevice::deleteClient(pClient);
    pClient = nullptr;
    return false;
  }
  delay(100);

  auto srv = pClient->getService(SERVICE_UUID);
  if (!srv) return false;
  pVideoChar = srv->getCharacteristic(CHARACTERISTIC_VIDEO_UUID);
  pCtrlChar = srv->getCharacteristic(CHARACTERISTIC_CTRL_UUID);
  if (!pVideoChar || !pCtrlChar) return false;
  if (!pVideoChar->subscribe(true, videoNotifyCallback)) return false;

  connected = true;
  needReconnect = false;
  reconnectCount = 0;
  deviceOffline = false;
  offlineStartTime = 0;
  return true;
}
// ======================== 视频帧处理 ========================
void processAndDrawFrame() {
  if (!frameReady) return;
  uint16_t len = 0;
  portENTER_CRITICAL(&mux);
  len = readyFrameLen;
  frameReady = false;
  portEXIT_CRITICAL(&mux);
  if (len > 0 && len <= BUF_SIZE) {
    if (readBuf[len - 2] == 0xFF && readBuf[len - 1] == 0xD9) {
      canvas.drawJpg(readBuf, len, 0, 0);
      applyOSDOverlay();
      canvas.pushSprite(0, 0);
    }
  }
}
// ======================== OSD 叠加层 ========================
void applyOSDOverlay() {
  int w = canvas.width(), h = canvas.height();
  int sigPercent = 0;
  uint16_t sigColor = UI_RED;
  int activeBars = 0;
  if (connected && pClient != nullptr) {
    sigColor = UI_LIGHT_GRAY;
    int rssi = pClient->getRssi();
    sigPercent = constrain(map(rssi, -90, -50, 0, 100), 0, 100);
    if (sigPercent > 75) activeBars = 4;
    else if (sigPercent > 50) activeBars = 3;
    else if (sigPercent > 20) activeBars = 2;
    else activeBars = 1;
  }

  int sigX = 16, sigY = 24;
  int barHeights[4] = { 3, 5, 7, 10 };
  for (int i = 0; i < 4; i++) {
    uint16_t col = (i < activeBars) ? sigColor : 0x4228;
    canvas.fillRect(sigX + (i * 4), sigY - barHeights[i], 3, barHeights[i], col);
  }
  char sigBuf[12];
  snprintf(sigBuf, sizeof(sigBuf), connected ? "%d%%" : "LOST", sigPercent);
  drawOSDText(sigBuf, sigX + 20, 15, sigColor, 1, top_left);

  int level = constrain(M5.Power.getBatteryLevel(), 0, 100);
  uint16_t batCol = (level > 20) ? UI_LIGHT_GRAY : UI_RED;
  char batBuf[16];
  snprintf(batBuf, sizeof(batBuf), "%d%%", level);
  int iconX = w - 38, iconY = 14;
  drawOSDText(batBuf, iconX - 4, 15, UI_LIGHT_GRAY, 1, top_right);
  canvas.drawRect(iconX, iconY, 22, 10, UI_LIGHT_GRAY);
  canvas.fillRect(iconX + 22, iconY + 3, 2, 4, UI_LIGHT_GRAY);
  int fillW = map(level, 0, 100, 0, 18);
  canvas.fillRect(iconX + 2, iconY + 2, fillW, 6, batCol);

  char imuBuf[64];
  snprintf(imuBuf, sizeof(imuBuf), "PITCH: %03d | ROLL: %03d | LED: %s",
           lastY, lastX, ledState ? "ON" : "OFF");
  drawOSDText(imuBuf, w / 2, h - 14, UI_LIGHT_GRAY, 1, bottom_center);
}
// ======================== setup ========================
void setup() {
  auto cfg = M5.config();
  M5.begin(cfg);
  M5.Lcd.setRotation(1);
  canvas.setColorDepth(16);
  canvas.createSprite(M5.Lcd.width(), M5.Lcd.height());
  M5.Power.setBatteryCharge(true);
  M5.Power.setChargeCurrent(100);
  M5.Power.setChargeVoltage(4200);
  bootAnimation();

  NimBLEDevice::init(BLE_DEVICE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEDevice::setMTU(512);
  NimBLEDevice::setDefaultPhy(BLE_GAP_LE_PHY_2M, BLE_GAP_LE_PHY_2M);

  loadDeviceFromNVS();

  M5.update();
  if (M5.BtnB.isPressed()) {
    clearDeviceNVS();
    drawHUDPopup("PAIRING CLEARED", "ENTERING SCAN MODE", UI_ORANGE);
    delay(1000);
    sysState = STATE_SCANNING;
    scanStartTime = millis();
    uiDirty = true;
    return;
  }

  if (hasSavedDevice) {
    sysState = STATE_AUTO_CONNECT;
  } else {
    sysState = STATE_SCANNING;
  }
  scanStartTime = millis();
  uiDirty = true;
}
// ======================== loop ========================
void loop() {
  M5.update();
  switch (sysState) {
    case STATE_BOOT:
      break;

    case STATE_AUTO_CONNECT:
      {
        showConnectingNamedUI("SAVED DEVICE");
        if (connectToAddressStr(savedAddrStr.c_str())) {
          showConnectedUI();
          delay(600);
          sysState = STATE_VIDEO_MODE;
          uiDirty = true;
        } else {
          sysState = STATE_DEVICE_LIST;
          scrollOffset = 0;
          uiDirty = true;
        }
        break;
      }

    case STATE_SCANNING:
      {
        scanForVehicles();
        sysState = STATE_DEVICE_LIST;
        scrollOffset = 0;
        uiDirty = true;
        break;
      }

    case STATE_DEVICE_LIST:
      {
        if (uiDirty) {
          renderDeviceList();
          uiDirty = false;
        }
        handleListTouch();
        if (M5.BtnB.wasPressed()) {
          sysState = STATE_SCANNING;
          scanStartTime = millis();
          uiDirty = true;
        }
        break;
      }

    case STATE_CONNECTING:
      {
        char nameBuf[18];
        String n = foundDevices[selectedDevice].name.c_str();
        if (n.length() > 14) n = n.substring(0, 14);
        strncpy(nameBuf, n.c_str(), sizeof(nameBuf) - 1);
        nameBuf[sizeof(nameBuf) - 1] = '\0';
        showConnectingNamedUI(nameBuf);

        if (connectToAddressStr(foundDevices[selectedDevice].addrStr)) {
          saveDeviceToNVS(foundDevices[selectedDevice].addrStr);
          savedAddrStr = foundDevices[selectedDevice].addrStr.c_str();
          hasSavedDevice = true;
          showConnectedUI();
          delay(600);
          sysState = STATE_VIDEO_MODE;
          lastX = 0;
          lastY = 0;
          uiDirty = true;
        } else {
          sysState = STATE_DEVICE_LIST;
          uiDirty = true;
        }
        break;
      }

    case STATE_VIDEO_MODE:
      {
        bool tapped = handleVideoTouch();
        if (tapped) {
          ledState = !ledState;
          sendLedCmd(ledState);
        }

        if (M5.BtnB.wasPressed()) {
          if (pVideoChar != nullptr) pVideoChar->unsubscribe();
          if (pClient != nullptr && pClient->isConnected()) pClient->disconnect();
          delay(100);
          sysState = STATE_SCANNING;
          scanStartTime = millis();
          uiDirty = true;
          break;
        }
        if (M5.BtnA.wasPressed()) {
          sendBeepCmd();
        }

        if (millis() - lastImuSendTime >= IMU_SEND_INTERVAL) {
          lastImuSendTime = millis();
          int8_t x, y;
          imuToJoystick(x, y);
          if (x != lastX || y != lastY) {
            sendJoystick(x, y);
            lastX = x;
            lastY = y;
          }
        }

        if (connected && !needReconnect) {
          processAndDrawFrame();
        }

        if (needReconnect) {
          reconnectCount = 0;
          sysState = STATE_RECONNECTING;
          uiDirty = true;
        }
        break;
      }

    case STATE_RECONNECTING:
      {
        showReconnectProgressUI();
        if (hasSavedDevice && connectToAddressStr(savedAddrStr.c_str())) {
          showConnectedUI();
          delay(600);
          sysState = STATE_VIDEO_MODE;
          lastX = 0;
          lastY = 0;
          uiDirty = true;
          break;
        }
        if (++reconnectCount >= MAX_RECONNECT_TIMES) {
          sysState = STATE_OFFLINE;
          offlineStartTime = 0;
          uiDirty = true;
        }
        delay(800);
        break;
      }

    case STATE_OFFLINE:
      {
        if (offlineStartTime == 0) offlineStartTime = millis();
        showOfflineUI();
        if (millis() - offlineStartTime >= 10000) {
          NimBLEDevice::deinit();
          M5.Lcd.setBrightness(0);
          M5.Lcd.sleep();
          M5.Power.powerOff();
          esp_deep_sleep_start();
        }
        delay(100);
        break;
      }
  }
  delay(2);
}
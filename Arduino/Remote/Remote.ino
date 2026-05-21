/*
 * ESPAUTO
 * Copyright (c) 2026 honoz
 * Licensed under the MIT License.
 */
 
#include <M5Unified.h>
#include <NimBLEDevice.h>

// 设备别名与目标车辆名称
const char* BLE_DEVICE_NAME   = "ARDUINO NESSO N1";
const char* TARGET_DEVICE_NAME = "ESPAUTO Rover";

// BLE 服务及特征值 UUID 配置
#define SERVICE_UUID "0000ffe0-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_VIDEO_UUID "0000ffe1-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_CTRL_UUID "0000ffe2-0000-1000-8000-00805f9b34fb"

// 视频数据双缓冲区配置
#define BUF_SIZE 16384
uint8_t jpegBufA[BUF_SIZE];
uint8_t jpegBufB[BUF_SIZE];
uint8_t* writeBuf = jpegBufA; // 供 BLE 接收中断写入的缓冲区指针
uint8_t* readBuf = jpegBufB;  // 供主线程解码渲染的缓冲区指针

// 视频帧解析状态控制变量
volatile uint16_t expectLen = 0;
volatile uint16_t recvCnt = 0;
volatile uint16_t readyFrameLen = 0;
volatile bool frameReady = false;

// 临界区互斥锁，用于保护跨线程/中断共享的视频数据
portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;

// BLE 客户端及远程特征值对象
NimBLEClient* pClient = nullptr;
NimBLERemoteCharacteristic* pVideoChar = nullptr;
NimBLERemoteCharacteristic* pCtrlChar = nullptr;

// 车辆连接及重连状态机变量
volatile bool connected = false;
volatile bool needReconnect = false;
#define MAX_RECONNECT_TIMES 5
int reconnectCount = 0;
bool deviceOffline = false;

// 车辆补光灯当前状态
bool ledState = false;

// 摇杆控制量(姿态量)缓存与发送定时器
int8_t lastX = 0;
int8_t lastY = 0;
uint32_t lastImuSendTime = 0;
#define IMU_SEND_INTERVAL 30

// 屏幕触摸划动控制云台的滤波与触发配置
#define FILTER_SIZE 3
int16_t deltaYBuffer[FILTER_SIZE] = { 0 };
uint8_t filterIndex = 0;
int32_t totalDeltaY = 0;
const int16_t SLIDE_TRIGGER = 15;
const uint32_t COOL_MS = 60;
uint32_t lastSendTime = 0;
bool isTouching = false;
int16_t lastTouchY = 0;

// OSD UI 扁平化配色方案定义（高透 FP V风格）
#define UI_BG_DARK       0x18C3 
#define UI_BG_BLACK      0x0000 
#define UI_CYAN          0x07FF // 主题科技青
#define UI_ORANGE        0xFDA0 // 警告提示橙
#define UI_RED           0xF800 // 异常故障红
#define UI_WHITE         0xFFFF // 纯白高亮
#define UI_LIGHT_GRAY    0xD6BA // 低饱和度浅灰

// UI 定时刷新与离线倒计时记录
uint32_t offlineStartTime = 0;
uint32_t lastUIRefresh = 0;

// 全局双缓冲画布对象，防止屏幕刷新闪烁
M5Canvas canvas(&M5.Lcd);

// 前置函数声明
bool connectBLE();
void sendJoystick(int8_t x, int8_t y);
void sendServoInc();
void sendServoDec();
void sendBeepCmd();
void sendLedCmd(bool enable);
void imuToJoystick(int8_t& x, int8_t& y);
void videoNotifyCallback(NimBLERemoteCharacteristic* pRemoteCharacteristic, uint8_t* pData, size_t length, bool isNotify);

// BLE 客户端连接断开时的回调类
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

// 向车辆发送摇杆(X, Y 轴)控制命令包
void sendJoystick(int8_t x, int8_t y) {
  if (!connected || !pCtrlChar) return;
  uint8_t buf[5] = { 0xAA, 0xFF, 0x15, (uint8_t)x, (uint8_t)y };
  pCtrlChar->writeValue(buf, 5, false);
}

// 向车辆发送云台舵机角度增加指令
void sendServoInc() {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, 0x13 };
  pCtrlChar->writeValue(cmd, 3, false);
}

// 向车辆发送云台舵机角度减小指令
void sendServoDec() {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, 0x14 };
  pCtrlChar->writeValue(cmd, 3, false);
}

// 向车辆发送鸣笛蜂鸣器控制指令
void sendBeepCmd() {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, 0x12 };
  pCtrlChar->writeValue(cmd, 3, true);
}

// 向车辆发送补光灯开关控制指令
void sendLedCmd(bool enable) {
  if (!connected || !pCtrlChar) return;
  uint8_t cmd[3] = { 0xAA, 0xFF, (uint8_t)(enable ? 0x10 : 0x11) };
  pCtrlChar->writeValue(cmd, 3, true);
}

// 读取遥控器内置 IMU 传感器加速度数据并映射转换为摇杆控制量
void imuToJoystick(int8_t& x, int8_t& y) {
  if (!M5.Imu.update()) { 
    x = 0; y = 0; 
    return;
  }
  auto data = M5.Imu.getImuData();
  // 提取加速度并进行方向修正及倍率放大
  int16_t rawY = (int16_t)(-data.accel.x * 100);
  int16_t rawX = (int16_t)(-data.accel.y * 100);
  // 限幅限制在 [-100, 100] 区间内
  y = (rawY > 100) ? 100 : ((rawY < -100) ? -100 : rawY);
  x = (rawX > 100) ? 100 : ((rawX < -100) ? -100 : rawX);
}

// BLE 视频特征值接收回调函数（高频接收 JPEG 数据流片段）
void videoNotifyCallback(NimBLERemoteCharacteristic* pRemoteCharacteristic, uint8_t* pData, size_t length, bool isNotify) {
  portENTER_CRITICAL_ISR(&mux);
  
  // 帧头匹配校验：若收到 4 字节且符合特定标志，说明新的一帧数据开始传输
  if (length == 4 && pData[0] == 0xAB && pData[1] == 0xCD) {
    expectLen = (pData[2] << 8) | pData[3]; // 解析该帧 JPEG 数据的预期总长度
    recvCnt = 0;
    portEXIT_CRITICAL_ISR(&mux);
    return;
  }
  
  // 数据异常边界保护
  if (expectLen == 0 || recvCnt >= BUF_SIZE || frameReady) {
    portEXIT_CRITICAL_ISR(&mux);
    return;
  }
  
  // 计算实际可拷贝的数据长度，防止溢出缓冲区
  size_t copyLen = length;
  if (recvCnt + copyLen > BUF_SIZE) copyLen = BUF_SIZE - recvCnt;
  
  // 将收到的数据片段拼接到后台 writeBuf 缓冲区中
  memcpy(writeBuf + recvCnt, pData, copyLen);
  recvCnt += copyLen;
  
  // 检查当前帧是否接收完整
  if (recvCnt >= expectLen) {
    readyFrameLen = expectLen;
    frameReady = true;
    expectLen = 0;
    
    // 执行指针原子的动态翻转，快速切换前后台读写缓冲区
    uint8_t* temp = writeBuf;
    writeBuf = readBuf;
    readBuf = temp;
  }
  portEXIT_CRITICAL_ISR(&mux);
}

// OSD 文本通用绘制助手函数
void drawOSDText(const char* text, int x, int y, uint16_t color, uint8_t size = 1, uint8_t datum = middle_center) {
  canvas.setTextSize(size);
  canvas.setTextDatum(datum);
  canvas.setTextColor(color);
  canvas.drawString(text, x, y);
}

// 系统启动初始化进度条动画
void bootAnimation() {
  int w = canvas.width();
  int h = canvas.height();
  int barW = w * 0.6;
  int barH = 4;
  int barX = (w - barW) / 2;
  int barY = h / 2 + 30;

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

// 通用 HUD 弹窗通知绘制函数
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

// 显示正在搜索和建立连接的 UI
void showConnectingUI() {
  static uint32_t lastRefresh = 0;
  if (millis() - lastRefresh < 300) return;
  lastRefresh = millis();
  
  static int dots = 0;
  char buf[32];
  snprintf(buf, sizeof(buf), "SEARCHING VEHICLE%s", dots == 0 ? "." : (dots == 1 ? ".." : "..."));
  dots = (dots + 1) % 3;
  drawHUDPopup("LINK STARTING", buf, UI_CYAN);
}

// 显示失去连接正在尝试重新连接的 UI
void showReconnectUI() {
  drawHUDPopup("LINK LOST", "RECONNECTING TO VEHICLE...", UI_ORANGE);
}

// 显示连接成功、通信安全的 UI
void showConnectedUI() {
  drawHUDPopup("LINK SECURE", "STREAM ONLINE", UI_CYAN);
}

// 显示车辆离线以及倒计时关机的 UI
void showOfflineUI() {
  uint32_t elapsed = offlineStartTime != 0 ? millis() - offlineStartTime : 0;
  int32_t remain = (10000 - elapsed + 999) / 1000;
  if (remain < 0) remain = 0;

  char buf[32];
  snprintf(buf, sizeof(buf), "AUTO POWER OFF IN %dS", (int)remain);
  drawHUDPopup("VEHICLE OFFLINE", buf, UI_RED);
}

// 在当前视频帧画幅之上叠加 OSD 遥测和仪表信息
void applyOSDOverlay() {
  int w = canvas.width();
  int h = canvas.height();

  // 1. 绘制屏幕边缘的科技感对焦边框
  int bLen = 15; 
  int bOff = 10; 
  canvas.drawFastHLine(bOff, bOff, bLen, UI_CYAN);
  canvas.drawFastVLine(bOff, bOff, bLen, UI_CYAN);
  canvas.drawFastHLine(w - bOff - bLen, bOff, bLen, UI_CYAN);
  canvas.drawFastVLine(w - bOff, bOff, bLen, UI_CYAN);
  canvas.drawFastHLine(bOff, h - bOff, bLen, UI_CYAN);
  canvas.drawFastVLine(bOff, h - bOff - bLen, bLen, UI_CYAN);
  canvas.drawFastHLine(w - bOff - bLen, h - bOff, bLen, UI_CYAN);
  canvas.drawFastVLine(w - bOff, h - bOff - bLen, bLen, UI_CYAN);

  // 2. 左上角：计算无线电信号强度并绘制 4 格信号条
  int sigPercent = 0;
  uint16_t sigColor = UI_RED;
  int activeBars = 0;

  if (connected && pClient != nullptr) {
    sigColor = UI_LIGHT_GRAY;
    int rssi = pClient->getRssi();
    sigPercent = constrain(map(rssi, -90, -50, 0, 100), 0, 100);
    if (sigPercent > 75)      activeBars = 4;
    else if (sigPercent > 50) activeBars = 3;
    else if (sigPercent > 20) activeBars = 2;
    else                      activeBars = 1;
  }

  int sigX = bOff + 6;
  int sigY = bOff + 14;
  int barHeights[4] = {3, 5, 7, 10}; 

  for (int i = 0; i < 4; i++) {
    uint16_t col = (i < activeBars) ? sigColor : 0x4228; 
    canvas.fillRect(sigX + (i * 4), sigY - barHeights[i], 3, barHeights[i], col);
  }

  char sigBuf[12];
  if (connected) {
    snprintf(sigBuf, sizeof(sigBuf), "%d%%", sigPercent);
  } else {
    snprintf(sigBuf, sizeof(sigBuf), "LOST");
  }
  drawOSDText(sigBuf, sigX + 20, bOff + 5, sigColor, 1, top_left);

  // 3. 右上角：获取当前遥控器电量并绘制数显电池图标
  int level = constrain(M5.Power.getBatteryLevel(), 0, 100);
  uint16_t batCol = (level > 20) ? UI_LIGHT_GRAY : UI_RED;
  char batBuf[16];
  snprintf(batBuf, sizeof(batBuf), "%d%%", level);
  
  int batShiftX = 4; 
  int iconX = w - bOff - 24 - batShiftX;
  int iconY = bOff + 4;

  drawOSDText(batBuf, iconX - 4, bOff + 5, UI_LIGHT_GRAY, 1, top_right);
  canvas.drawRect(iconX, iconY, 22, 10, UI_LIGHT_GRAY);
  canvas.fillRect(iconX + 22, iconY + 3, 2, 4, UI_LIGHT_GRAY);
  int fillW = map(level, 0, 100, 0, 18);
  canvas.fillRect(iconX + 2, iconY + 2, fillW, 6, batCol);

  // 4. 正下方：组装并渲染车辆的姿态数据与补光灯状态文本
  char imuBuf[64];
  snprintf(imuBuf, sizeof(imuBuf), "PITCH: %03d | ROLL: %03d | LED: %s", lastY, lastX, ledState ? "ON" : "OFF");
  drawOSDText(imuBuf, w / 2, h - bOff - 4, UI_LIGHT_GRAY, 1, bottom_center);
}

// 主线程视频解码与画面刷新入口
void processAndDrawFrame() {
  if (!frameReady) return;

  uint16_t len = 0;
  
  // 极其短暂地进入临界区锁，仅复制当前帧长度并清除就绪标志
  portENTER_CRITICAL(&mux);
  len = readyFrameLen;
  frameReady = false;
  portEXIT_CRITICAL(&mux);

  if (len > 0 && len <= BUF_SIZE) {
    // 丢包边界防御：检查接收到的 JPEG 是否包含合法的结尾标识 (0xFF, 0xD9)
    // 只有完整包才会送去解码渲染，从根本上杜绝半截损坏帧造成的视频花屏
    if (readBuf[len - 2] == 0xFF && readBuf[len - 1] == 0xD9) {
      canvas.drawJpg(readBuf, len, 0, 0); // 在画布上解码并绘制 JPEG 图像
      applyOSDOverlay();                  // 将仪表层叠在图像上方
      canvas.pushSprite(0, 0);            // 一步推送到物理屏幕显示
    }
  }
}

// 执行 BLE 扫描并尝试连接目标车辆
bool connectBLE() {
  // 如果已有活跃客户端，断开连接并清理其占用的内存资源
  if (pClient != nullptr) {
    if (pClient->isConnected()) {
      if (pVideoChar != nullptr) pVideoChar->unsubscribe();
      pClient->disconnect();
      delay(100); 
    }
    NimBLEDevice::deleteClient(pClient);
    pClient = nullptr; pVideoChar = nullptr; pCtrlChar = nullptr;
  }

  connected = false;
  NimBLEScan* pScan = NimBLEDevice::getScan();
  pScan->setActiveScan(true);
  pScan->setInterval(45);
  pScan->setWindow(15);
  pScan->clearResults();
  pScan->start(10000, false); // 开启 10 秒异步扫描

  uint32_t scanStart = millis();
  const NimBLEAdvertisedDevice* targetDevice = nullptr;

  // 循环等待并过滤扫描列表以匹配目标车辆
  while (millis() - scanStart < 10000) {
    if (needReconnect) showReconnectUI(); else showConnectingUI();
    auto results = pScan->getResults();
    for (size_t j = 0; j < results.getCount(); j++) {
      auto dev = results.getDevice(j);
      if (dev->getName() == TARGET_DEVICE_NAME) { 
        targetDevice = dev; 
        break; 
      }
    }
    if (targetDevice) break;
    delay(30);
  }
  pScan->stop(); pScan->clearResults();

  // 若未搜寻到车辆，将状态置为离线并直接退出
  if (!targetDevice) { 
    deviceOffline = true; 
    needReconnect = false; 
    return false;
  }

  // 创建并配置 BLE 客户端连接参数
  pClient = NimBLEDevice::createClient();
  pClient->setClientCallbacks(&clientCB, false);
  pClient->setConnectionParams(6, 12, 0, 200); // 调整连接间隔以保障视频吞吐量
  pClient->setConnectTimeout(2000);

  if (!pClient->connect(targetDevice)) {
    NimBLEDevice::deleteClient(pClient);
    pClient = nullptr; 
    return false;
  }
  delay(100);
  
  // 获取指定的远程服务与特征值
  auto srv = pClient->getService(SERVICE_UUID);
  if (!srv) return false;

  pVideoChar = srv->getCharacteristic(CHARACTERISTIC_VIDEO_UUID);
  pCtrlChar = srv->getCharacteristic(CHARACTERISTIC_CTRL_UUID);
  if (!pVideoChar || !pCtrlChar) return false;

  // 订阅视频流特征值的 Notify 通知
  if (!pVideoChar->subscribe(true, videoNotifyCallback)) return false;
  
  // 成功连接，恢复各状态机指标
  connected = true; 
  needReconnect = false;
  reconnectCount = 0;
  deviceOffline = false; 
  offlineStartTime = 0;
  return true;
}

// 触摸屏事件检测及划动趋势均值滤波处理
void handleTouch() {
  M5.Touch.update(millis());
  uint8_t touchCount = M5.Touch.getCount();
  
  // 没有手指触摸时清空相关划动变量与缓冲区
  if (touchCount == 0) {
    isTouching = false; totalDeltaY = 0; filterIndex = 0;
    memset(deltaYBuffer, 0, sizeof(deltaYBuffer));
    return;
  }
  
  auto detail = M5.Touch.getDetail(0);
  int16_t currentY = detail.y;
  
  // 手指首次按下时初始化起点坐标
  if (!isTouching) { 
    isTouching = true; 
    lastTouchY = currentY;
    totalDeltaY = 0; 
    return; 
  }

  // 记录纵向移动偏量并存入滑动滤波器
  int16_t rawDeltaY = currentY - lastTouchY;
  lastTouchY = currentY;
  deltaYBuffer[filterIndex] = rawDeltaY;
  filterIndex = (filterIndex + 1) % FILTER_SIZE;
  
  // 滤除手指微颤，计算滤波滑动总量
  int32_t sum = 0;
  for (uint8_t i = 0; i < FILTER_SIZE; i++) sum += deltaYBuffer[i];
  totalDeltaY += (sum / FILTER_SIZE);
  
  // 判断发送冷却时间以防止过于高频的操作
  if (millis() - lastSendTime < COOL_MS) return;
  
  // 根据累加的划动趋势触发对应的舵机偏转命令并扣除步长
  if (totalDeltaY <= -SLIDE_TRIGGER) {
    sendServoInc(); 
    totalDeltaY += SLIDE_TRIGGER;
    lastSendTime = millis();
  } else if (totalDeltaY >= SLIDE_TRIGGER) {
    sendServoDec(); 
    totalDeltaY -= SLIDE_TRIGGER; 
    lastSendTime = millis();
  }
}

// 系统主初始化引导函数
void setup() {
  auto cfg = M5.config();
  M5.begin(cfg);
  
  M5.Lcd.setRotation(1); // 调整屏幕为横屏方向
  
  canvas.setColorDepth(16);
  canvas.createSprite(M5.Lcd.width(), M5.Lcd.height());

  // 配置电源芯片的充电限制属性
  M5.Power.setBatteryCharge(true);
  M5.Power.setChargeCurrent(100);
  M5.Power.setChargeVoltage(4200);

  bootAnimation();
  
  // 蓝牙协议栈初始化及射频调优配置
  NimBLEDevice::init(BLE_DEVICE_NAME);  
  NimBLEDevice::setPower(ESP_PWR_LVL_P9); // 发射功率调至最大档位以提升拉距性能
  NimBLEDevice::setMTU(512);              // 增大 MTU 提升单包数据载荷
  NimBLEDevice::setDefaultPhy(BLE_GAP_LE_PHY_2M, BLE_GAP_LE_PHY_2M); // 启用 2M 高速物理层以支撑视频帧率

  connectBLE();

  if (deviceOffline) {
    showOfflineUI();
    offlineStartTime = millis();
  } else {
    showConnectedUI();
    delay(600);
  }
}

// 固件主业务循环体
void loop() {
  M5.update();
  handleTouch();
  
  // 物理按键 A 控制补光灯翻转
  if (M5.BtnA.wasPressed()) {
    ledState = !ledState;
    sendLedCmd(ledState);
  }
  // 物理按键 B 控制小车鸣笛
  if (M5.BtnB.wasPressed()) {
    sendBeepCmd();
  }

  // 定时读取遥控器姿态并向小车高频同步行驶指令
  if (millis() - lastImuSendTime >= IMU_SEND_INTERVAL) {
    lastImuSendTime = millis();
    int8_t x, y;
    imuToJoystick(x, y);
    // 只有当姿态值发生变化时才对外发送，有效控制网络负载
    if (x != lastX || y != lastY) {
      sendJoystick(x, y);
      lastX = x;
      lastY = y;
    }
  }

  // 车辆处于离线断连状态下的核心处理逻辑
  if (deviceOffline) {
    if (offlineStartTime == 0) offlineStartTime = millis();
    showOfflineUI();
    
    // 断连超过 10 秒后触发强制进入超低功耗深度睡眠关机模式
    if (millis() - offlineStartTime >= 10000) {
      NimBLEDevice::deinit();         
      M5.Lcd.setBrightness(0);       
      M5.Lcd.sleep();                
      M5.Power.powerOff();
      esp_deep_sleep_start();
    }
    delay(100);
    return;
  }

  // 正常连线且不需要异常重连时，执行画面刷新
  if (connected && !needReconnect) {
    processAndDrawFrame();
  }

  // 触发重连请求后的状态机自愈循环
  if (needReconnect) {
    showReconnectUI();
    if (connectBLE()) {
      showConnectedUI();
      delay(600);
      lastX = 0; lastY = 0; // 重置遥控姿态锁零点
    } else {
      // 达到重连次数上限后，标记为设备已彻底离线
      if (++reconnectCount >= MAX_RECONNECT_TIMES) {
        deviceOffline = true;
      }
    }
    delay(500);
  }

  delay(2); 
}
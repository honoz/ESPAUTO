#include "esp_camera.h"
#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>
#include "driver/i2s.h"
#include "driver/ledc.h"
#include "driver/temperature_sensor.h"

// -----------------------------------------------------------------------------
// 1. 硬件引脚配置
// -----------------------------------------------------------------------------

// OV2640 摄像头引脚分配
#define PWDN_GPIO_NUM -1
#define RESET_GPIO_NUM -1
#define XCLK_GPIO_NUM 14
#define SIOD_GPIO_NUM 39
#define SIOC_GPIO_NUM 38

#define Y9_GPIO_NUM 21
#define Y8_GPIO_NUM 13
#define Y7_GPIO_NUM 12
#define Y6_GPIO_NUM 10
#define Y5_GPIO_NUM 8
#define Y4_GPIO_NUM 17
#define Y3_GPIO_NUM 18
#define Y2_GPIO_NUM 9
#define VSYNC_GPIO_NUM 48
#define HREF_GPIO_NUM 47
#define PCLK_GPIO_NUM 11

// DRV8837 电机驱动引脚
#define LEFT_IN1 2
#define LEFT_IN2 1
#define RIGHT_IN1 40
#define RIGHT_IN2 41

// WS2812 状态指示灯引脚及数量
#define LED_PIN 46
#define LED_COUNT 2

// MAX98357/I2S 音频放大器引脚
#define I2S_LCLK_PIN 6
#define I2S_BCLK_PIN 5
#define I2S_DOUT_PIN 45

// 摄像机云台舵机引脚
#define SERVO_PIN 3

// -----------------------------------------------------------------------------
// 2. 硬件控制参数与配置
// -----------------------------------------------------------------------------

// 电机 LEDC (PWM) 参数设置定义
#define MOTOR_LEDC_TIMER     LEDC_TIMER_0
#define MOTOR_FREQ           20000            // 20kHz 频率，避免人耳可听的电机啸叫声
#define MOTOR_RESOLUTION     LEDC_TIMER_8_BIT // 8位分辨率 (占空比范围 0-255)

// 分配独立的外设 PWM 通道控制四个电机输入端
#define CH_LEFT_IN1          LEDC_CHANNEL_2
#define CH_LEFT_IN2          LEDC_CHANNEL_3
#define CH_RIGHT_IN1         LEDC_CHANNEL_4
#define CH_RIGHT_IN2         LEDC_CHANNEL_5

// LED 全局状态控制变量
Adafruit_NeoPixel pixels(LED_COUNT, LED_PIN, NEO_GRB + NEO_KHZ800);
bool ledState = false;
uint8_t currentLedBrightness = 0;

// 舵机脉宽控制参数定义 (50Hz 工作频率，14位分辨率下)
#define SERVO_TIMER          LEDC_TIMER_1
#define SERVO_CHANNEL        LEDC_CHANNEL_1
#define SERVO_MIN_DUTY       410   // 0.5ms 脉宽对应的寄存器值
#define SERVO_MAX_DUTY       2048  // 2.5ms 脉宽对应的寄存器值
#define SERVO_STEP           80    // 单次按键触发的脉宽步进量
int servoDuty = (SERVO_MIN_DUTY + SERVO_MAX_DUTY) / 2; // 默认初始化居中位置

// -----------------------------------------------------------------------------
// 3. 通信安全与状态监控变量
// -----------------------------------------------------------------------------
portMUX_TYPE motorMux = portMUX_INITIALIZER_UNLOCKED; 
volatile uint32_t lastMoveCmdTime = 0; // 记录最后一次收到移动指令的时间戳
const uint32_t MOVE_TIMEOUT_MS = 100;  // 100ms 无指令自动刹车，防止 BLE 断连失控

// BLE 服务与特征值标准 UUID
#define SERVICE_UUID                  "0000ffe0-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_VIDEO_UUID     "0000ffe1-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_CTRL_UUID      "0000ffe2-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_DEV_STATUS_UUID "0000ffe3-0000-1000-8000-00805f9b34fb"

// 厂商自定义数据标识（Company ID），用于设备发现阶段的精准匹配
#define MANUFACTURER_COMPANY_ID       0xE5E5
#define MANUFACTURER_PRODUCT_ID       0x01

const char* BLE_DEVICE_NAME = "ESPAUTO Rover";
NimBLEServer* pServer = nullptr;
NimBLECharacteristic* pVideoChar = nullptr;
NimBLECharacteristic* pCtrlChar = nullptr;
NimBLECharacteristic* pDevStatusChar = nullptr;

volatile bool deviceConnected = false; // BLE 设备连接状态标志
volatile bool bleReady = false;        // BLE 数据流就绪标志
volatile bool beepFlag = false;        // 蜂鸣器触发异步标志

QueueHandle_t videoQueue = NULL;       // 跨核心高并发视频帧指针队列句柄

// -----------------------------------------------------------------------------
// 4. 辅助工具函数
// -----------------------------------------------------------------------------

// 获取并计算 ESP32-S3 芯片内部的 CPU 核心温度
float readCPUTemperature() {
    static temperature_sensor_handle_t temp_handle = NULL;
    static bool inited = false;
    if (!inited) {
        temperature_sensor_config_t temp_config = { .range_min = -10, .range_max = 80 };
        if (temperature_sensor_install(&temp_config, &temp_handle) == ESP_OK) {
            inited = true;
        } else { return NAN; }
    }
    float temp = NAN;
    temperature_sensor_enable(temp_handle);
    temperature_sensor_get_celsius(temp_handle, &temp);
    temperature_sensor_disable(temp_handle);
    return temp;
}

// 封装当前设备状态(亮度、温度)，并更新更新到 BLE 状态读取特征值中
void updateDevStatusBLE(void) {
    uint8_t statusBuf[8] = {0};
    statusBuf[0] = currentLedBrightness;
    float cpuTemp = readCPUTemperature();
    statusBuf[1] = (!isnan(cpuTemp)) ? (uint8_t)constrain((int)cpuTemp, 0, 100) : 0xFF;
    
    if(pDevStatusChar){
        pDevStatusChar->setValue(statusBuf, sizeof(statusBuf));
    }
}

// -----------------------------------------------------------------------------
// 5. 底层外设执行机构驱动
// -----------------------------------------------------------------------------

// 驱动双路 H 桥进行电机速度及方向控制 (-100 到 100 百分比)
void setMotor(int leftSpeed, int rightSpeed) {
    uint32_t leftDuty = constrain(abs(leftSpeed) * 255 / 100, 0, 255);
    uint32_t rightDuty = constrain(abs(rightSpeed) * 255 / 100, 0, 255);

    // 左侧电机正反转/制动逻辑控制
    if (leftSpeed > 0) {
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN1, leftDuty);
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN2, 0);
    } else if (leftSpeed < 0) {
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN1, 0);
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN2, leftDuty);
    } else {
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN1, 0);
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN2, 0);
    }

    // 右侧电机正反转/制动逻辑控制
    if (rightSpeed > 0) {
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN1, rightDuty);
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN2, 0);
    } else if (rightSpeed < 0) {
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN1, 0);
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN2, rightDuty);
    } else {
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN1, 0);
        ledc_set_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN2, 0);
    }

    // 硬件即时更新四个 PWM 通道的占空比寄存器
    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN1);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN2);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN1);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN2);
}

// 摇臂解析算法：将混叠的 XY 轴控制量差速映射转换为双路电机转速
void carControlByJoystick(int x, int y) {
    if (abs(x) < 12) x = 0; // 忽略中心物理死区抖动
    if (abs(y) < 12) y = 0;

    int left = y + x;
    int right = y - x;

    left = constrain(left, -100, 100);
    right = constrain(right, -100, 100);

    setMotor(left, right);
}

// 控制大功率照明补光灯的开关
void setLed(bool enable) {
    ledState = enable;
    currentLedBrightness = enable ? 200 : 0;
    pixels.setBrightness(currentLedBrightness);
    if (enable) pixels.fill(0xFFFFFF); // 全填白色光
    else pixels.clear();
    pixels.show();
}

// 带有亮度级别调节的照明灯控制
void setLedWithBrightness(uint8_t brightness) {
    currentLedBrightness = brightness;
    ledState = (brightness > 0);
    pixels.setBrightness(brightness);
    if (ledState) pixels.fill(0xFFFFFF);
    else pixels.clear();
    pixels.show();
}

// 初始化云台舵机的 50Hz PWM 基准时钟及通道配置
void initServo() {
    ledc_timer_config_t timer_conf = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = LEDC_TIMER_14_BIT, // 14位高精度分辨率
        .timer_num = SERVO_TIMER,
        .freq_hz = 50, // 50Hz 舵机控制周期
        .clk_cfg = LEDC_AUTO_CLK
    };
    ledc_timer_config(&timer_conf);

    ledc_channel_config_t chan_conf = {
        .gpio_num = SERVO_PIN,
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .channel = SERVO_CHANNEL,
        .timer_sel = SERVO_TIMER,
        .duty = (uint32_t)servoDuty,
        .hpoint = 0
    };
    ledc_channel_config(&chan_conf);
}

// 执行底层舵机 PWM 脉宽改写
void servoWriteDuty(int duty) {
    ledc_set_duty(LEDC_LOW_SPEED_MODE, SERVO_CHANNEL, duty);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, SERVO_CHANNEL);
}

// 步进调节舵机旋转方向 (dir: -1 递减, 1 递增)
void setServo(int dir) {
    servoDuty += dir * SERVO_STEP;
    servoDuty = constrain(servoDuty, SERVO_MIN_DUTY, SERVO_MAX_DUTY); // 硬件限幅限位保护
    servoWriteDuty(servoDuty);
}

// 初始化 I2S 协议总线音频驱动 (用于音频输出)
void initSpeaker() {
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX),
        .sample_rate = 16000,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 4,
        .dma_buf_len = 256,
        .use_apll = false,
        .tx_desc_auto_clear = true, // 停止输出时 DMA 自动清空防噪音
        .fixed_mclk = 0
    };
    i2s_pin_config_t pin_config = {
        .bck_io_num = I2S_BCLK_PIN,
        .ws_io_num = I2S_LCLK_PIN,
        .data_out_num = I2S_DOUT_PIN,
        .data_in_num = I2S_PIN_NO_CHANGE
    };
    i2s_driver_install(I2S_NUM_0, &i2s_config, 0, NULL);
    i2s_set_pin(I2S_NUM_0, &pin_config);
}

// 通过 I2S 总线阻塞式输出方波信号，产生提示音 (1kHz 持续 200ms)
void playBeepSound() {
    const int sampleRate = 16000;
    const int duration = 300;
    const int samples = sampleRate * duration / 1000;
    int16_t buffer[128];
    int idx = 0;

    const float freq1 = 400.0;
    const float freq2 = 800.0;
    const float attackMs = 5.0;
    const float releaseMs = 20.0;
    const float attackSamples = sampleRate * attackMs / 1000.0;
    const float releaseStartSample = samples - sampleRate * releaseMs / 1000.0;
    const float twoPi = 2.0 * M_PI;

    for (int i = 0; i < samples; i++) {
        float t = (float)i / sampleRate;

        float envelope;
        if (i < attackSamples) {
            envelope = (float)i / attackSamples;
        } else if (i >= releaseStartSample) {
            envelope = (float)(samples - i) / (samples - releaseStartSample);
        } else {
            envelope = 1.0;
        }

        float phase1 = twoPi * freq1 * t;
        float phase2 = twoPi * freq2 * t;
        float square1 = ((int)(i % (int)(sampleRate / freq1)) < (int)(sampleRate / freq1 / 2)) ? 1.0 : -1.0;

        float sample = (sin(phase1) * 0.5 + sin(phase2) * 0.25 + square1 * 0.15) * envelope;
        int16_t s = (int16_t)(sample * 28000);

        buffer[idx++] = s;
        buffer[idx++] = s;

        if (idx >= 128) {
            size_t bytesWritten;
            i2s_write(I2S_NUM_0, buffer, 256, &bytesWritten, portMAX_DELAY);
            idx = 0;
        }
    }
}

// -----------------------------------------------------------------------------
// 6. 通信协议解析与 BLE 回调管理
// -----------------------------------------------------------------------------

// 自定义数据控制协议解析器 (指令头: 0xAA 0xFF)
void parseControlCmd(uint8_t* data, uint8_t len) {
    if (len < 3 || data[0] != 0xAA || data[1] != 0xFF) return;
    uint8_t cmd = data[2];

    switch (cmd) {
        case 0x00: if (len == 4) setLedWithBrightness(data[3]); break; // 线性调光
        case 0x10: setLed(true); break;                               // 大灯开
        case 0x11: setLed(false); break;                              // 大灯关
        case 0x12: beepFlag = true; break;                            // 触发提示音
        case 0x13: setServo(-1); break;                               // 云台左偏/下偏
        case 0x14: setServo(1); break;                                // 云台右偏/上偏
        case 0x15:                                                    // 摇杆控制多轴底盘
            if (len == 5) {
                carControlByJoystick((int8_t)data[3], (int8_t)data[4]);
                portENTER_CRITICAL(&motorMux);
                lastMoveCmdTime = millis(); // 刷新心跳维持计数
                portEXIT_CRITICAL(&motorMux);
            }
            break;
        default: break;
    }
}

// BLE 连接事件状态监控回调类
class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        deviceConnected = true;
        bleReady = true;
    }
    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        deviceConnected = false;
        bleReady = false;
        carControlByJoystick(0, 0); // 异常断连底盘立即紧急制动
        NimBLEDevice::startAdvertising(); // 重新向外发送广播等待回连
    }
};

// BLE 收到控制端下发写入数据时的回调处理
class CtrlCharacteristicCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
        std::string value = pCharacteristic->getValue();
        if (value.length() > 0) {
            parseControlCmd((uint8_t*)value.c_str(), value.length());
        }
    }
};

// 状态特征被客户端读取时的回调监听
class DevStatusCallbacks : public NimBLECharacteristicCallbacks {
    void onRead(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
        updateDevStatusBLE();
    }
};

// -----------------------------------------------------------------------------
// 7. 外设及通信硬件初始化
// -----------------------------------------------------------------------------

// 配置并初始化摄像头传感器驱动
void initCamera() {
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer = LEDC_TIMER_0;
    config.pin_d0 = Y2_GPIO_NUM;
    config.pin_d1 = Y3_GPIO_NUM;
    config.pin_d2 = Y4_GPIO_NUM;
    config.pin_d3 = Y5_GPIO_NUM;
    config.pin_d4 = Y6_GPIO_NUM;
    config.pin_d5 = Y7_GPIO_NUM;
    config.pin_d6 = Y8_GPIO_NUM;
    config.pin_d7 = Y9_GPIO_NUM;
    config.pin_xclk = XCLK_GPIO_NUM;
    config.pin_pclk = PCLK_GPIO_NUM;
    config.pin_vsync = VSYNC_GPIO_NUM;
    config.pin_href = HREF_GPIO_NUM;
    config.pin_sscb_sda = SIOD_GPIO_NUM;
    config.pin_sscb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn = PWDN_GPIO_NUM;
    config.pin_reset = RESET_GPIO_NUM;
    config.xclk_freq_hz = 20000000;
    config.pixel_format = PIXFORMAT_JPEG;
    config.frame_size = FRAMESIZE_HQVGA; // 分辨率：240x176，适合 BLE 物理带宽限制
    config.jpeg_quality = 12;            // JPEG压缩质量(10-63)，兼顾清晰度与发包带宽
    config.fb_count = 3;                 // 采用三帧硬件循环队列，实现无延迟多核图像捕获

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) ESP.restart();    // 摄像头异常则自动复位系统

    sensor_t* s = esp_camera_sensor_get();
    s->set_hmirror(s, 1);
    s->set_brightness(s, 0);
    s->set_whitebal(s, 1);       // 启用自动白平衡 AWB
    s->set_awb_gain(s, 1);       // 启用 AWB 增益
    s->set_wpc(s, 1);            // 启用白像素校正
    s->set_bpc(s, 1);            // 启用黑像素校正
    s->set_lenc(s, 1);           // 启用镜头校正
    s->set_saturation(s, 0);     // 饱和度归中
}

// 初始化电机所占用四路底层独立的 PWM 发生通道
void initMotor() {
    ledc_timer_config_t motor_timer = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = MOTOR_RESOLUTION,
        .timer_num = MOTOR_LEDC_TIMER,
        .freq_hz = MOTOR_FREQ,
        .clk_cfg = LEDC_AUTO_CLK
    };
    ledc_timer_config(&motor_timer);

    ledc_channel_config_t ledc_ch[4] = {
        {.gpio_num = LEFT_IN1, .speed_mode = LEDC_LOW_SPEED_MODE, .channel = CH_LEFT_IN1, .timer_sel = MOTOR_LEDC_TIMER, .duty = 0, .hpoint = 0},
        {.gpio_num = LEFT_IN2, .speed_mode = LEDC_LOW_SPEED_MODE, .channel = CH_LEFT_IN2, .timer_sel = MOTOR_LEDC_TIMER, .duty = 0, .hpoint = 0},
        {.gpio_num = RIGHT_IN1, .speed_mode = LEDC_LOW_SPEED_MODE, .channel = CH_RIGHT_IN1, .timer_sel = MOTOR_LEDC_TIMER, .duty = 0, .hpoint = 0},
        {.gpio_num = RIGHT_IN2, .speed_mode = LEDC_LOW_SPEED_MODE, .channel = CH_RIGHT_IN2, .timer_sel = MOTOR_LEDC_TIMER, .duty = 0, .hpoint = 0}
    };
    
    for(int i=0; i<4; i++) {
        ledc_channel_config(&ledc_ch[i]);
    }
}

// 初始化彩灯灯条
void initLed() {
    pixels.begin();
    setLedWithBrightness(0);
}

// 配置高吞吐率低功耗蓝牙 (NimBLE) 全套参数
void initBLE() {
    NimBLEDevice::init(BLE_DEVICE_NAME);
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);
    NimBLEDevice::setMTU(512);
    NimBLEDevice::setDefaultPhy(BLE_GAP_LE_PHY_2M, BLE_GAP_LE_PHY_2M);
    
    pServer = NimBLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    NimBLEService* pService = pServer->createService(SERVICE_UUID);
    pVideoChar = pService->createCharacteristic(CHARACTERISTIC_VIDEO_UUID, NIMBLE_PROPERTY::NOTIFY);
    pCtrlChar = pService->createCharacteristic(CHARACTERISTIC_CTRL_UUID, NIMBLE_PROPERTY::WRITE);
    pCtrlChar->setCallbacks(new CtrlCharacteristicCallbacks());
    
    pDevStatusChar = pService->createCharacteristic(CHARACTERISTIC_DEV_STATUS_UUID, NIMBLE_PROPERTY::READ);
    pDevStatusChar->setCallbacks(new DevStatusCallbacks());
    updateDevStatusBLE();

    pService->start();
    NimBLEAdvertising* pAdvertising = NimBLEDevice::getAdvertising();
    pAdvertising->setName(BLE_DEVICE_NAME);
    // 使用厂商自定义数据广播，替代 Service UUID 广播实现设备发现
    uint8_t mfgData[] = { (uint8_t)(MANUFACTURER_COMPANY_ID & 0xFF),
                          (uint8_t)(MANUFACTURER_COMPANY_ID >> 8),
                          MANUFACTURER_PRODUCT_ID };
    pAdvertising->setManufacturerData(mfgData, sizeof(mfgData));
    pAdvertising->start();
}

// -----------------------------------------------------------------------------
// 8. FreeRTOS 双核心高并发多任务管理
// -----------------------------------------------------------------------------

// 【运行于 Core 0】：无线流控专职任务。负责数据分包、限速发送与内存回收
void videoSendTask(void* pvParameters) {
    camera_fb_t* fb = NULL;
    const int mtu = 490; // 单次发送的有效分包大小，防底层芯片内存抖动丢包
    uint8_t head[4] = { 0xAB, 0xCD, 0, 0 }; // 帧同步标志头
    while (1) {
        // 阻塞接收队列中的图像帧指针，最大挂起时间 10ms，期间让出 CPU 权限
        if (xQueueReceive(videoQueue, &fb, pdMS_TO_TICKS(10)) != pdTRUE) {
            continue;
        }

        if (fb && deviceConnected && bleReady) {
            if (fb->format == PIXFORMAT_JPEG && fb->len > 0) {
                uint32_t len = fb->len;
                head[2] = (uint8_t)(len >> 8);  // 拼装长度高字节
                head[3] = (uint8_t)len;         // 拼装长度低字节

                pVideoChar->setValue(head, 4);
                pVideoChar->notify();
                vTaskDelay(pdMS_TO_TICKS(1));   // 为接收端处理多段封包首部留出同步时间

                uint32_t offset = 0;
                while (offset < len && deviceConnected) {
                    int l = min(mtu, (int)(len - offset));
                    pVideoChar->setValue(fb->buf + offset, l);
                    pVideoChar->notify();
                    offset += l;

                    // 硬件流控限速：配合 BLE 空口物理层实际速率进行 3ms 步进延时，防止内部缓冲区产生死锁崩溃
                    vTaskDelay(pdMS_TO_TICKS(3));
                }
            }
            esp_camera_fb_return(fb); // 图像分包投递完毕后立即将内存还给摄像头驱动硬件
        } else if (fb) {
            esp_camera_fb_return(fb); // 突发断连时安全释放该指针所指向的物理内存
        }
    }
}

// 【运行于 Core 1】：硬件拍照专职任务。利用摄像头硬件 VSYNC 阻塞机制自动维持帧率
void cameraCaptureTask(void* pvParameters) {
    while (1) {
        if (!deviceConnected) {
            vTaskDelay(pdMS_TO_TICKS(100)); // 处于未连接闲置状态下，降低拍照功耗
            continue;
        }

        camera_fb_t* fb = esp_camera_fb_get(); // 阻塞式调用硬件接口捕获图像帧
        if (!fb) {
            vTaskDelay(1); 
            continue;
        }

        // 尝试推入队列，若无线发送端处理堆积导致队列溢出，则立即丢弃当前硬件帧，防止产生视觉滞后延迟
        if (xQueueSend(videoQueue, &fb, 0) != pdTRUE) {
            esp_camera_fb_return(fb);
        }
    }
}

// -----------------------------------------------------------------------------
// 9. 主逻辑主入口
// -----------------------------------------------------------------------------
void setup() {
    videoQueue = xQueueCreate(4, sizeof(camera_fb_t*)); // 建立四帧深度的高容错缓冲指针队列
    initCamera();
    initMotor();
    initServo();
    initSpeaker();
    initLed();
    initBLE();
    
    // 强制将两个深度依赖时间片和物理天线总线的死循环任务，剥离到独立的两个核心中并行工作
    xTaskCreatePinnedToCore(videoSendTask, "videoSendTask", 4096, NULL, 5, NULL, 0);       // 通信处理放核 0
    xTaskCreatePinnedToCore(cameraCaptureTask, "cameraCaptureTask", 4096, NULL, 5, NULL, 1); // 图像提取放核 1

    lastMoveCmdTime = millis();
}

// 主后台线程演变为轻量级安全看门狗，完全避免任何可能阻断无线电传输的 CPU 抢占行为
void loop() {
    portENTER_CRITICAL(&motorMux);
    uint32_t lastCmd = lastMoveCmdTime;
    portEXIT_CRITICAL(&motorMux);

    // 心跳看门狗：如果超时未收到任何控制命令，强制停车，防止小车跑丢
    if (millis() - lastCmd > MOVE_TIMEOUT_MS) {
        carControlByJoystick(0, 0);
    }

    // 异步触发音频发生机制
    if (beepFlag) {
        beepFlag = false;
        playBeepSound();
    }

    vTaskDelay(pdMS_TO_TICKS(20)); // 让出主后台处理线程，用于维持系统最底层的软件定时器健康
}
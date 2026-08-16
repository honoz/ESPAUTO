/*
ESPAUTO
Copyright (c) 2026 honoz
Licensed under the MIT License.
*/

#include "esp_camera.h"
#include <NimBLEDevice.h>
#include <Adafruit_NeoPixel.h>
#include "driver/i2s.h"
#include "driver/ledc.h"
#include "driver/temperature_sensor.h"

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

#define LEFT_IN1 2
#define LEFT_IN2 1
#define RIGHT_IN1 40
#define RIGHT_IN2 41

#define LED_PIN 46
#define LED_COUNT 2

#define I2S_LCLK_PIN 6
#define I2S_BCLK_PIN 5
#define I2S_DOUT_PIN 45

#define SERVO_PIN 3

#define MOTOR_LEDC_TIMER     LEDC_TIMER_0
#define MOTOR_FREQ           20000            
#define MOTOR_RESOLUTION     LEDC_TIMER_8_BIT 

#define CH_LEFT_IN1          LEDC_CHANNEL_2
#define CH_LEFT_IN2          LEDC_CHANNEL_3
#define CH_RIGHT_IN1         LEDC_CHANNEL_4
#define CH_RIGHT_IN2         LEDC_CHANNEL_5

Adafruit_NeoPixel pixels(LED_COUNT, LED_PIN, NEO_GRB + NEO_KHZ800);
bool ledState = false;
uint8_t currentLedBrightness = 0;

#define SERVO_TIMER          LEDC_TIMER_1
#define SERVO_CHANNEL        LEDC_CHANNEL_1
#define SERVO_MIN_DUTY       410   
#define SERVO_MAX_DUTY       2048  
#define SERVO_STEP           80    
int servoDuty = (SERVO_MIN_DUTY + SERVO_MAX_DUTY) / 2; 

portMUX_TYPE motorMux = portMUX_INITIALIZER_UNLOCKED; 
volatile uint32_t lastMoveCmdTime = 0; 
const uint32_t MOVE_TIMEOUT_MS = 100;  

#define SERVICE_UUID                  "0000ffe0-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_VIDEO_UUID     "0000ffe1-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_CTRL_UUID      "0000ffe2-0000-1000-8000-00805f9b34fb"
#define CHARACTERISTIC_DEV_STATUS_UUID "0000ffe3-0000-1000-8000-00805f9b34fb"

#define MANUFACTURER_COMPANY_ID       0xE5E5
#define MANUFACTURER_PRODUCT_ID       0x01

const char* BLE_DEVICE_NAME = "ESPAUTO Rover";
NimBLEServer* pServer = nullptr;
NimBLECharacteristic* pVideoChar = nullptr;
NimBLECharacteristic* pCtrlChar = nullptr;
NimBLECharacteristic* pDevStatusChar = nullptr;

volatile bool deviceConnected = false; 
volatile bool bleReady = false;        
volatile bool beepFlag = false;        

QueueHandle_t videoQueue = NULL;       

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

void updateDevStatusBLE(void) {
    uint8_t statusBuf[8] = {0};
    statusBuf[0] = currentLedBrightness;
    float cpuTemp = readCPUTemperature();
    statusBuf[1] = (!isnan(cpuTemp)) ? (uint8_t)constrain((int)cpuTemp, 0, 100) : 0xFF;
    
    if(pDevStatusChar){
        pDevStatusChar->setValue(statusBuf, sizeof(statusBuf));
    }
}

void setMotor(int leftSpeed, int rightSpeed) {
    uint32_t leftDuty = constrain(abs(leftSpeed) * 255 / 100, 0, 255);
    uint32_t rightDuty = constrain(abs(rightSpeed) * 255 / 100, 0, 255);

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

    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN1);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_LEFT_IN2);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN1);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, CH_RIGHT_IN2);
}

void carControlByJoystick(int x, int y) {
    if (abs(x) < 12) x = 0; 
    if (abs(y) < 12) y = 0;

    int left = y + x;
    int right = y - x;

    left = constrain(left, -100, 100);
    right = constrain(right, -100, 100);

    setMotor(left, right);
}

void setLed(bool enable) {
    ledState = enable;
    currentLedBrightness = enable ? 200 : 0;
    pixels.setBrightness(currentLedBrightness);
    if (enable) pixels.fill(0xFFFFFF); 
    else pixels.clear();
    pixels.show();
}

void setLedWithBrightness(uint8_t brightness) {
    currentLedBrightness = brightness;
    ledState = (brightness > 0);
    pixels.setBrightness(brightness);
    if (ledState) pixels.fill(0xFFFFFF);
    else pixels.clear();
    pixels.show();
}

void initServo() {
    ledc_timer_config_t timer_conf = {
        .speed_mode = LEDC_LOW_SPEED_MODE,
        .duty_resolution = LEDC_TIMER_14_BIT, 
        .timer_num = SERVO_TIMER,
        .freq_hz = 50, 
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

void servoWriteDuty(int duty) {
    ledc_set_duty(LEDC_LOW_SPEED_MODE, SERVO_CHANNEL, duty);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, SERVO_CHANNEL);
}

void setServo(int dir) {
    servoDuty += dir * SERVO_STEP;
    servoDuty = constrain(servoDuty, SERVO_MIN_DUTY, SERVO_MAX_DUTY); 
    servoWriteDuty(servoDuty);
}

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
        .tx_desc_auto_clear = true, 
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

void parseControlCmd(uint8_t* data, uint8_t len) {
    if (len < 3 || data[0] != 0xAA || data[1] != 0xFF) return;
    uint8_t cmd = data[2];

    switch (cmd) {
        case 0x00: if (len == 4) setLedWithBrightness(data[3]); break; 
        case 0x10: setLed(true); break;                               
        case 0x11: setLed(false); break;                              
        case 0x12: beepFlag = true; break;                            
        case 0x13: setServo(-1); break;                               
        case 0x14: setServo(1); break;                                
        case 0x15:                                                    
            if (len == 5) {
                carControlByJoystick((int8_t)data[3], (int8_t)data[4]);
                portENTER_CRITICAL(&motorMux);
                lastMoveCmdTime = millis(); 
                portEXIT_CRITICAL(&motorMux);
            }
            break;
        default: break;
    }
}

class ServerCallbacks : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override {
        deviceConnected = true;
        bleReady = true;
    }
    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override {
        deviceConnected = false;
        bleReady = false;
        carControlByJoystick(0, 0); 
        NimBLEDevice::startAdvertising(); 
    }
};

class CtrlCharacteristicCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
        std::string value = pCharacteristic->getValue();
        if (value.length() > 0) {
            parseControlCmd((uint8_t*)value.c_str(), value.length());
        }
    }
};

class DevStatusCallbacks : public NimBLECharacteristicCallbacks {
    void onRead(NimBLECharacteristic* pCharacteristic, NimBLEConnInfo& connInfo) override {
        updateDevStatusBLE();
    }
};

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
    config.frame_size = FRAMESIZE_HQVGA; 
    config.jpeg_quality = 12;            
    config.fb_count = 3;                 

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) ESP.restart();    

    sensor_t* s = esp_camera_sensor_get();
    s->set_hmirror(s, 1);
    s->set_brightness(s, 0);
    s->set_whitebal(s, 1);       
    s->set_awb_gain(s, 1);       
    s->set_wpc(s, 1);            
    s->set_bpc(s, 1);           
    s->set_lenc(s, 1);           
    s->set_saturation(s, 0);     
}

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

void initLed() {
    pixels.begin();
    setLedWithBrightness(0);
}

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
    uint8_t mfgData[] = { (uint8_t)(MANUFACTURER_COMPANY_ID & 0xFF),
                          (uint8_t)(MANUFACTURER_COMPANY_ID >> 8),
                          MANUFACTURER_PRODUCT_ID };
    pAdvertising->setManufacturerData(mfgData, sizeof(mfgData));
    pAdvertising->start();
}

void videoSendTask(void* pvParameters) {
    camera_fb_t* fb = NULL;
    const int mtu = 490; 
    uint8_t head[4] = { 0xAB, 0xCD, 0, 0 }; 
    while (1) {
        if (xQueueReceive(videoQueue, &fb, pdMS_TO_TICKS(10)) != pdTRUE) {
            continue;
        }

        if (fb && deviceConnected && bleReady) {
            if (fb->format == PIXFORMAT_JPEG && fb->len > 0) {
                uint32_t len = fb->len;
                head[2] = (uint8_t)(len >> 8);  
                head[3] = (uint8_t)len;         

                pVideoChar->setValue(head, 4);
                pVideoChar->notify();
                vTaskDelay(pdMS_TO_TICKS(1));   

                uint32_t offset = 0;
                while (offset < len && deviceConnected) {
                    int l = min(mtu, (int)(len - offset));
                    pVideoChar->setValue(fb->buf + offset, l);
                    pVideoChar->notify();
                    offset += l;

                    vTaskDelay(pdMS_TO_TICKS(3));
                }
            }
            esp_camera_fb_return(fb); 
        } else if (fb) {
            esp_camera_fb_return(fb); 
        }
    }
}

void cameraCaptureTask(void* pvParameters) {
    while (1) {
        if (!deviceConnected) {
            vTaskDelay(pdMS_TO_TICKS(100)); 
            continue;
        }

        camera_fb_t* fb = esp_camera_fb_get(); 
        if (!fb) {
            vTaskDelay(1); 
            continue;
        }

        if (xQueueSend(videoQueue, &fb, 0) != pdTRUE) {
            esp_camera_fb_return(fb);
        }
    }
}

void setup() {
    videoQueue = xQueueCreate(4, sizeof(camera_fb_t*)); 
    initCamera();
    initMotor();
    initServo();
    initSpeaker();
    initLed();
    initBLE();

    xTaskCreatePinnedToCore(videoSendTask, "videoSendTask", 4096, NULL, 5, NULL, 0);       
    xTaskCreatePinnedToCore(cameraCaptureTask, "cameraCaptureTask", 4096, NULL, 5, NULL, 1); 

    lastMoveCmdTime = millis();
}

void loop() {
    portENTER_CRITICAL(&motorMux);
    uint32_t lastCmd = lastMoveCmdTime;
    portEXIT_CRITICAL(&motorMux);

    if (millis() - lastCmd > MOVE_TIMEOUT_MS) {
        carControlByJoystick(0, 0);
    }

    if (beepFlag) {
        beepFlag = false;
        playBeepSound();
    }

    vTaskDelay(pdMS_TO_TICKS(20)); 
}
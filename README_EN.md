**English Version** | [中文版](README.md)

# ESPAUTO

ESP32-Based BLE Real-Time Video Streaming RC Car Project

## Preview
| Android | Web PC |
| :---: | :---: |
| ![Android](Assets/android_screenshot.png) | ![Web PC](Assets/web_pc.png) |
| **Web Mobile** | **Arduino NESSO N1** |
| ![Web Mobile](Assets/web_mobile.png) | ![Arduino NESSO N1](Assets/nesso_n1.png) |
| **Windows** | **Mac** | 
| ![Windows](Assets/windows_screenshot.png) | ![Mac](Assets/mac_screenshot.png) |

## Introduction

ESPAUTO is an open-source RC car project based on Bluetooth Low Energy (BLE). Through optimizations on the BLE communication link, it achieves real-time video streaming and command control. The project includes five control interfaces: Android, Windows, Mac, Arduino, and Web, serving as a reference for learning BLE image transmission and ESP32 development.

## Key Features

- **Communication Protocol:** BLE data link based on ESP32-S3/C6.
- **Multi-Platform Control:**
  - **Android:** Supports Android 12+, developed in Kotlin with XML-based UI.
  - **Windows:** Supports Windows 10 19041+, developed in C# with WinUI 3-based UI.
  - **Mac:** Supports macOS 26+, developed in Swift with SwiftUI-based UI.
  - **Arduino:** Supports Arduino Nesso N1, developed in C++, providing physical remote controller firmware.
  - **Web:** Supports browser access, developed in JavaScript with HTML + CSS-based UI.
- **Localization:** Built-in bilingual support (Chinese and English).
- **Open Source:** The firmware and client source code are entirely public for reference and learning.

## Project Structure

- `/Android/ESPAUTO`: Android client project directory.
- `/Windows/ESPAUTO`: Windows client project directory.
- `/Mac/ESPAUTO`: Mac client project directory.
- `/Arduino`: ESP32 low-level firmware source code.
  - `/Car`: Car-side firmware (ESP32-S3 + OV5640).
  - `/Remote`: Remote controller firmware (Arduino Nesso N1 / ESP32-C6).
- `/Web`: Web client source code.

## Quick Start

### 1. Hardware Requirements

- **Car Side:** ESP32-S3 + OV5640 camera module.
- **Controller Side:** Arduino Nesso N1 (equipped with ESP32-C6).

### 2. Deployment

**Firmware Flashing (Arduino IDE):**

- **Car (ESP32-S3):** Open the `/Arduino/Car` project in Arduino IDE, configure the board type and port, then compile and upload.
- **Remote (Arduino Nesso N1):** Open the `/Arduino/Remote` project in Arduino IDE, configure the board type and port, then compile and upload.

**Client Setup:**

- **Android:** Open the `/Android/ESPAUTO` project in Android Studio to build the APK.
- **Windows:** Open the `/Windows/ESPAUTO` project in Visual Studio to build the MSI installer.
- **Mac:** Open the `/Mac/ESPAUTO` project in Xcode to build the app.
- **Web:**
  - **Online:** Visit the [Web Online Client](https://honoz.github.io/ESPAUTO/Web/index.html) to connect and use directly.
  - **Local:** Open `/Web/index.html` locally to use.

**Connection:**

- Establish a Bluetooth pairing connection between devices to use.

## License

This project is open-sourced under the [MIT License](LICENSE).

## Feedback

Feel free to submit Issues or PRs for project discussion and collaboration.

# InkBridge

**Turn your Android Stylus device into a professional Graphics Tablet for Linux.**

InkBridge is a low-latency, wired (USB) driver solution that maps raw Android S-Pen/Stylus data to a virtual Linux input device. It supports full pressure sensitivity, tilt, and tool switching, effectively replacing a dedicated Wacom Intuos/Cintiq tablet for digital art workflows in Krita, Blender, GIMP, and more.

![InkBridge UI Screenshot](docs/screenshot.png)



## ✨ Key Features

* **USB Wired Connection:** Uses the **Android Open Accessory (AOA)** protocol for near-zero latency input.
* **Professional Input Support:**
    * **Logarithmic Pressure Curve:** Custom-tuned math for natural, responsive strokes (4096 levels).
    * **True 3D Tilt:** Projects Android sensor data to standard Linux EV_ABS tilt axes (-90° to +90°).
    * **Reliable Eraser:** "Clean Handover" protocol ensures 100% reliable tool switching between Pen and Eraser during hover or active strokes.
* **Virtual Driver:** Creates a system-level `uinput` device ("pen-emu") compatible with X11 and Wayland (libinput).
* **Modern Desktop UI (Qt/QML):**
    * **Multi-Monitor Mapping:** Select exactly which screen the tablet maps to.
    * **Customizable Physics:** Adjust Pressure Sensitivity and Minimum Threshold (Deadzone).
    * **Rotation Correction:** Swap X/Y axes for landscape/portrait mismatches.
    * **Theming:** Built-in Dark and Light modes.

## 🛠️ Architecture

InkBridge is a complete modernization of the Linux-Android drawing pipeline:

1.  **Android App (Sender):** Written in **Kotlin**. Captures raw `MotionEvent` data, converts Tilt/Orientation radians to degrees, and streams packed binary structs via USB.
2.  **Linux Desktop Client (Receiver):** Written in **C++20** with **Qt/QML**. Claims the USB device, decodes the stream, and injects events into the Linux kernel via `uinput`.

## 🚀 Installation & Build

### Prerequisites
* **Qt 5.12+** (Core, Gui, Qml, Quick, Concurrent)
* **libusb-1.0** (Development headers)
* **C++20** compliant compiler (GCC/Clang)

### Building the Linux Client
```bash
# Clone the repository
git clone [https://github.com/yourusername/InkBridge.git](https://github.com/yourusername/InkBridge.git)
cd InkBridge

# Install dependencies (Ubuntu/Debian example)
sudo apt install build-essential qt5-default libusb-1.0-0-dev

# Build with qmake
mkdir build && cd build
qmake ../InkBridge.pro
make -j$(nproc)
```

### Setting up Permissions (`uinput`)
To allow InkBridge to create a virtual tablet device without running as root (sudo), you must configure `udev` rules.

1.  Create the rule file:
    ```bash
    sudo nano /etc/udev/rules.d/99-inkbridge.rules
    ```
2.  Paste the following content:
    ```bash
    # Allow access to uinput for virtual device creation
    KERNEL=="uinput", MODE="0660", GROUP="input"

    # Optional: Allow access to Android USB devices (Samsung/Google/etc)
    SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"
    SUBSYSTEM=="usb", ATTR{idVendor}=="04e8", MODE="0666", GROUP="plugdev"
    ```
3.  Apply changes:
    ```bash
    sudo udevadm control --reload-rules
    sudo udevadm trigger
    sudo usermod -aG input $USER
    ```
    *(Note: You may need to logout and login for group changes to take effect).*

## 🎮 Usage

1.  **Install the Android App** on your tablet/phone.
2.  **Launch InkBridge** on your Linux desktop.
3.  Connect your Android device via USB.
4.  Accept the "Open with InkBridge" prompt on your Android device.
5.  **Success!** The status bar in the desktop app will turn Green/Connected.

### Advanced Controls
* **Pressure Sensitivity:** Adjusts the logarithmic curve. Higher values make it easier to reach 100% pressure.
* **Minimum Pressure:** Sets a deadzone floor to ignore accidental light touches (ghosting).
* **Reset Connection:** If the device gets stuck or the USB handle is busy, click the "Reset Connection" button in the sidebar to force a USB bus reset and driver re-attachment.

## 🔧 Troubleshooting

**"Error claiming interface: LIBUSB_ERROR_BUSY"**
This happens if the Linux kernel (`cdc_acm` or `usbfs`) grabs the Android device before InkBridge does.
* *Fix:* InkBridge includes an auto-detach feature. If this persists, click the **Reset Connection** button in the sidebar.

**Stylus moves but doesn't click/draw**
* *Fix:* Ensure you have set the "Map to Screen" option correctly in the UI. If the coordinates are out of bounds relative to the selected monitor, clicks may be rejected.

**Tilt is inverted**
* *Fix:* Check the "Fix Rotation (Swap X/Y)" checkbox in the Advanced Settings if you are using the tablet in a different orientation than the PC screen.

## ❤️ Acknowledgments & Credits

This project was originally inspired by the "Android Virtual Pen" application.

While the original project is no longer maintained/deprecated, it served as the proof-of-concept for USB AOA digitizer communication. **InkBridge** represents a total rewrite and modernization of that original concept:

* **Refactored Android App:** Complete rewrite from Java to **Kotlin**, optimizing the event loop and sensor data processing.
* **Refactored Desktop Client:** Complete rewrite from legacy C++ to **C++20**, improving memory safety and concurrency.
* **Modern UI:** Replaced the archaic GTK/X11 interface with a fluid, hardware-accelerated **Qt Quick (QML)** interface.
* **New Features:** Added support for **Multi-Monitor Mapping**, **3D Tilt** (Tilt X/Y), and **Eraser Button** support (Linux Kernel Tool Switching).

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

You are free to:

* Use the software for any purpose
* Study how the software works and modify it
* Redistribute copies
* Distribute modified versions

Under the following terms:
* Any derivative work must also be licensed under GPL-3.0
* The full source code must be made available when distributing the software
* License notices and copyright statements must be preserved

This project is distributed **without any warranty**, including without implied warranties of merchantability or fitness for a particular purpose. See the full license text for details.

The complete license text is available in the LICENSE file in this repository or at https://www.gnu.org/licenses/gpl-3.0.en.html 

<p align="center">
  <img src="logo.png" alt="Logo" height="70" valign="middle" />
  <img src="title_animated.svg" alt="Nothing Tweaks" height="80" valign="middle" />
</p>

<br>

> **An Xposed module designed to customize and enhance the Nothing OS experience.**

By leveraging the modern Xposed framework, this module injects deep system-level tweaks allowing you to tailor your Nothing device exactly to your liking.


## ✨ Features

Nothing Tweaks comes packed with a variety of modifications to elevate your Nothing OS experience. _Click any section below to expand and view the available tweaks:_

<details>
<summary><b>📱 Status Bar</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Status Bar Brightness Slider** | Slide horizontally across the status bar to smoothly adjust screen brightness. |
| **Double Tap to Sleep** | Double tap the status bar to turn off the screen. |
| **Status Bar Edge Padding** | Adjust left and right edge spacing of the status bar content to match your display corners. |
| **Notification Icon Limit** | Set the maximum number of notification icons shown in the status bar and AOD. |
| **Nothing Earphone Icon** | Replaces the generic Bluetooth status bar icon with the official Nothing earbud glyph whenever any Bluetooth audio device is connected. |

</details>

<details>
<summary><b>⏰ Status Bar Clock</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Show Seconds** | Displays real-time seconds in the status bar clock. |
| **Show Day of Week** | Prepends the abbreviated weekday (e.g. `Mon 10:10`) to the status bar clock. |
| **Custom Text** | Appends custom text after the status bar time (up to 24 characters). |

</details>

<details>
<summary><b>📶 Network Speed Indicator</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Network Speed Indicator** | Display real-time upload and download speeds directly in the status bar. |
| **Indicator Position** | Choose placement next to the Clock (Left) or next to Battery &amp; System Icons (Right). |
| **Display Mode** | Switch between combined total speed (e.g. `1.2 MB/s`) or separate upload and download rates (e.g. `▲ 20K ▼ 1.2M`). |
| **Auto-hide When Inactive** | Automatically hides the indicator when network is idle (&lt; 1 KB/s) to keep the status bar uncluttered. |
| **Show Activity Arrows** | Display sleek `▲` and `▼` directional arrows instead of text labels (Separate mode only). |

</details>

<details>
<summary><b>⚙️ Device Features</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Palm Touch Sleep** | Turn off the screen by covering the display with your palm on the lockscreen. |
| **Flip to Record** | Flip the phone face-down to automatically start voice recording. |
| **Super Volume Boost** | Enables a higher maximum audio volume ceiling. |
| **Stepless Volume** | Unlocks continuous, granular volume adjustment levels. |

</details>

<details>
<summary><b>🧭 Navigation</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Long Swipe Back to Kill App** | Hold the back swipe gesture and release to immediately kill and force-stop the foreground application. |
| **Hide Keyboard Space** | Removes the blank space beneath the keyboard and hides the navigation bar handle while typing. |
| **Hide Nav Bar (with Circle to Search)** | Hides the navigation bar gesture pill completely while preserving all gesture navigation and Circle to Search functionality. |

</details>

<details>
<summary><b>🔒 Lockscreen</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Double Tap to Sleep** | Double tap any empty area on the lockscreen to immediately turn off the screen. |
| **Double Tap to Wake** | Double tap the screen while screen is off or in AOD to wake the device. |
| **Additional Charging Info** | Displays real-time wattage (W), current (mA), voltage (V), and battery temperature (°C) while charging. Positioned 10dp above the fingerprint/lock icon. |
| **Scramble PIN** | Randomizes the positions of the PIN keypad digits on every unlock attempt. |
| **Skip Power-Off Verification** | Allows powering off or restarting the device from the lock screen without entering your PIN/password. |
| **Hide Clock** | Hides the stock lockscreen clock and widgets (smartspace), ideal for third-party depth effect wallpapers. |
| **Remove Glimpse Ads** | Disables the sponsored Glance / Glimpse magazine wallpaper on the lock screen. |

</details>

<details>
<summary><b>🤖 AI &amp; Miscellaneous</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Both Volume Keys for Torch** | Press both volume buttons together when the screen is off to toggle the flashlight. |
| **Disable Safe Volume Warning** | Permanently disables the high-volume warning dialog and volume limit when increasing headphone volume. |
| **Gemini AI Clipboard Chip** | Redirects the Nothing OS clipboard AI action chip to Google Gemini instead of ChatGPT. |
| **Volume Panel Timeout** | Customize the duration (in milliseconds) before the volume slider automatically dismisses (default: 3000 ms). |
| **Disable Temperature Warnings** | Disables intrusive high-temperature system warning dialogs. |

</details>

<details>
<summary><b>📸 Screenshots &amp; Screen Recording</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Allow Screenshots &amp; Recording Everywhere** | Allows taking screenshots, screen recording, and casting in restricted apps and throughout the system, while blocking screenshot and recording detection. |
| **Screen Recording Quality Clamp** | Force the built-in screen recorder to clamp at 720p or 1080p @ 60fps for smoother or lower-bandwidth recordings. |
| **Screenshot Serial Number** | Optionally watermarks your device serial number (`SN: <serial>`) onto captured screenshots. |

</details>

<details>
<summary><b>👆 In-Display Fingerprint</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Color Scanning Dots** | Re-colors the animated scanning ripple dots around your finger during biometric authentication. |
| **Custom Color Picker** | Choose any custom static color via a built-in color picker (when random mode is off). |
| **Random Color Every Tap** | Automatically rolls a fresh, vibrant color every time you touch the fingerprint scanner. |

</details>

<details>
<summary><b>🛠️ System</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Advanced Power Menu** | Adds direct reboot options for Recovery and Bootloader / Fastboot to the power menu. |
| **Allow 180° Rotation** | Allows the screen to rotate completely upside-down (reverse portrait). |

</details>

<details>
<summary><b>🔋 Battery &amp; Charging</b></summary>
<br>

| Feature | Description |
| :--- | :--- |
| **Charging Limit** | Automatically pauses charging when the battery reaches your chosen percentage to prevent cell degradation and prolong overall battery lifespan. |
| **Stop Charging At (Limit Slider)** | Adjustable threshold slider from 50% to 95% (in 5% steps) with smart hysteresis charging resumption when battery drops by 2%. |
| **Charging Paused Notification** | Displays a status notification whenever charging is paused at the set limit. |
| **Why Cap at 80% Guide** | Built-in expandable guide detailing why limiting high voltage (&gt;80% / 4.2V+) relieves electrochemical stress on the cathode and doubles cycle life. |

</details>


## 🚀 Installation

1. Ensure your device is rooted and **[LSPosed](https://github.com/LSPosed/LSPosed)** is installed.
2. Download the latest Nothing Tweaks APK from the **[Releases](https://github.com/RevealedSoulEven/NothingTweaks/releases/)** page and install it.
3. Open the **LSPosed Manager** app and enable the Nothing Tweaks module.
4. Reboot your device (or restart `SystemUI` and `Nothing Launcher`) to apply the changes.
5. Open the **Nothing Tweaks** app to configure your settings!


## 🤝 Credits & Acknowledgements

This project would not have been possible without the amazing work of the Android modding community. Special thanks to:

* **[rovo89](https://github.com/rovo89)** - For creating the original [Xposed Framework](https://github.com/rovo89/Xposed), which pioneered this level of Android customization.
* **[LSPosed Team](https://github.com/LSPosed/LSPosed)** - For maintaining and advancing the modern ART hooking framework that powers this module today.
* **[Rares6567](https://github.com/Rares6567)** - For _"hide space under keyboard"_ feature. And his project [NothingXpert](https://github.com/Rares6567/NothingXpert).
* **[LSPosed/DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure)** - For the logic and research behind bypassing screenshot / screen recording detection.

<br>

---
<sub>*Disclaimer: This module modifies system-level components. Use it at your own risk.*</sub>

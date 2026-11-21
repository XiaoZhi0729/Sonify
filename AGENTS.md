# Workspace Rules

## Wireless ADB Discovery & APK Installation

When the user asks to install or deploy the app (e.g. "安装", "装到手机/平板") and `adb devices` shows no active connection:

1. Because `run_command` does not keep the adb daemon alive across separate calls, and `adb mdns services` needs a few seconds after `adb start-server` to collect mDNS broadcasts, discover paired wireless debugging devices in one command:
   ```powershell
   adb start-server; Start-Sleep -Seconds 3; adb mdns services
   ```
2. Build the APK first (`.\gradlew.bat assembleDebug`) if not already up to date.
3. In a **single** `run_command` call, start the server, connect to the discovered `_adb-tls-connect._tcp` endpoints (`adb connect <ip:port>`), and install directly to each connected `<ip:port>` (`adb -s <ip:port> install -r <apk_path>`).
4. Do not report "no device connected" without first running mDNS discovery (`adb start-server; Start-Sleep -Seconds 3; adb mdns services`).

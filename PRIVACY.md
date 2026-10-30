# DiAuto-MG4 privacy

Updated: 26 October 2026.

DiAuto-MG4 hosts Android Auto on the MG4 head unit. Projection content comes from the
connected phone; maps, music, calls, and their associated data are handled by the
phone's Android Auto applications and are subject to those applications' policies.
On the platform-signed `car` build, supported MG4 battery data can be read locally
through the vehicle interface and forwarded to the phone as Android Auto energy
sensors. The `phone` build has no system UID and is intended for desk testing; its
vehicle telemetry behavior is not verified on an MG4.

Settings and pairing information are stored locally. Diagnostic logs and exported
settings can contain device identifiers, network details or other contextual data;
review them before sharing. DiAuto-MG4 does not include an advertising or analytics
service. Diagnostic reports are not uploaded automatically.

Bluetooth, Wi-Fi and USB permissions support the connection. Location permissions may
be required for wireless discovery and supported location features. Microphone access
supports voice commands. Overlay, notification and background permissions support the
projection UI and connection service.

One-tap crash-report upload through Telegram is optional and off by default. It is
available only in builds configured with both a Telegram bot token and chat ID; when
enabled and used, the report is sent to Telegram, which handles it under its own
privacy policy. Leave those build-time values unset to keep upload unavailable.

Questions: https://github.com/mastroiannim/DiAuto-MG4/issues

# DiAuto MG4+ 2.2.2-beta7

- New setting *Open Android Auto when connected* in *Settings → Connection*, Yes by default. On No, Android Auto connects in the background, over wireless or USB, and opens with the car's Android Auto button.

# DiAuto MG4+ 2.2.2-beta6

- Steering wheel: play/pause, next and previous stay with the source playing (radio, Bluetooth, USB), even with Android Auto connected alongside it.
- Stock launcher: DiAuto tells it about the Android Auto session every 30 s while connected, so its media card no longer stays on "Unknown".

# DiAuto MG4+ 2.2.2-beta5

- Consumption model for Google Maps: now on by default, refreshed every 30 s. To go back to the car's average consumption, turn it off in *Settings → Car*.
- Uncompressed music (PCM) moves from test mode to *Settings → Sound*, off by default.
- Battery capacity: the defaults are now 49, 61 and 72.5 kWh, the energy estimated between 100 % and 0 % shown by the car. It can be set by hand in *Settings → Car*, with a button to go back to automatic.

# DiAuto MG4+ 2.2.2-beta4

- Steering wheel in climate mode: the right-hand buttons no longer change the Android Auto track. DiAuto leaves them to the car in every mode except media, as the stock launcher does.

# DiAuto MG4+ 2.2.2-beta3

- Test mode: uncompressed music (PCM) instead of AAC, to compare (*Sound*, from the next connection). It uses more Wi-Fi and may stutter.

# DiAuto MG4+ 2.2.2-beta2

- Calls through the car right after connecting: DiAuto's wake-up no longer takes the phone's hands-free slot as it comes back on Bluetooth, which kept calls off the car for about two minutes.
- Car Wi-Fi client: back after 10 s by default instead of 1 min (*Settings → Connection*). At a car start with no phone linked, it is switched on even if something else left it off.

# DiAuto MG4+ 2.2.2-beta1

- Phone already plugged in when the car starts: DiAuto finds it and connects over USB, ahead of wireless. If USB doesn't come up, wireless takes over again.

# DiAuto MG4+ 2.2.1

- The update window shows how much data will be downloaded, and advises connecting the car to a Wi-Fi network as a client, or updating over USB.
- Incompatible firmwares (SWI68 R46, SWI131 R05): the banner also mentions a USB wireless Android Auto dongle as a backup.
- Everything from the 2.2.1 betas below.

# DiAuto MG4+ 2.2.1-beta29

- State of health: until there is a measurement, *Settings → Car* shows it as being measured, with its progress, instead of 100 %. Maps is told 95 % meanwhile.

# DiAuto MG4+ 2.2.1-beta28

- **Logs on by default again**, ready to send if something goes wrong: *Settings → About → Export logs*, always available. They are small, and can be switched off in *Record logs*.
- **Logs no longer contain private details:** no destinations, road names or GPS position, and phone and Wi-Fi names are hidden. Please send a new log rather than a file from an earlier version.

# DiAuto MG4+ 2.2.1-beta27

- The battery version no longer changes according to the capacity measured while driving: it comes from the car, or from your choice in *Settings → Car*. The measurement only gives the state of health of that battery.
- The log now says why the battery type could not be read from the car, when that happens.

# DiAuto MG4+ 2.2.1-beta26

- **Consumption model for Google Maps (experimental, off by default, *Settings → Car*):** Maps gets a description of the car (rolling and air resistance, outside temperature, climate power) instead of its average consumption, so speed, cold and heating count. A window explains it when you switch it on. It replaces the test option that read MG4 Dash's consumption map.
- With the model on, test mode can show its figures over Android Auto, against what the battery really uses.
- Outside temperature now reaches Maps on SWI69, SWI131 and SWI132.
- The car's Wi-Fi client is switched off and the access point restarted once a phone is linked, whichever network the car was on, and comes back when no phone is linked. Meant to fix the phone not finding the car's Wi-Fi after a 5 GHz home network.
- State of health from DiAuto's own measurement, with MG4 Dash's only until a first sample. Comfort 64 kWh can be chosen in the battery versions.

# DiAuto MG4+ 2.2.1-beta25

- **Security:** wireless Android Auto only accepts a phone connected to the car's own access point (a device on the car's Wi-Fi client network can no longer start a session). The AllGo-compatible service only answers the car's own apps and MG4 Dash. Updates are HTTPS only. Unused root / Shizuku and diagnostic upload code removed.
- **Logs off by default:** *Settings → About → Record logs*, to switch on when diagnosing a problem; the Export button shows only then. Bluetooth / Wi-Fi addresses are half-masked in the log, and voice prompts are kept as WAV only in test mode.

# DiAuto MG4+ 2.2.1-beta24

- Night mode *Like the car*: also dark from sunset to sunrise (calculated at the car's last GPS position), as MG4 Dash does, so stopping with the lights off at night stays dark.

# DiAuto MG4+ 2.2.1-beta23

- The right-hand steering wheel buttons are left to the car while they drive the instrument cluster's menus (trip stats, etc.), as the stock launcher does: DiAuto no longer sends them to Android Auto then.

# DiAuto MG4+ 2.2.1-beta22

- No more "Switching USB device to accessory mode…" and "Failed" messages when a phone or a wireless Android Auto dongle is plugged in by USB: the connection was made anyway, they now only go to the log.

# DiAuto MG4+ 2.2.1-beta21

- Pause heard at once with music over Android Auto (Wi-Fi): some phones send the music in bursts, and a press between two bursts read as "nothing playing", so DiAuto let the ~4 s the phone still sends after a pause play out. Music now counts as playing for 3 s after the last sound received.

# DiAuto MG4+ 2.2.1-beta20

- **Battery recognised automatically:** DiAuto reads the battery type from the car's configuration, the same byte the stock charging app uses (Standard 51 kWh recognised at once). For the state of health, and when the type is not enough, it measures the capacity while you drive (energy out of the battery ÷ drop in charge, after about 15 %).
- **Red banner on the home screen** for car firmwares known not to run wireless Android Auto (SWI68 R46, SWI131 R05).
- **Test mode** (*Settings → About*, off by default) shows the features being tested: the model format sent to Maps, consumption from MG4Dash (needs MG4Dash), and voice treble, clarity and headroom. Off, they use their defaults (format C; MG4Dash consumption off; treble off, clarity off, headroom -6 dB).
- Settings reopen on the tab used last, until the app restarts.

# DiAuto MG4+ 2.2.1-beta17

- Night mode (*Settings → Display*) has four choices: **Like the car** (default) follows the car's lights, as the car's own screens do, and is right from start-up even when the lights were already on; **Sun** is dark from sunset to sunrise at the car's position (the former automatic mode); **Day**; **Night**.

# DiAuto MG4+ 2.2.1-beta16

- The exported log also keeps the call service lines of SWI68 / SWI133 cars (they use other names than on SWI69), to diagnose the car's call window showing over Android Auto.

# DiAuto MG4+ 2.2.1-beta15

- The exported log also includes the car's own lines about the Android Auto session and calls (its phone app, Bluetooth settings, rui), to diagnose cases like the car's call window showing over Android Auto. Bluetooth addresses, IP addresses, phone numbers, contact names and the phone's name are removed.

# DiAuto MG4+ 2.2.1-beta14

- The car's own call window no longer covers Android Auto's during a call: DiAuto now tells the car's phone app that the Android Auto screen is showing and which phone runs it, as the stock Android Auto app does. Calls still go through the car's Bluetooth hands-free.
- New *Settings → Connection → Wi-Fi channel*: Auto, 36, 40, 44 or 48 for the car's access point (it was always on 36, the busiest channel). Auto picks the least crowded one from the scan results the car already has, without slowing the connection, and only when the access point starts. The channel in use is shown next to the setting.
- Experimental *Settings → Sound → Voice treble*, for prompts and the assistant with separate audio streams: recreates the treble the phone cuts off (light or strong exciter, or Opus 1.6's neural bandwidth extension). Feedback on which setting sounds best is welcome on GitHub.
- Voice headroom is now -6 dB by default (for those who never changed it).

# DiAuto MG4+ 2.2.1-beta11

- Two settings for prompts and the assistant with separate audio streams (*Settings → Sound*), both off by default and applied from the next prompt: **voice clarity** (removes the low rumble below 150 Hz and lifts consonants around 3 kHz) and **voice headroom** (0, -3 or -6 dB, so the car's own processing does not saturate). Both are plain filters and gains, with no distortion of their own.

# DiAuto MG4+ 2.2.1-beta10

- Pause is heard at once: some phones and players keep sending the music for several seconds after a pause, heard as a ~4 s delay. That audio is now dropped right away (up to 6 s, until you press play).
- Music over Bluetooth: pause no longer resumes by itself after ~2 s. The car's Bluetooth already sends the phone the pause, and DiAuto sent a second play/pause toggle over Android Auto that undid it.

# DiAuto MG4+ 2.2.1-beta9

- Navigation prompts and the assistant sound like with the car's own Android Auto: measured on the car, it plays them at 48 kHz on a normal track, while DiAuto handed them at 16 kHz on a small buffer that the car turned into a fast track and resampled with its basic converter. DiAuto now converts them to 48 kHz itself with a much sharper filter (flat to 7 kHz, no audible images) and plays them on a normal track. Still no boost with separate audio streams.
- The last voice prompts are kept as received from the phone (WAV, in OpenHeadunitLogs/voice) to check their quality.

# DiAuto MG4+ 2.2.1-beta6

- Battery preheating before a charging stop drives the battery heating switch exactly as the car's own apps do (zone 0, 1 / 2, read 0 = on), on every MG4 firmware DiAuto recognises; it stays off on an unrecognised one.

# DiAuto MG4+ 2.2.1-beta5

- With separate audio streams, navigation prompts and the assistant now reach the car exactly as the phone sends them: no boost and no resampling in DiAuto (the 16 kHz voice was converted to 48 kHz since 2.2.0-beta12).

# DiAuto MG4+ 2.2.1-beta4

- The car's Wi-Fi client comes back 1 minute after the Android Auto session ends (was 2 minutes); the delay can be set in *Settings → Connection*: 10 s, 30 s, 1 min, 2 min or 5 min.

# DiAuto MG4+ 2.2.1-beta3

- The car's Wi-Fi client is switched off only once your phone connects to the car over Bluetooth, no longer as soon as DiAuto starts: with no phone in the car, the car keeps joining its own Wi-Fi networks (a home network, for example). It comes back 2 minutes after the Android Auto session ends.

# DiAuto MG4+ 2.2.1-beta2

- Smoother wireless Android Auto when the car's own Wi-Fi is on: the car has a single Wi-Fi radio, so its Wi-Fi client shared it with the Android Auto hotspot, and once the car drove away from that network the picture could drop to a few frames per second with sound gaps. During a wireless session the car's Wi-Fi client is now always switched off (it was only switched off on 2.4 GHz), and back on afterwards if it was on.

# DiAuto MG4+ 2.2.1-beta1

- With Android Auto on the USB cable, the car's Wi-Fi client is switched on so the car joins its known networks, such as the phone's hotspot, and has internet. It goes off again when the USB session ends. Option in *Settings → Connection*, on by default.

# DiAuto MG4+ 2.2.0

First official release of DiAuto MG4+: everything in the 2.1 and 2.2 betas, and since 2.2.0-beta24:

- One setting for the MG4 version in *Settings → Car*: choosing the version also sets the usable battery capacity Google Maps uses; automatic follows the car for both.
- README: tip for Google Assistant / Gemini closing at once (unlock the phone).

# DiAuto MG4+ 2.2.0-beta24

- Clean navigation prompts again. With separate audio streams (the default), prompts and the assistant are never boosted: the phone sends them near full scale, so the boost went through the limiter on almost every syllable. Use the car's navigation volume instead. Mixed with the music, the voice level can still be raised (0 % by default, also for existing installs), with a note that it costs some quality.
- The voice level shows marks every 50 % and its current value, and changing it during a session offers to reconnect (it is read when the phone connects).
- Larger small texts: the hint under the two-finger menu, the setting descriptions, the choice buttons and the home screen lines.

# DiAuto MG4+ 2.2.0-beta21

- A short **tutorial** at first start: the gestures over Android Auto and the main features (Maps trip planning, battery settings, preheating before a charging stop, going back to the car's Android Auto). It can be skipped and shown again from *Settings → About*. In all 22 languages.
- Two fingers from the right edge now give a real full screen: the car's top strip is hidden too, and Android Auto reconnects for a few seconds to use the whole height. The choice is kept for the next trips.
- "Settings" was shown in English in 14 languages.

# DiAuto MG4+ 2.2.0-beta20

**A simpler DiAuto.** New home screen and settings, one way to connect, and many fixes found on the road.

- New **DiAuto for MG4** home screen and settings in five tabs (Connection, Display, Sound, Car, About), with large buttons, in the car's language. The two-finger menu over Android Auto has two large buttons: Disconnect and Settings.
- Wireless Android Auto uses only the car's own hotspot (USB still works). Wi-Fi Direct, Headunit Server, Wireless Helper, Self Mode, Wi-Fi auto-start and the old onboarding are gone (about 6,000 lines less).
- Music no longer sounds sped up or choppy: audio is acknowledged to the phone once it has really been played.
- Coming back to Android Auto no longer shows a black screen for several seconds, and the "Do you see the screen?" banner is gone.
- Gemini / Google Assistant: the microphone stays open. DiAuto's mic packets lacked the header the phone expects, so it often closed the mic after 0.2 s.
- Steering wheel phone key with Android Auto on screen opens Android Auto's phone app.
- Clearer navigation prompts: the 16 kHz voice is converted to 48 kHz with a proper filter, and a raised voice level no longer clips. The voice level goes up to +200 %.
- Google Maps gets the MG4 version's real fast-charging power (Standard 51 kWh, Comfort / Luxury / XPower 64 kWh, Extended Range 77 kWh), detected from the car or set in *Settings → Car*. Battery capacity and state of health are set there too, saved at once, with a reset to automatic.
- New, off by default: **battery preheating before a charging stop** on the Maps route, a set time before arrival (30 min by default).
- Music over Bluetooth (hybrid) keeps the music in the car: the car's settings no longer drop the phone's Bluetooth audio.
- A setting that applies at the next connection offers to reconnect when you return to Android Auto.
- An unwanted disconnection 2 minutes after connecting is fixed.

# DiAuto MG4+ 2.1.0-beta17

- Coming back to the Android Auto screen is instant every time: the phone keeps sending video for the whole session instead of 2 minutes after leaving.
- A navigation prompt no longer keeps the radio or the music lowered until the next one: once no prompt audio has arrived for 3 s, DiAuto gives the guidance focus back (never the assistant's).

# DiAuto MG4+ 2.1.0-beta16

- Steering wheel keys no longer draw Android Auto's focus highlight: keys Android Auto does not know (the MG4 wheel's own codes, which also reach the projection screen) were sent as an unknown key, which Android Auto takes as rotary input. They are no longer sent; the wheel still works through its media key paths.
- Coming back to the Android Auto screen within 2 minutes no longer blanks the picture for a moment: the video was never stopped, so DiAuto no longer asks the phone to restart it.

# DiAuto MG4+ 2.1.0-beta15

- After an update (USB, file manager or the in-app updater) the car sees Android Auto again at once: the update ended the process the car's Android Auto service (rui) was bound to, and rui never bound again until the car restarted. DiAuto now restarts rui right after it is updated.

# DiAuto MG4+ 2.1.0-beta14

- "Separate audio streams" is on by default (also for existing installs): prompts, the assistant and notifications keep their own channel, the car's navigation one, heard over the radio. Off puts them in the multimedia stream with the music.

# DiAuto MG4+ 2.1.0-beta13

- The beta12 setting is replaced by the existing "Separate audio streams", now off by default (also for existing installs): off, prompts, the assistant and notifications play in the multimedia stream with the music; on, they get their own channel, the car's navigation one, heard over the radio. Takes effect at the next connection.

# DiAuto MG4+ 2.1.0-beta12

- New setting "Navigation prompts over the radio" (off by default): off, prompts and the assistant play with the music, on all speakers, as before 2.0.0-beta19, but are not heard while the radio plays; on, they use the car's navigation channel and are heard over the radio, which can sound thinner.

# DiAuto MG4+ 2.1.0-beta11

- Navigation prompts, assistant and system sounds back to 16 kHz: with them announced at 48 kHz (beta9/10) the phone no longer connected.

# DiAuto MG4+ 2.1.0-beta10

- Steering wheel phone key (and the car's other Android Auto shortcuts): the car asks Android Auto to open its phone, music or assistant; DiAuto answered without doing it, so the car's own phone opened. DiAuto now opens the Android Auto screen on that app.
- Settings > Compatibility report: a short summary of the setup (car firmware, Android, decoders, DiAuto settings, Bluetooth and hotspot state, last steering wheel key codes) with a QR code, Copy and Share, to add to a GitHub issue. It holds no addresses, names, passwords, VIN or location.
- SWI68 R46 and SWI131: the freed hands-free retry and the automatic move to Headunit Server mode are removed (neither got wireless Android Auto working there; a newer firmware does). Units that earlier betas had moved to Headunit Server go back to Native AA.

# DiAuto MG4+ 2.1.0-beta9

- Navigation prompts, the assistant and system sounds are asked from the phone at 48 kHz like the music, instead of 16 kHz, which sounded dull on the car's navigation channel.

# DiAuto MG4+ 2.1.0-beta8

- Video at 30 frames per second by default, also for existing installs: half the frames for the phone to encode and send over Wi-Fi, fewer stutters, still smooth. 60 remains available in Video > FPS limit.
- Coming back to the Android Auto screen is instant: for 2 minutes after leaving it, the video keeps decoding off-screen, then it is given back to the phone as before (so the phone does not keep encoding for nothing).

# DiAuto MG4+ 2.1.0-beta7

- Pause is heard at once: the music already in the Wi-Fi jitter buffer (about 1 s) is dropped when a pause key or play/pause on playing music is sent, instead of playing out first.

# DiAuto MG4+ 2.1.0-beta6

- Steering wheel next/previous with the stock launcher: the launcher leaves the wheel's left/right keys to AllGo when Android Auto is the source, and AllGo reads them itself. DiAuto now does the same (on release, only while Android Auto is the car's source). If a launcher also sends them as AllGo's next/previous, the skip happens once.

# DiAuto MG4+ 2.1.0-beta5

- Stock launcher: the media card no longer stays on "Unknown" without a cover after a car start or an Android Auto reconnect. DiAuto tells rui again that the session is up at each track change (at most every 30 s).
- Bluetooth: DiAuto now gives rui the phone's address the way AllGo does (Android Auto Bluetooth pairing request). rui stores it and passes it to the car, whose Settings then keep that phone's hands-free link. 2.1.0-beta2 sent it in a field rui ignores for USB devices, so the link was still being cut.

# DiAuto MG4+ 2.1.0-beta3

- Steering wheel next/previous now reach Android Auto the way they reached AllGo: the car's launcher sends them through rui as press/release codes (next 7/11, previous 8/12, play/pause 20/21), which DiAuto now passes to the phone as key presses. MG4 Dash needs no DiAuto-specific code. When the wheel play/pause also came through rui, DiAuto's own reading of that key is skipped, so it toggles once.

# DiAuto MG4+ 2.1.0-beta2

- Fixes short sound dropouts every few seconds: DiAuto announced the car's own Bluetooth address to the car as the phone's, so the car's Settings kept cutting the phone's hands-free link and the phone kept reconnecting it. DiAuto now announces the phone's address (from Android Auto's Bluetooth pairing request or the wireless link).

# DiAuto MG4+ 2.1.0-beta1

- DiAuto is now installed in place of the car's own Android Auto (package com.allgo.app.androidauto, as an update of AllGo's app; the original stays on the system partition). The car's launcher shows the Android Auto tile as active and the media card with the song, artist and cover; MG4 Dash sees it as the car's Android Auto. The former separate relay is built in. At first start DiAuto takes over the settings of the old com.drivehub.diauto.mg4 and removes it. Settings > Go back to the car's Android Auto removes DiAuto and brings AllGo back.

# DiAuto MG4+ 2.0.0-beta24

- Experimental AllGo relay: DiAuto sends the phone's Bluetooth address (never an empty one, which made AllGo's rui crash at start) and tells the relay again at every track change, so a restarted relay recovers the session.

# DiAuto MG4+ 2.0.0-beta23

- Experimental: when the AllGo relay (a local stand-in for AllGo's Android Auto, not part of this repo) is installed, DiAuto tells it when a session starts and ends, so the stock launcher's Android Auto button and media card see a connected phone. The OEM Android Auto blocker leaves the relay alone. Without the relay nothing changes.

# DiAuto MG4+ 2.0.0-beta22

- Experimental: the stock SAIC launcher and status bar show the song title, artist and cover from Android Auto. They read them from AllGo's media browser service, which AllGo's own Android Auto feeds; DiAuto now feeds it the same way (bind with com.allgo.action.aapservice, then metadata, play state and session status). MG4 Dash is unchanged (it reads DiAuto's own MediaSession).

# DiAuto MG4+ 2.0.0-beta20

- Navigation prompts: each prompt now holds its own navigation-guidance focus while it plays. The MG4 only opens its navigation channel, where the prompt audio goes since beta19, while such a request is held; beta19 skipped it whenever the phone's music request was active, so prompts were silent on every source.

# DiAuto MG4+ 2.0.0-beta19

- Navigation prompts and the assistant are heard while the radio plays: their audio is now played as navigation guidance, which the MG4 sends to its navigation channel and mixes over any source. It used to be played as Android Auto media, which only the Android Auto source makes audible.

# DiAuto MG4+ 2.0.0-beta18

- Prompts are heard when the phone does not ask for audio focus first (it does not always): the prompt then asks by itself, as navigation guidance like the phone's request, never as a voice call, so the radio dips and comes back.

# DiAuto MG4+ 2.0.0-beta17

- The radio comes back after a navigation prompt: DiAuto asked for audio focus twice per prompt, once as the phone asked (navigation guidance, which the MG4 mixes over the radio) and once more as a voice call when the prompt audio started. The second request made the SAIC radio drop its focus and stay silent until the next key press. Prompts and the assistant now only use the phone's own request.

# DiAuto MG4+ 2.0.0-beta16

- Navigation prompts are heard again over the radio: once DiAuto had stopped asking for audio focus for the phone's music (its guard against pausing a Bluetooth phone), it stopped asking for prompts and the assistant too, so the radio covered them for the rest of the session. Prompts, the assistant and notifications now always ask (unless the focus setting is Never); the guard only applies to music.

# DiAuto MG4+ 2.0.0-beta15

- Battery for Google Maps is set by itself: the capacity is read from the car when its firmware publishes it, otherwise DiAuto takes the battery MG4 Dash knows (the one chosen in its settings, or the one nearest to its measurement), otherwise the 64 kWh battery (61.7 kWh usable). The state of health is MG4 Dash's estimate when MG4 Dash is installed and has measured the battery, otherwise 100 %. Both can still be forced by hand in Settings > Charging info, with an Automatic choice to go back.
- Updates: an Updates button on the main screen shows the installed version and offers the newest release, betas only when "Also check beta releases" is on. The in-app update now downloads the APK itself, checks its SHA-256 and installs it with a PackageInstaller session flagged for internal storage, as MG4 Dash does (the previous session lacked the internal-storage flag the MG4 needs, and went through the public Downloads folder). DiAuto restarts by itself and comes back on screen.

# DiAuto MG4+ 2.0.0-beta14

- Experimental, SWI68/SWI131 only: before falling back to Headunit Server, DiAuto tries Native AA once more with the phone hands-free link released and the Android Auto RFCOMM server opened on its own, in case the head unit one-session cap is a shared pool rather than per server. If the channel is still refused it moves to Headunit Server as before. Other firmwares are unchanged.

# DiAuto MG4+ 2.0.0-beta13

- Beta diagnostic logs (Downloads/OpenHeadunitLogs) are kept smaller: the last 40 app starts instead of 80, 20 MB for the whole folder instead of 50 MB, and a log file is cut at 5 MB (a head unit left on for hours used to write a single ever-growing file). Setting the log level to Silent in the settings turns them off. Release builds will have them off by default.
- The gesture hint shown when Android Auto starts (2 fingers from the left edge: menu with Settings; 3 fingers down: back to DiAuto, disconnects) now stays about 10 s instead of 3.5 s (still shown once, on the first connection after install).

# DiAuto MG4+ 2.0.0-beta12

- Head units whose Bluetooth refuses the Native AA channel (SWI68, SWI131: the nFore library caps app RFCOMM servers at one session and drops every incoming connection) are moved to Headunit Server mode automatically, once, with a notification saying what to do on the phone. Firmwares known to work (SWI69, SWI132, SWI133) are never switched; on a firmware nobody has checked, the switch only happens after the Native AA Bluetooth server has failed twice in a row with no connection.

# DiAuto MG4+ 2.0.0-beta11

- Faster reconnection when you come back to the car: the watchdog now pokes the phone every 30 s instead of 60 s, and when the phone answers its poke, the Bluetooth auto-start no longer pokes a second time on top of it (two pokes at once kept the phone's Android Auto channel out for two minutes). No watchdog poke for 3 minutes after the phone has just reconnected over Bluetooth.

# DiAuto MG4+ 2.0.0-beta10

- The phone comes back on its own after you leave the car on and walk away: while the car is on with no Android Auto session and the remembered phone is not linked over Bluetooth, DiAuto pokes it once a minute (it used to try only right after the disconnect). A phone that is linked but has not started Android Auto is left alone, and so is a session you ended yourself.

# DiAuto MG4+ 2.0.0-beta9

- The cluster navigation test screen (from upstream PR #16) no longer has its own icon in the app list; it is still reachable with adb for testing.

# DiAuto MG4+ 2.0.0-beta8

- No more connection screen at car start: DiAuto stays in the background, the phone connects over Bluetooth, and the projection comes forward once the phone has started Android Auto. Whether it starts, and whether the music plays, follows the phone's own Android Auto settings ("Start Android Auto automatically", "Start music automatically"). The old behaviour is the hidden setting show-app-on-car-start.

# DiAuto MG4+ 2.0.0-beta7

- Beta logs keep the last 80 app starts instead of 10 (still capped at 50 MB): the head unit wakes several times an hour, so a failed morning connection was no longer in the logs a few hours later.

# DiAuto MG4+ 2.0.0-beta6

- DiAuto now takes the sound under AllGo's Android Auto name (com.allgo.app.androidauto, source type 70). The stock launcher then knows the source is Android Auto and stops sending the steering-wheel arrows to the radio.

# DiAuto MG4+ 2.0.0-beta5

- Wireless no longer stops connecting after a day of short drives or head-unit restarts: the boot-loop guard (written for another head unit) is off on the MG4, and a pause it left is lifted (as in DaniCastroDev's fork).
- The unused Wi-Fi Direct channel is closed at start when the mode does not use it; it made the car toggle Wi-Fi P2P tens of times a second (from DaniCastroDev's fork).

# DiAuto MG4+ 2.0.0-beta4

- Faster track changes: after next / previous, the old track's audio still buffered in DiAuto (1–2 s on Wi-Fi) is dropped, so the new track is heard as soon as the phone sends it.

# DiAuto MG4+ 2.0.0-beta3

- Music resumes after the steering-wheel arrows: the stock launcher sends them to the radio, which grabs the sound for a moment; DiAuto now waits 1.5 s before telling the phone, so the music keeps playing when a launcher hands the source back.
- A PLAY after a real loss (from the steering wheel or a launcher) now tells the phone it has the sound again and takes the Android Auto source back; before, the phone ignored the PLAY.

# DiAuto MG4+ 2.0.0-beta2

- Music no longer stops 0.1 s after it starts: the focus loss caused by DiAuto's own claim of the Android Auto source was passed on to the phone as if the car had taken the sound.
- The car-source claim reuses one focus request instead of a new one each time, so repeated claims no longer make DiAuto lose focus to itself.
- Less stutter at music start: when the phone sends a burst, the AAC decoder waits for room (up to 1.5 s) instead of dropping frames.
- After you pick another source (radio, Bluetooth), DiAuto no longer takes the Android Auto source back on its follow-up checks; if the phone's Bluetooth music link comes back during a Wi-Fi music session, it is dropped again.
- The in-app issue link points to this repository.

# DiAuto MG4+ 2.0.0-beta1

- **DiAuto MG4+**: this fork now shows its own name (DiAuto MG4+), starts its own version line at 2.0.0 and says on the About screen that it is a community fork. The package stays com.drivehub.diauto.mg4, so it installs over DiAuto-MG4 and keeps its settings (the short-lived "MG4 AA" rename of beta5 is undone).
- Upstream PRs merged: #6 (hotspot kept on 5 GHz while the car Wi-Fi is on 2.4 GHz), #7 (720p margins and top bar), #9 (steering-wheel voice key to the assistant, arrows no longer highlight Android Auto), #10 (cabin mic reaches Android Auto), #12 (no empty diagnostic report on a USB stick), #14 (60 fps by default, no decoder toast) and #16 (Android Auto guidance on the instrument cluster, BYD navigation output removed).

# DiAuto-MG4 1.9.7-beta4

- Music over Android Auto: when the music starts, DiAuto checks 3 s and 8 s later that the car really switched to the Android Auto source, and claims it again if not (the music could start in silence).
- Music over Android Auto: after a guidance or voice prompt, if the car stayed on another source while the phone keeps playing, DiAuto takes the Android Auto source back.
- When the car or a launcher switches away from Android Auto (to the radio, for example), the phone is now told and pauses, instead of showing "playing" in silence.
- Beta diagnostics: logs at Info level are written to a file in Downloads/OpenHeadunitLogs by default, so a lost-audio drive can be read back.

# DiAuto-MG4 1.9.7-beta3

- Battery at arrival in Google Maps: the car battery reading now starts when the phone connects, also when DiAuto was started during the car's locked boot (from damienhoareau's fork).
- The cleanup of a session that ended can no longer drop the battery sensors the next session's phone just asked for.

# DiAuto-MG4 1.9.7-beta2

- Music now goes over Android Auto by default (the Bluetooth "hybrid" mode is off, existing installs included); it can still be turned back on in the audio settings.
- The car's media source switches to Android Auto when the phone starts playing, and the radio is given back when you pick it: the 8 s re-claim loop is gone, and the phone is told when the car takes audio focus, so it pauses (from DaniCastroDev's fork).
- A notification or a navigation prompt no longer switches the car from the radio to Android Auto (from DaniCastroDev's fork).

# DiAuto-MG4 1.9.7-beta1

- After an update, the app no longer forces a recompile of itself: on the MG4 that failed while the app was running and deleted the compiled code made at install time, so the app ran slower from the APK.

# DiAuto-MG4 1.9.7-beta0

- Settings > update now checks this fork's releases (with or without betas), downloads, verifies and installs them.
- A beta now sees its final version as an update (1.9.7-beta0 < 1.9.7), and the "-debug" build suffix no longer affects the comparison.
- After an update the app restarts its service, so Android Auto comes back without a car restart.

# DiAuto-MG4 1.9.6-fix.1 — fork of merthankaraman/DiAuto-MG4 1.9.6

- Reconnection on the next trip: `CommManager` is app-wide, but the service's `onDestroy()` cancelled its scope, so after the first stop every later disconnect cleanup was a no-op and the phone could not connect again in the same process (upstream PR #4).
- A user exit from inside Android Auto is no longer read as an unexpected drop, and connection errors are no longer recorded as user exits (PR #4).
- Bluetooth auto-start no longer overwrites the chosen connection mode; Headunit Server mode brings up the hotspot and scans every subnet (PR #4).
- Wireless sessions no longer loop on "connection lost": the Bluetooth channel is held for the session and the Wi-Fi credentials go out without a 1 s delay (PR #5).
- The captive-portal helper stops retrying once the head unit refuses its iptables redirect (PR #13).

# DiAuto 0.3.11 — 2026-10-01

- Recover when the built-in car hotspot is enabled after DiAuto opens, without restarting the app.
- Exclude wired, modem and BYD bridge interfaces from automatic hotspot selection while the real access point is still starting.
- Refresh cached hotspot credentials after a phone-reported join failure before retrying.
- Wait for an explicitly selected hotspot interface instead of falling back to another network.
- Prevent cancelled or superseded credential resolvers from publishing or invalidating newer results.
- Owner confirmed that the hotspot recovery test build worked before authorizing this release.

# DiAuto 0.3.10 — 2026-09-29

- Completed English, Simplified Chinese, Arabic, Russian and Spanish coverage, including home, connection setup, diagnostics and audio settings.
- Arabic right-to-left layout support and Simplified Chinese display/theme options.
- On Android 13+, Android Settings and the in-app language picker now stay synchronized; older Android keeps the saved language choice.
- Language selection survives settings export/import and resets correctly to System default.
- Automatic translation-coverage and format checks help prevent regressions.
- Clarify the BYD-only support scope on the README and all five website editions.

# DiAuto 0.3.3 — BYD HUD navigation

- Standalone windshield arrows, next-turn distance and street names on verified DiLink5.1 firmware, without ADB, root or a computer helper.
- Add opt-in BYD navigation settings and background vendor output with route-end, disconnect and stale-guidance cleanup. Force-stop cleanup occurs on the next launch.
- Recover the current Wi-Fi Direct interface/BSSID when it appears late; allow a bounded grace period before using a plausible cached fallback. Preserve Static BSSID overrides.
- Add a best-effort Bluetooth auto-start notification fallback and useful recovery diagnostics.
- Thanks to @STUkh for PR #2 and the Wi-Fi recovery/auto-start improvements.

# DiAuto 0.3.1 — first public release

- Native wireless Android Auto on tested BYD DiLink 5.1, plus wired USB.
- New car-friendly home screen, settings pages, controls and dialogs.
- Music-through-car-Bluetooth mode avoids competing media audio focus.
- Wireless groups prefer the car Wi-Fi channel when supported, reducing observed scrolling stalls.
- Bounded SurfaceView frame pacing and preserved full-screen touch alignment.
- No extra phone companion app, root, dongle or firmware changes required.
- 576 unit tests pass; optimized release tested on DiLink 5.1.

Other head units and sustained performance are not guaranteed. The tested display
reports about 58 Hz; the app does not promise constant 60 FPS.

## Upstream history

# Changelog
### v.3.3.0-alpha
- Begin for theming of the App.
- Refactor WiFi-Code from AapService into their own classes for better maintenance, thanks to @MrEAlderson
- USB-Blacklist Filter to prevent the app from asking for non Android phones

### v.3.2.6
- Fix settings UI crash and dpi input on older Android devices
- Fix video artifacts
- Fixing wireless stack where failures stay broken until restart
- Bringing back old style USB List
- Fix broken theme after import: Reapply Theme selection after Settings import
- Make HUD Mode for apply for the whole app

### v.3.2.5
- Fix black screen after backgrounding, and the washed-out picture a dropped frame leaves
- Fix headunit server socket leak
- Car GPS: deliver the head unit's fix when the phone asks, and keep it flowing

### v.3.2.4
- Fix: Video and audio never catch up after a wireless link stall, thanks to @o-jcardenass
- Fix: Native AA wake poke takes down the head unit's own hands-free link, thanks to @o-jcardenass
- Stop the setup wizard from overwriting the reported manufacturer, thanks to @o-jcardenass
- Added: Let the Bluetooth side keep the media buttons,  thanks to @o-jcardenass

### v.3.2.3
- Adding custom log location (App folder or Download folder)
- additional fixing for the fps/freeze problems. Thanks to @o-jcardenass and @andrecuellar for helping
- Fix/hotspot unreadable config
- NativeAA: show when a P2P group that lands on channel 12 or 13

### v.3.2.2
- Fixing location jumping, especially on lower speeds
- Fixing screen flicker again in video decoder
- Fix/video throughput telemetry and keyframe lockout, thanks to @o-jcardenass
- Fix Steering Wheel Buttons not working anymore
- added new native mode without WiFi-Direct creation, thanks to @o-jcardenass

### v.3.2.1
- Fixing new welcome screen, thanks to @andrecuellar
- Fixing HW decoding on kitkat thanks to @o-jcardenass
- Fixing 2 Fatal errors shown in play console
- fix: move mic timestamp inside encrypted payload (byte 4), thanks to @bruno303
- making native mode work better and on more devices, thanks to @o-jcardenass

### v.3.2.0
- Don't grab audio focus on connect in dynamic mode, thanks to @bnayahu
- Acquire transient audio focus while AA audio plays, thanks to @bnayahu
- Add GitHub Actions CI (build + unit tests), thanks to @bnayahu
- Fix picture stuck if used within DUDU's PiP, thanks to @MrEAlderson
- Fix(wifi): use public API for frequency, thanks to @DerTeufel
- Added pixel aspect ratio setting, thanks to @axel92b
- Added more audio features to gain audio focus and work with dsp headunits as setting
- Added display over other apps permission for better compatibility with some headunits, espacially with self mode
- Fix transport get stucked on multiple reconnects, thanks to @notathf
- Add car keys support for FYT headunits, thanks to @MrEAlderson
- Added Option to flip projection horizontal for Headup-Displays
- Recover automatically from post-first-frame video display stalls, thanks to @andrecuellar
- Settings and Onboarding Wizard redesign for better usability, thanks to @andrecuellar
- Fixing mulitiple WiFi-Direct and Native AA connection issues, thanks to @o-jcardenass, @andrecuellar and @notathf
- Various fixes PR, thanks to @MrEAlderson
- Selfmode on AA 17.4 now connects to the headunit dev server or opens the AA settings to start it
- Rename the app to Open Headunit because of confusion with Headunit Reloaded (HUR)

### v.3.1.1
- Reduce pressure on sensor events like night and gps and start/stop these events in onConnected, onDisconnect and onDestroy
- Merged ffmpeg PR #625 by @mmwtl. This added ffmpeg software decoder for h265, which old devives could benefit a lot. Thank you!
- Wi-Fi Direct changes. Prevent duplicate start, graceful resets, cleaned up stale groups
- Remove Automatic Play Integrity Checks, so Playstore won't check the license and link to the playstore in headunits
- Added manual bssid for native Mode. This should help for users where the bssid is hidden for the app, thanks to @rakshan-kumr
- Added immediately network scan with wifi connection, thanks to @MrEAlderson for the PR

### v.3.1.0
- Added libusb as alternative to the native usb stack for better compatibility with some devices
- Fixed Layout in Portrait Mode in nearly square devices
- Added Scale Slider for loading screen media

### v.3.0.1
- Fixed: App Exit on Disconnect
- Enhanced: USB Workflow. This will hopefully eliminate some random usb disconnects
- Fixed keyboard input on Android < 6 Devices
- Enhanced Wi-Fi Direct-Mode
- Added: Bundled FFmpeg HEVC decoder with decoder source setting and FPS/performance overlay
- Enhanced: HEVC GLES/YUV rendering latency, audio buffering, and Wi-Fi Direct fallback reliability
- Enhanced File Selector for some devices
- Fixed some fatal errors, showing in play console

### v.3.0.0
- Added: Custom loading screen (image/GIF/video), thanks to @andrecuellar
- Added: Settings-Reset Button, if you mess up something in the settings, you can now reset them to default
- Removed: Old deprecated ssl library written in C-Code for better maintenance, stability and smaller file sizes
- Added: Direct Logging to file without logcat, thanks to @Anton111111
- try to fix connection lost on carrier lost again
- keep usb disconnection for 8s alive, for maybe restarts of usb dongles
- Implement car headlight signal mode (ILL+) for night theme management, thanks to @minhtuanact
- Added settings export and import functionality with backup options, thanks to @JanRi3D
- Added whitelist to usb connection for not interrupting with iPhones and other usb devices
- Added QR Code for easy connection with wireless helper
- Fixed: BT auto-connect dragging phone into wireless flow during USB session, thanks to @andrecuellar
- Persist Auto-Optimize wizard settings synchronously, thanks to @andrecuellar
- Added ability to swipe with two fingers from the right side to switch fullscreen mode and Normal (all bars) mode, thanks to @Anton111111
- Improved: usb button auto connect, thanks to @bezprobeloff
- Catched an fatal error listed in the play console
- Fixed: Audio Stutter on some devices since 2.1.1
- Fixed: USB device list duplicates and Android Auto projection launch on Android 10+, thanks to @jeancarloscc
- Enhanced: Google Nearby. It was buggy with 2 FPS video
- Fixed: Navigation Button mapping now working

### v.2.3.1
- Fixed a connection lost on for example borders
- Binding socket to Wi-Fi network if available to prevent connection drops on carrier lost
- Added Static Audio Focus Toggle to prevent audio focus loss on some devices
- Fixing samsung routines and modes
- Fixing wrong orientation on start if holding the phone wrong. Now uses the orientation from settings
- Fixing usb errors with AAwireless Dongles
- Added Audio Mixer to mix different audio tracks, thanks to @jeffdapaz for the idea
- Added Autostart on BT for multiple devices
- Fixed Microphone input source was wrong mapped
- Added Vietnamese translation 🇻🇳 thanks to @minhtuanact
- Merged PR #549 - implement back key routing and add keymap for back key, thanks to @JanRi3D

### v.2.3.0
- Added some new buttons for keymap
- Fixed 3 Fatal errors
- Fixed video decoder settings for allwinner devices
- Added new navigation intents
- Included PR #456
- Added new "Autostart on WiFi" Setting #324
- Fixed empty bssid on native AA. Should now work on more devices
- Fixed a new fatal with media sessions
- Readded fullscreen overlays system icons #351
- Remap Enter (66) to Dpad Center (23) for Rotary Knob #459
- Debounce multiple key events if key event is the same in 100ms #465
- Moved Mic settings to own fragment and added 3 new options for the new mic enhancement from version 2.2.2, which defaults to off for better compatibility
- Merged PR #481 - Apply MediaTek 60fps and audio optimizations, thanks to @mrkontrast-coder
- Some rewrite of the AudioTrackWrapper, to enhance stability and minimize stutters
- Merged PR #490 - Add UI scale settings, thanks to @Anton111111
- Merged PR #502 - Navigation Broadcast Updates. Thanks to @Bastel2020

### v.2.2.2
- Fixed: Exit on disconnect now stops the carmode too
- Fixed: Exit intent not closing the app
- Fixed: Orientation not working great on app switch, if you have "auto or sensor" enabled
- Again: Steering Wheel and Keymapping got some changes, maybe this will work on more devices
- Extend mic debugging and add NoiseSuppressor, AutomaticGainControl and AcousticEchoCanceler for better voice quality
- Fixed an issue where the Android USB system prompt wouldn't appear for phones. The prompt is now enabled by default and can be separately disabled for USB thumb drives. It calls "Listen for USB Devices" setting and it decouples the system USB prompt from the Auto-Start behavior. This will bring back the old functionality for all and can be disabled for those who are annoyed of the popup for non Android Auto devices
- Fixed: Rescale and UpdateUI if the useable area differs from the one negiotated. This happens on devices which lie about their navbars.
- Add separate audio streams setting and update related functionality thanks to @Anton111111
- Fixed: Auto-Night mode over 3 hours of in the UK and other countries, thanks to @BinarySimple17
- Enhanced: When audio sink is off, the app no longer tries to get media focus at all

### v.2.2.1
- **Fixed a fatal error in UBS conncetions since 2.2.0. This is important so releasing this version while not fixing all planned issues**
- Google Nearby Connection is now auto connecting if auto connect is enabled
- UI: Added Error Message for Android 10 and below for selfmode
- New Approach for scaling and touch to prevent offset
- Fixing App appears multiple times in App-Drawer
- Fixing Routines and intents not working

### v.2.2.0
- Added: Native AA. 🎉  Warning! This will only work on a limited amount of headunits! Most Android devices do not support connecting 2 Android devices via Bluetooth which is essential for this to work.
- Added: Google Nearby Support as connection method. Needs Wireless Helper 1.6.0 or later
- Added: Pip-Support
- Added: 4K in select
- Try to fix connection problems on WiFi
- Added: Intent and routine for starting the app directly to self mode
- Added: Force Scale Option for older devices on surface view
- Added: New Immersive Fullscreen with avoided notch area. This should fix problems for eg. Pixel Phones
- Enhanced: Video Decoder Error Handling
- Added: 2 new WiFi-Options for a WiFi-Direct. Thanks to @andrecuellar
- Added Japanese language 🇯🇵 thanks to @mattyann87
- Enhanced: Media Session Announcement. Thanks to @irwanrhmn
- New App-Icon without text for better visibility
- Fixed: USB modal appearing for non-Android Auto devices thanks to @andrecuellar
- Added: Create configurable audio queue and audio buffer in settings thanks to @irwanrhmn

### v.2.1.1
- Fixed: Layout crash on Android 4.2
- Added: Enable Hotspot option. Note: This will not work on every device. Especially after Android 13!
- Added: Fake VPN Handler for new Android Auto in offline mode. It is no longer possible to send a network to AA, so we need this hack, if your device is offline
- Enhanced: Audio-Focus is now more aggressive to hopefully fix the audio is not coming from my tablet/headunit errors
- Added: Auto-Boot Functionality. Thanks to @andrecuellar

### v.2.1.0
- Fixed: Exit Intent not working. Thanks to benyjr
- Added: Rotary Support
- Fixed: Crash in Android < 5
- Fixed: Double Button fire and enter/knob click as dpad center mapping
- Enhanced: Android Auto start for selfmode now tries all methods always and catches errors
- Fixed: Auto start, connection lost overlay, toasts and API17 Bluetooth crash. thanks to @andrecuellar
- Added: Exit App on disconnect feature/setting thanks to @Tilak-03 and @andrecuellar
- Enhanced: Wi-Fi Setting redirect is gone. Now only a toast message
- Fixed: styling errors

### v.2.0.2
- Fixed: 60FPS never applied
- Fixed: SSL Handshake fix for truncated messages
- Added: dark mode and xtreme dark mode setting for the app itself thanks to @andrecuellar!
- Removed: App category="maps" so nav buttons recognize the app again
- Fixed: Multiple Button Events and double/tripple skips
- Fixed: USB Permission Request thanks to @Bastel2020
- Added: Setting for Disable stretch to fit. This will fix  wrong rendering on some devices @thanks to tsabaia
- Fixed: Touch screen accuracy when not in full screen mode for older devices
- Fixed: Big Icon-Button on main screen when the dpi is small and the screen is wide

### v.2.0.1
- Fixed: Multiple volume sliders appearing on modern devices (Pixel 9 fix)
- Added: Support for Media Button emulation (SWC improvement for MacroDroid etc.)
- Added: App shortcut and deep link for full app exit (headunit://exit)
- Added: Improved Wi-Fi Direct reliability with recursive discovery and ping handoff
- Added: Romanian translation 🇷🇴, thanks to @LeeWiu
- Merged PR #189: Adding navigation message handling, thanks to @Bastel2020
- Merged PR #215: Fix USB reconnect race and stale dongle data after AA exit, thanks to @andrecuellar
- Merged PR #205: Fix wireless dongle disconnect on network changes, thanks to @andrecuellar
- Merged PR #216: Add Bluetooth SCO microphone support, thanks to tgigli

### v.2.0.0
- Added Wi-Fi Direct (P2P): Support. Connect your phone to the headunit without a shared network or hotspot. The headunit now automatically becomes visible as a P2P peer.
- Refactored Connection Core: Complete rewrite of the internal connection handling using the new **CommManager**. Improved stability, faster handshakes, and better coroutine integration.
- Enhanced Fullscreen Logic: Choose between "Immersive" (hide all), "Status Only" (keep navigation bars), or "None". Improved compatibility for devices where buttons were previously obscured.
- Added Auto-Optimization Wizard: Automatically recommends the best Resolution, DPI, and Codec for your specific hardware.
- Added Early MediaSession Initialization: Fixes audio routing issues where the phone would sometimes play sound through its own speakers instead of the headunit.
- Added New Logging System: Integrated log level control and file capture for easier debugging.
- **IMPORTANT** Fixing Android Auto 16.4 intents for selfmode. In Wireless Helper too. Please update to 1.2.0

### v.1.15.1
- New Feature: Added Auto-Optimization Wizard to automatically find the best Resolution, DPI, and Codec settings for your hardware.
- Bugfix: Fixed Self Mode failing to start in offline/hotspot scenarios (Network ID 0 fix).
- Bugfix: Improved Audio Routing. The phone is now more likely to route audio to the headunit immediately upon connection by using an early-initialized MediaSession with remote playback metadata.
- Bugfix: Fixed GPS Speed calculation. Speeds were previously doubled due to an incorrect unit conversion (knots instead of mm/s).
- UI: Improved Settings readability on small screens by allowing multi-line descriptions.

### v.1.15.0
- Added arabic language thanks to A5H0
- Added new intent for setting day/night mode for maps
- Added new window flags for older devices to finally fix fullscreen issues
- Added new intents to make the headunit recognize the app as navigation app
- Added LegacyOptimizer which will handle things directly and faster for single core cpus. Should improve the performance on Android 4.1 - 4.4 Devices
- Fixed BT Permission Bug
- Changed the Twilight-Calculator for better switch to day/night on auto mode to prevent to bright display
- Added more mediasession logic to gain audio focus and audio routing
- Merged Retry Button on connect screen, thanks to @andrecuellar
- Merged auto connect usb feature, thanks to @andrecuellar

### v.1.14.3
- New Feature: Added **App Shortcuts** for quick Connect/Disconnect. Now compatible with Samsung Modes & Routines.
- New Feature: Registered OpenHU as an official system navigation provider (Category: Maps).
- Improvement: Added `headunit://disconnect` intent for remote session termination.
- Fixed: Resolved potential app freezes during long-running sessions by optimizing memory hygiene and FIFO handling.
- Compatibility: Improved support for Amazon Fire Tablets and devices without GPS hardware by marking hardware features as optional.

### v.1.14.2
- Bugfix: Resolved `SuperNotCalledException` crash in MainActivity.
- Bugfix: Notification and Exit Button do not close the app
- Improvement: Removed old legacy Invisible Bluetooth Setting to prevent Bluetooth from start on the whole time
- Localization: Corrected "Version" translations in multiple languages.

### v.1.14.1
- Improvement: Integrated USB Auto-Connect into "Auto-Connect Last Session". App now behaves like a native headunit and connects automatically on startup or USB plug-in.
- New Feature: Added USB Soft-Reset logic. Automatic recovery from USB "stalls" without needing to replug the cable.
- Major Improvement: Audio focus and routing overhaul. Added `MediaSession` support and immediate focus response to phone. Fixes issues where background apps on the tablet would block Android Auto audio.
- Improvement: Robust Task Switching. Leaving the app via Home button or clicking the Launcher icon no longer breaks the connection. Music continues in background, and clicking the icon/notification correctly returns to the projection.
- New Feature: Enhanced Key Debugger ("Key-Sniffer"). Prominent display of all key events, including special characters (ö, ü, ß) and proprietary steering wheel intents (MTC, FYT).
- New Feature: Official Navigation App Registration. OpenHU is now recognized as a navigation provider (`geo:`, `google.navigation:`, `android.intent.action.NAVIGATE`). Compatible with hardware "NAV" buttons.
- Bugfix: Removed redundant "Already connected" and "Reconnection required" alerts for a smoother user experience.
- Localization: All new strings translated into 10 languages.

### v.1.14.0
- Added Separate volume setting #91
- Added Auto-Start on Bluetooth Option
- Merged PR #134 - Fixing Connection on Mediathek Headunits
- Merged PR #131 - Fixes SystemUI on < Android 6 Devices
- Merged PR #127 - Fixing Audio Truncation

### v.1.13.3
- Fixed Screen Issues on Android 4 with header and navigations #114
- Fixed Night-Mode Bug #116
- Merged several PR for better Language Handling with a new language selector. Thanks to @andrecuellar

### v.1.13.2
- Fixed margins now working for devices prior Android 5 Lollipop
- Fixing warnings
- Fixing broken colors on mixed daynight values
- Fixed a bug where a message is bigger than thought after about 20 minutes and connections closes

### v.1.13.1
- Fixed Custom Insets Dialog with a Scrollview
- Fixed 4 app crashes listed in play console
- Fixed 2 warnings in play console for edge-to-edge display
- Fixed a race condition in ssl read/write
- Preventing disconnect if just one package was broken/corrupt in ssl transfer

### v.1.13.0
- Improvement: USB stability overhaul (implemented 16KB internal buffer)
- New Feature: Custom Insets (Margins) setting with live preview
- Fixed: Video decoder blackscreen on some AI-Boxes (H.264 NAL padding)
- Fixed: UI focus issues in Settings causing system bars to reappear
- Fixed: Native SIGABRT crashes during reconnection
- Cleaned up Debug settings

### v.1.12.0
- Major Improvement: Wireless Connectivity overhaul (Socket Reuse, better Handshake)
- New Feature: Wireless Mode Switch (Manual, Auto-Scan, Wireless Helper Support)
- Added: Support for Wireless Helper companion app
- Fixed: Android 15 (16KB page size) compatibility for native libraries

### v.1.11.1
- Improvement: 1440p and h265 are now checked both. Some old devices have more than 1080p but no h265 support and android auto crashes with Error 11
- Fixed bug in Kitkat Devices on search for wireless devices
- Merged PR #94 - Fixed blurry icon. Thanks to @nicoruy
- Merged PR #95 - Make Settings own View to apply directly. Thanks to @nicoruy

### v.1.11.0
- New Feature: Advanced Night Mode (Light Sensor, Screen Brightness, separate thresholds, manual time)
- Improvement: Audio Stuttering fixed (Optimized ACK handling)
- Improvement: USB Reconnection stability (Added "Reconnection Required" dialog for stuck sessions)
- Improvement: WiFi Discovery (Added Multi-Interface Scan and NSD/mDNS support)
- New Feature: Enhanced Service Notification (Reduced noise, added Exit button)
- Added: Spanish translation 🇪🇸 thanks to @andrecuellar
- Added: Ukraine translation 🇺🇦 thanks to welshi32
- Bugfix: Non-Fullscreen View was stretched, touch could be off
- Bugfix: Wifi with Headunit Server now works with hotspot

### v.1.10.4
- Added: Dutch translation 🇳🇱 thanks to safariking
- Several black screen and connection error enhancements
- Bugfix: Crash in Background if not started as foreground service

### v.1.10.3
- Bugfix: Force Software Decoder wasn't getting always the sw decoder
- Added: Russian translation 🇷🇺 thanks to @prostozema
- Enhancement: Fixing small issues in the video-decoder which should help lower spec devices to render properly (but act a little bit slower perhaps)

### v.1.10.2
- Bugfix - Button Mapping ignored #71
- New Feature: Screen-Orientation Feature to lock to a certain orientation (Landscape/Portrait) #69 thanks to @JanRi3D
- Enhancement: SSL will now attempt multiple times and not break instantly thanks to @MicaelJarniac
- Added: Chinese(Traditional) translation 🇹🇼 thanks to @GazCore
- Added: Polish translation 🇵🇱 thanks to @Kacper1263
- Added: Czech translation 🇨🇿 thanks to @teodortomas
- Fixed brazilian portuguese folder name

### v.1.10.1
- Bugfix: Added missing 3 Byte startcode which stops some devices to start the projection
- Added PR #68 - Fix Wifi Direct detection thanks to @rakshan-kumr
- Added PR #67 - Brazilian Portuguese translation 🇧🇷 thanks to @MicaelJarniac
- Added PR #66 - Add conscrypt to fix error 7 on lower Android versions 🚀, thanks to @JanRi3D
- The old jni files and c code can maybe be removed when PR #66 is performing great. So we can get rid of that again :)

### v.1.10.0
- New Feature: Portrait Mode Support (Dashboard & Projection) with smart resolution scaling Known Bug is, that map is unresponsive to touch. That is in all HU apps
- New Feature: Redesigned Keymap Screen (easier configuration)
- New Feature: Right Hand side driving setting (#63)
- New Feature: Auto-Connect last session (Thanks to @JanRi3D) (#21)
- New Feature: Auto-Selfmode if enabled in settings
- New Feature: Allow sideloaded apps (#57)
- Localization: Added German Translation 🇩🇪 Other translations are highly appreciated
- Improvement: TextureView is now the default renderer (better compatibility for most devices)
- Improvement: Fixed Dashboard layout rotation
- Rewrite: Completly Rewrite the Video-Decoder as it was undebuggable. Removed the async mode and more

### v.1.9.0
- New Feature: GLES20 Video Renderer (Fixes black screen/artifacts/scaling on older Head Units)
- New Feature: In-App Log Export (Save to file/Share) for easier debugging
- Improvement: Audio Sink Logic fixed (System Audio always advertised) -> Fixes black screen when Audio Sink is disabled
- Improvement: Video Decoder optimized for legacy devices (Buffer size adjustments, Overflow handling)
- Hopefully Fix: Audio Stuttering resolved (Buffer/Queue logic reverted to stable state)
- Fix: Video Fragmentation on some devices (Support for split frames/Offset 2 headers)
- Fix: Crash on Android 5.1 (NoSuchMethodError)
- Fix: Audio-Sink disable not working
- UI: Consistent Dialog Theme (Teal/Rounded) and improved list buttons
- Compatibility: Verified support for Android 4.1+ (minSdk 16)
- Compatibility: Bring back native SSL Support (JNI) for better performance on older devices (Toggle in Debug Settings)

### v.1.7.0
- Added WiFi Network Discovery (Port Scan) with Auto-Connect
- Added Intent Support (`headunit://connect?ip=...`) for automation
- Added Wifi-Launcher Support with new setting
- Updated README

### v.1.6.3
- Added mandatory Safety Disclaimer on first start
- Improved audio stability and fixed stuttering issues
- Enhanced full-screen stability with transparent system bars
- Fixed WiFi disconnection synchronization issues (ByeBye request)
- General UI and stability improvements

### v.1.6.2
- Fixed critical screen flickering during startup and fullscreen transitions
- Resolved video decoder freezing issues on tablets and older devices
- Improved system bar handling for a more stable projection experience

### v.1.6.1
- Added "About" page with version info, changelog, and license
- Added "Force Legacy Decoder" (synchronous mode) setting to fix issues on some devices (e.g., Mediatek)
- Improved surface handling to prevent crashes on decoder reconfiguration
- Fixed "Unsaved changes" dialog in settings
- Updated UI with consistent back arrows and theming
- Fixed black screen issues on some devices by optimizing decoder initialization

### v.1.6.0
- Fixed the selfmode not working outside the wifi bug
- Redesign of App, Look and feel with modern Material 3
- Better Settings
- Huge Android Auto Protocol Updates
- Clicking Exit in Android Auto now closes the projection

### v.1.5.0
- Complete Rewrite of the Video decoder for better Video-Performance
- Updated Android-Auto Protocol with the latest available codecs (h265 for example)
- Added 1440p Video-Option(Note this only works with h265!)
- Added FPS-Setting
- Added Codec-Setting
- Added Force to Use Software decoder Setting
- Merged the Android Native SSL Library and get rid of the old jni files

### v.1.4.1
- Fixing Touch-Events for devices with higher resolutions
- Removing file-log and logging is only enabled if debug is on

### v.1.4.0
- Added Selfmode
- Better Close App

### v.1.3.0
- Changed the Settings Layout Look and feel
- Added DPI Option
- Added full screen option
- Fixing Keymap Changes and button recognition

### v.1.2.1 - Resolution enhancement
- Just a minor enhancement for the resolution. Not yet perfect in my opinion but better than before
- The is the last release for this year. Happy Holidays to all and a happy new year

### v.1.2.0 - Bugfix Release
- Added Exit button to app
- Added resolution settings back for better compatibility with different screen sizes
- Added Option for which texture to use. Some devices perform better on SurfaceView, some on TextureView
- Fixed keymapping
- Fixed a lot of color issues
- Fixed a bug where the app crashed on startup on some devices
- Fixed Layout on wider screens
- Some rewrite, and small bugfixes

### v1.1.0 - New Design
- Changed the basic design to a modern look and bigger buttons
- Hopefully fixed audio-stutters with audio thread and some logs
- Removed some deprecations

### v1.0.0 - Initial Revived Release
- Updated dependencies to latest versions.
- Improved compatibility with newer Android versions.
- Added Multitouch-Support
- Some sort of wireless support with Headunit-Server on Phone

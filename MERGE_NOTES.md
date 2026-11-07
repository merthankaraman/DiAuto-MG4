# Merge Skittle6938 2.2.2 → mastroiannim/DiAuto-MG4 (branch `merge/skittle-into-main`)

Il branch è stato **compilato e verificato con successo con Gradle**: tutti i 726 unit test passano e gli APK debug (`carDebug`, `phoneDebug`) vengono assemblati correttamente. Prima del rilascio servono la prova su vettura e gli altri controlli manuali (vedi sotto).

## Come importarlo

```bash
cd DiAuto-MG4                       # il tuo clone, su main = 5e6ca5d
git fetch /percorso/diauto-merge-2.3.0.bundle merge/skittle-into-main:merge/skittle-into-main
git checkout merge/skittle-into-main
./gradlew :app:testCarDebugUnitTest :app:assembleCarDebug :app:assemblePhoneDebug
```

Il merge ha due genitori (`5e6ca5d` = tuo main, `b8aa49c` = Skittle 2.2.2), quindi la storia di
entrambi resta. Un commit successivo corregge il nome dell'APK nel README.

## Decisioni applicate
- Solo MG4, niente Wi-Fi Direct, nuova home di Skittle.
- Identità app `com.allgo.app.androidauto` (la vecchia `com.drivehub.diauto.mg4` viene importata
  e rimossa al primo avvio dalla nuova app; installare prima la nuova).
- Versione `2.3.0`, `versionCode 2200` (sopra il 2143 di Skittle). Tag di rilascio: `v2.3.0`.
- OTA dal repo `mastroiannim/DiAuto-MG4` (SHA-256 obbligatorio, scelta APK per flavor).

## Eliminato di proposito
`WifiDirectManager`, `AutomationActivity`, `DiagnosticsFragment`, `SettingsFragment`, `SUExecutor`,
layout `fragment_home` del 1.9 (e quello portrait, ripristinato alla versione di Skittle).
Il fix 957d982 (hotspot: non riscrivere valori vecchi) non ha più bersaglio: nessun codice
scrive `hotspot-ssid/password/interface` fuori da `Settings.kt`.

## Riapplicato da mastroiannim (dentro il codice di Skittle)
- Workflow di release firmato, CI con secret scan / dependency review / Semgrep, `allowBackup=false`.
- Telegram fuori dalle build release, protobuf 3.25.9, varianti release car/phone.
- `isUserExitActive` persistente (AapService, AutoStartReceiver) + `ManualPokePolicy`
  con coordinatore seriale nel poke manuale (usa `refreshAccessPoint()` e `POKE_HOLD_MS` di Skittle).
- `SoftApBssidPolicy` live-first (test con MAC anonimizzato di Skittle), `CarNetworkQuirks` senza
  porta di ripiego occupata, `ArpNudger`, `Mg4EnergyProvider` con retry, scelta APK OTA per flavor,
  share del crash report quando Telegram non c'è, filtro keycode in `RemoteControlReceiver`.
- Catalogo condizioni/azioni e matrice firmware (docs), note di sicurezza PR-1…8.

## Conflitti risolti a mano (da rivedere)
| File | Scelta |
|---|---|
| `AapService.kt` | Skittle per claim audio e watchdog; entrambe le condizioni nell'auto-start BT; watchdog usa `isUserExitActive`; **rimossa** la chiamata mastro `startSaicAndroidAutoMediaPublication` (Skittle usa `onMediaPlaybackStarted`) |
| `CarKeyBroadcastReceiver.kt` | `handleSaicHardkey` ora restituisce Boolean: consuma il tasto solo se non c'è un'altra sorgente attiva; tasti volante fuori da media → **non** consumati; abort generico eliminato (come mastro) |
| `SaicCarAudioSource.kt` | versione Skittle (riusa lo stesso `AudioManager`, `lastClaimElapsedMs`) |
| `MicRecorder.kt` | Skittle + SCO asincrono di mastro |
| `OtaReleaseFetcher.java` | URL mastroiannim + `BuildConfig.FLAVOR` |
| `Settings.kt` (auto) | migrazione che preserva scelte esplicite USB-only / Wi-Fi-only (Skittle le espandeva a USB+Wi-Fi) |
| README / CHANGELOG | README di Skittle con link al tuo repo; il tuo README è in `docs/BUILD_AND_RELEASE_1.9.md`; CHANGELOG con voce 2.3.0 in cima |

## Controlli statici fatti
Nessun marcatore di conflitto; tutti gli XML ben formati; classi del manifest tutte presenti;
nessun riferimento rimasto alle classi eliminate; `R.string/layout/drawable/color` risolti;
firme di `pokeDevice`, `refreshAccessPoint`, `Mg4EnergyProvider.start` coerenti; test toccati da
mastro riletti contro il codice unito; NDK 27.2 coerente tra Gradle e workflow.

## Da verificare a mano
1. ~~**Compilazione e unit test** (`OtaReleaseAssetsTest`, `LocaleHelperTest`, `SoftApBssidPolicyTest`, `ManualPokePolicyTest`)~~: **VERIFICATO** (726 test passati; fix compatibilità Robolectric `GraphicsMode.LEGACY` su host Windows ARM64).
2. **Tasti al volante** su vettura: ricevitore SAIC `RECEIVER_NOT_EXPORTED` di mastro con la nuova identità; semantica di consumo cambiata.
3. **Microfono**: se `MicRecorder.start()` gira sul thread principale, la guardia di mastro passa al microfono locale.
4. **Migrazione dall'ID vecchio** su una vettura reale; verificare che l'app vecchia sia rimossa.
5. `docs/INSTALL.md` dice di reinserire SSID/password in *Connection setup*, ma non trovo codice che scriva quelle preferenze.
6. `shortcuts.xml` punta ancora ad `AutomationActivity` (così com'era in Skittle).
7. Il README di Skittle dice che le chiavi non sono nel repo, ma `tools/platform.*` c'è (certificato = chiave di test AOSP, non segreta).
8. `scripts/rollback_mg4.ps1` resta sul vecchio ID di proposito: **non puntarlo a `com.allgo.app.androidauto`**.
9. `docs/MG4_ROLLBACK.md` e `docs/MG4_TESTING.md` non sono stati rivisti per il flusso 2.3.0 oltre alla nota in testa.

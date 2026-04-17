# Lumi Android App

Aplicație Android pentru dispozitivul Lumi — primește audio + imagine prin Bluetooth, procesează cu STT românesc, și trimite la Gemini AI pentru răspunsuri inteligente.

## Funcționalități

| Funcție | Detalii |
|---------|---------|
| **BLE** | Primește cadre PCM audio + JPEG de la dispozitivul Lumi |
| **STT Română** | Android SpeechRecognizer configurat pe `ro-RO` |
| **Gemini Flash** | Răspunsuri rapide pentru sarcini simple (identificare obiecte, rezumat notificări) |
| **Gemini Pro** | Orchestrare automată pentru sarcini complexe (WhatsApp, contacte, mesaje personalizate) |
| **Memorie conversație** | Ultimele 5 interacțiuni trimise ca context la fiecare cerere |
| **Notificări** | Citește notificările telefonului via `NotificationListenerService` |
| **Contacte** | Rezolvă aliasuri ca "mama", "tata" la contacte reale |
| **WhatsApp** | Deschide WhatsApp cu mesaj pre-completat (deep link) |

## Rutarea sarcinilor

```
Utilizator vorbește → STT → prompt text
         ↓
    Gemini Flash clasifică: SIMPLU sau COMPLEX?
         ↓                        ↓
   Gemini Flash             Gemini 2.5 Pro
  (răspuns rapid)          (orchestrare multi-pas)
         ↓                        ↓
    Răspuns afișat ←──────────────┘
    + salvat în memorie (max 5)
```

## Structura proiectului

```
app/src/main/java/com/lumi/app/
├── MainActivity.kt              # UI principal + gestionare permisiuni
├── LumiApplication.kt           # Application class
├── bluetooth/
│   ├── LumiBluetoothManager.kt  # BLE scanner + GATT client
│   └── LumiBluetoothService.kt  # Foreground service BT
├── ai/
│   ├── GeminiClient.kt          # REST client Gemini API
│   ├── TaskRouter.kt            # Rutare Flash/Pro
│   └── ConversationMemory.kt    # Memorie ultimele 5 interacțiuni
├── stt/
│   └── RomanianSTT.kt           # SpeechRecognizer ro-RO
├── notifications/
│   └── LumiNotificationService.kt  # NotificationListenerService
├── contacts/
│   └── ContactsHelper.kt        # Căutare și rezolvare aliasuri contacte
├── messaging/
│   └── MessageSender.kt         # Trimitere WhatsApp / SMS
├── settings/
│   ├── SettingsActivity.kt      # UI setări
│   └── AppSettings.kt           # SharedPreferences wrapper
└── ui/
    ├── MainViewModel.kt          # ViewModel principal
    └── MessageAdapter.kt         # RecyclerView adapter chat
```

## Protocol BLE (Lumi Device ↔ App)

**Service UUID:** `12345678-1234-1234-1234-123456789abc`

| Characteristic | UUID suffix | Tip | Descriere |
|---------------|-------------|-----|-----------|
| Audio Stream | `...ab1` | NOTIFY | Cadre PCM s16le 16kHz mono |
| Image Stream | `...ab2` | NOTIFY | Chunks JPEG (protocol chunk) |
| Command | `...ab3` | WRITE | Comenzi către dispozitiv |
| Status | `...ab4` | READ | Status dispozitiv |

**Protocol chunk imagine:**
- `byte[0] = 0x00..0xFE` → index chunk
- `byte[0] = 0xFF` → ultim chunk (semnal reassembly)
- `byte[1..]` = date JPEG

**Protocol audio:**
- `byte[0..1]` = număr secvență (big-endian uint16)
- `byte[2..]` = date PCM s16le

## Cum construiești APK-ul

### Metoda 1: Android Studio (recomandat)

1. Instalează [Android Studio](https://developer.android.com/studio)
2. Deschide folderul `Lumi/` în Android Studio
3. Lasă Gradle să sincronizeze dependențele
4. Conectează telefonul sau crează un emulator
5. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
6. APK-ul va fi în `app/build/outputs/apk/debug/app-debug.apk`

### Metoda 2: Linie de comandă

```bash
# Necesită: JDK 17+, Android SDK cu build-tools 34
export ANDROID_HOME=/path/to/android/sdk

# Copiază local.properties.example → local.properties și completează sdk.dir
cp local.properties.example local.properties
# Editează local.properties cu calea la SDK-ul tău

# Build APK debug
./gradlew assembleDebug

# APK se găsește la:
# app/build/outputs/apk/debug/app-debug.apk
```

### Instalare pe telefon

```bash
# Via ADB (telefonul conectat cu USB debugging activat)
adb install app/build/outputs/apk/debug/app-debug.apk

# SAU transferă APK-ul pe telefon și deschide-l din Files
# (necesită "Instalare din surse necunoscute" activat în Setări)
```

## Configurare inițială

1. Deschide aplicația
2. Acceptă toate permisiunile (microfon, contacte, Bluetooth, notificări, SMS)
3. Activează **accesul la notificări** din prompt-ul care apare
4. Apasă **Setări** (iconița din toolbar)
5. Introdu **cheia API Gemini** (de la [Google AI Studio](https://aistudio.google.com/))
6. Selectează modelul rapid preferat
7. Alege dispozitivul Bluetooth Lumi din lista de dispozitive pereche
8. Salvează → Apasă "Conectare"

## Permisiuni necesare

| Permisiune | Motiv |
|------------|-------|
| `RECORD_AUDIO` | STT și microfon |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | Conectare dispozitiv Lumi |
| `READ_CONTACTS` | Rezolvare aliasuri ("mama" → contact) |
| Notification Listener | Citire notificări și mesaje |
| `SEND_SMS` / `READ_SMS` | Trimitere/citire SMS |
| `INTERNET` | API Gemini |

## Modele Gemini suportate

| Model | ID | Utilizare |
|-------|----|-----------|
| Gemini 1.5 Flash | `gemini-1.5-flash` | Rapid, stabil |
| Gemini 2.5 Flash | `gemini-2.5-flash-preview-04-17` | Rapid + capacitate mai mare |
| Gemini 2.5 Pro | `gemini-2.5-pro-preview-03-25` | Complex, orchestrare |

## TODO pentru producție

- [ ] Implementare trimitere automată WhatsApp via Accessibility Service
- [ ] STT din stream PCM BLE (acum folosește microfonul telefonului)
- [ ] TTS pentru redarea răspunsurilor prin difuzorul Lumi
- [ ] Autentificare și stocare securizată a cheii API (Android Keystore)
- [ ] Compresie imagine adaptivă bazată pe lățimea de bandă BLE
- [ ] Suport pentru mai multe dispozitive Lumi simultan

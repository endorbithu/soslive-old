# SOSlive

Android és iOS alkalmazás vészhelyzeti élő videó közvetítéshez (RTMP), helyadattal, fotóval és
értesítéssel. **Nincs SOSlive backend:** az app a user saját **Google Drive**-jára írja az
eseményeket és a beállításokat JSON fájlokba, a videót a stream szerverre küldi; a web
(`https://<webapp>`) a Drive fájlokat csak olvassa.

A műszaki szerződés: „SOSlive mobil app – átállási útmutató” és a
`docs/EVENT_FORMAT.md` (endorbithu/soslive-webapp).

## Repó szerkezet

| Mappa | Tartalom |
|---|---|
| `android/` | Új Android app (Kotlin, Jetpack Compose) – önálló Gradle projekt |
| `ios/` | Új iOS app (SwiftUI) – XcodeGen projekt + `SOSliveCore` Swift csomag |
| `legacy/` | A régi (2017–2019-es) Java Android app változatlanul, csak referenciának |
| `docker-compose.yml` | Helyi RTMP/HLS szerver (MediaMTX) a stream kipróbálásához |

## Hogyan működik

```
mobil app ──(Google token, drive.file)──► user Google Drive-ja
   │                                         SOSlive/            (privát mappa)
   │                                           config.json       (privát, csak az app írja)
   │                                           2026-09-29 14:03:22.json  (esemény, "anyone with link")
   │                                           img ….jpg          (fotó, "anyone with link")
   └──(RTMP)──► stream szerver ──(HLS)──► néző böngészője  ◄── web: https://<webapp>/e/{fileId}
```

- **Belépés:** csak Google (`openid email profile drive.file`); a token a telefonon marad.
- **SOSlive mappa:** belépéskor megkeresi (`appProperties {"soslive":"root"}`), ha nincs,
  létrehozza; több találatnál a legrégebbi az érvényes.
- **config.json:** értesítendő e-mailek / telefonszámok, `max_events`; csak az app írja,
  ismeretlen mezőket megőriz. Első belépéskor a régi Java app SMS-számai átkerülnek bele.
- **Esemény:** fájlnév a kezdés ideje UTC-ben, tartalom `{"v":1,"stream":"…","entries":[…]}`
  (`pos` / `msg` / `img`). Létrehozás után „anyone with the link” megosztás, a link
  `https://<webapp>/e/{fileId}`. Az app mindig a teljes fájlt tölti fel (összevonva,
  újrapróbálással; 404 esetén leáll), pozíció legfeljebb 30 mp-enként.
- **Rotáció:** új esemény után a `max_events`-nél régebbiek a Drive kukájába kerülnek.
- **Értesítés:** SOS-kor a link SMS-ben (Androidon `SEND_SMS` engedéllyel automatikusan,
  különben kitöltött SMS) és e-mailben (kitöltött levél) megy a `config.json` címzettjeinek.
  A válasz natív SMS-ként jön; az app nem olvas SMS-t. Az esemény fájlba nem kerül
  telefonszám vagy e-mail cím.
- **Stream:** cserélhető `StreamProvider`; most konfigurációs sablon: eseményenként véletlen
  kulcs, publikálás `<RTMP_URL>/<kulcs>`, a `stream` mezőbe a HLS sablon kerül. Hosztolt
  szolgáltató (Mux, Cloudflare…) később köthető be – annak API kulcsa szerverre való, nem az appba.
- **Szimulált Drive:** ha nincs Google kliens azonosító beállítva, a fájlok csak a
  telefonon tárolódnak (fejlesztéshez; a linkeket más nem nyithatja meg).

## Google Cloud beállítás

A mobil appok **ugyanabban a Google Cloud projektben** kapnak OAuth klienst, mint a web
(`drive.file` projektenként érvényes – csak így látja a web a mobil fájljait):

- **Android** OAuth kliens: package `info.soslive.stream` + az aláíró kulcs SHA-1-e
  (debug és release kulcshoz is), valamint a projekt **Web** kliens azonosítója az appban.
- **iOS** OAuth kliens: bundle ID `info.soslive.stream`.
- Scope-ok: `openid`, `email`, `profile`, `https://www.googleapis.com/auth/drive.file`.
- OAuth consent screen: élesben „In production” (Testing módban 7 nap után lejárnak a tokenek).

## Konfiguráció

**Android – `android/local.properties`** (vagy `-P` gradle property):

```properties
soslive.webappUrl=https://soslive.example.com
soslive.streamRtmpUrl=rtmp://stream.example.com:1935/live
soslive.streamHlsTemplate=https://stream.example.com/live/{key}/index.m3u8
# Web OAuth kliens azonosító (ugyanaz a projekt). Üresen: szimulált Drive.
soslive.googleWebClientId=1234-web.apps.googleusercontent.com
```

**iOS – `ios/Config/Secrets.xcconfig`** (minta: `Secrets.example.xcconfig`):
`SOSLIVE_WEBAPP_URL`, `SOSLIVE_STREAM_RTMP_URL`, `SOSLIVE_STREAM_HLS_TEMPLATE`,
`GOOGLE_IOS_CLIENT_ID`, `GOOGLE_REVERSED_CLIENT_ID`, `SOSLIVE_DEVELOPMENT_TEAM`.

Helyi stream szerver teszteléshez: `docker compose up` (MediaMTX, RTMP :1935, HLS :8888).

## Fejlesztés

```bash
# Android
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug

# iOS
cd ios && swift test --package-path Packages/SOSliveCore
brew install xcodegen && xcodegen generate && open SOSlive.xcodeproj
```

A CI (`.github/workflows/ci.yml`) mindkét platformot buildeli és teszteli.

### Felépítés

```
android/app/src/main/java/info/soslive/stream/
├── auth/        Google belépés + drive.file (Credential Manager, AuthorizationClient), fiók tár
├── drive/       Drive REST kliens, szimulált Drive, SosliveDrive (mappa/config/esemény/rotáció),
│                EventWriter, fájlformátumok
├── stream/      StreamProvider (sablon), StreamController (RootEncoder RTMP)
├── location/, sms/   helyadat, SMS / e-mail értesítés
└── ui/          Compose képernyők: belépés, fő képernyő, eseményeim, beállítások

ios/
├── Packages/SOSliveCore/   ugyanez platformfüggetlenül (DriveAPI, SosliveDrive, EventWriter,
│                           formátumok, StreamProvider) + tesztek
└── SOSlive/                SwiftUI app: GoogleSignIn, HaishinKit RTMP, képernyők
```

### Nyitott kérdések (az útmutatóból)

- Automatikus (háttér) értesítés kell-e, vagy elég a kitöltött SMS / e-mail?
- Képek tárhelye: most Drive + nyilvános megosztás (`drive.google.com/thumbnail`), ami nem
  minden esetben jelenik meg megbízhatóan.
- Régi események / linkek átvitele; végleges web cím.
- Céges (Workspace) fiókoknál az „anyone with link” megosztás tiltva lehet – az app jelzi.

### Ismert korlátok

- A közvetítés leáll, ha az app háttérbe kerül.
- A release build a debug kulccsal van aláírva (Android) – publikálás előtt saját kulcs kell.

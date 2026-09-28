# SOSlive

Android alkalmazás vészhelyzeti élő videó közvetítéshez (RTMP), helyadat-küldéssel, SOS SMS-sel,
fotó-bejelentéssel és hozzászólásokkal – felhasználókezeléssel (e-mail + jelszó, Google és
Facebook SSO). A backendet egyelőre egy **szimulált (mock) szerver** adja, amit később egy
valódi backend vált le ugyanazzal az API szerződéssel (`mock-server/openapi.yaml`).

> A korábbi (2017–2019-es) Java + yasea alapú verziót ez a kód teljesen leváltja.

## Gyors indítás

Az egyetlen kötelező beállítás az RTMP szerver címe.

```bash
# 1) Mock backend
cd mock-server
cp .env.example .env          # állítsd be: RTMP_URL=rtmp://<szerver>:1935/live
npm install
npm start                     # http://localhost:3000  (demo: demo@soslive.local / demo1234)

# 2) Android app (emulátorból a gép 10.0.2.2:3000 címen éri el a mock szervert)
./gradlew installDebug
```

Docker-rel ugyanez, opcionálisan helyi RTMP szerverrel (MediaMTX) is:

```bash
cp mock-server/.env.example mock-server/.env   # RTMP_URL=rtmp://<géped LAN IP-je>:1935/live
docker compose --profile rtmp up --build
# a stream nézése: ffplay rtmp://localhost:1935/live/<streamKey>
#                  vagy HLS: http://localhost:8888/live/<streamKey>
```

A stream kulcsot a backend adja eseményenként (`{yyyymmdd}_{userId}_{eventId}_{hash}`), vagy
egy fix kulcsot használ, ha `RTMP_STREAM_KEY` meg van adva (pl. YouTube / Facebook Live).

### Konfiguráció

**Backend – `mock-server/.env`** (részletek: `mock-server/.env.example`)

| Kulcs | Kötelező | Leírás |
|---|---|---|
| `RTMP_URL` | **igen** | RTMP ingest, pl. `rtmp://host:1935/live` – az app ide publikál: `<RTMP_URL>/<streamKey>` |
| `RTMP_STREAM_KEY` | nem | Fix stream kulcs minden eseményhez |
| `PUBLIC_WEB_URL` | nem | A szerver külső címe – ez kerül az SOS SMS linkjébe |
| `JWT_SECRET` | nem | Állítsd be, hogy újraindítás után is érvényesek maradjanak a bejelentkezések |
| `GOOGLE_CLIENT_ID` | nem | Ha meg van adva, a Google ID tokeneket valóban ellenőrzi |
| `FACEBOOK_APP_ID`, `FACEBOOK_APP_SECRET` | nem | Ha meg van adva, a Facebook tokeneket valóban ellenőrzi |

**App – `local.properties`** (vagy `-P` gradle property), minden érték opcionális:

```properties
# Mock/valódi backend címe. Alapértelmezés: http://10.0.2.2:3000/ (emulátor -> fejlesztő gép)
# Valódi telefonon: a gép LAN IP-je, pl. http://192.168.1.10:3000/
soslive.apiBaseUrl=http://10.0.2.2:3000/

# Valódi Google bejelentkezés: a Google Cloud Console *Web* OAuth kliens azonosítója
# (ugyanez menjen a backend GOOGLE_CLIENT_ID-jébe). Üresen: szimulált Google login.
soslive.googleWebClientId=

# Valódi Facebook bejelentkezés. Üresen: szimulált Facebook login.
soslive.facebookAppId=
soslive.facebookClientToken=
```

### Szimulált SSO

Ha egy szolgáltatóhoz nincs kliens azonosító, a „Google (szimulált)” / „Facebook (szimulált)”
gomb egy párbeszédablakot nyit, ahol e-mail címet és nevet lehet megadni. Az app ilyenkor
`mock:<email>|<név>` tokent küld, amit a mock backend elfogad, mintha a szolgáltatótól jött
volna. Azonos e-mail címmel a különböző bejelentkezési módok ugyanahhoz a fiókhoz kapcsolódnak.

## Funkciók

- **Felhasználókezelés**: regisztráció, bejelentkezés, Google (Credential Manager) és Facebook
  login, JWT access token + rotálódó refresh token (401 esetén automatikus frissítés), kijelentkezés.
- **SOS**: egy gombnyomás → esemény létrehozása helyadattal → RTMP élő közvetítés → SMS az SOS
  telefonszámokra az esemény linkjével (SEND_SMS engedély nélkül az SMS app nyílik meg kitöltve).
- **Élő videó** (nem SOS) ugyanígy, SMS nélkül; automatikus újrakapcsolódás (3×).
- **Fotó bejelentés**: a rendszer kamera appjával, feltöltés az eseményhez; 2 órán belül a további
  fotók és hozzászólások ugyanahhoz az eseményhez kerülnek („Új esemény” menüvel zárható).
- **Helyadat**: induláskor és közvetítés alatt folyamatosan (Fused Location Provider).
- **Hozzászólások**: az aktív eseménynél 10 mp-enként frissül, olvasatlan számláló; a nézők a
  mock szerver nyilvános oldalán (`/e/<id>`, ez az SMS-ben küldött link) tudnak írni.
- **Eseményeim**: lista + részletek, hozzászólások.
- **Profil**: név, SOS telefonszámok (`+36301234567` formátum), SOS üzenet szövege.
- Magyar és angol felület.

## Architektúra

```
app/src/main/java/info/soslive/stream/
├── core/          AppConfig (BuildConfig értékek), UiText
├── data/
│   ├── remote/    Retrofit API (AuthApi, SosLiveApi), DTO-k, AuthInterceptor, TokenAuthenticator
│   ├── local/     DataStore: session, aktív esemény
│   └── repository/ Auth-, Profile-, EventRepository implementációk
├── domain/        modellek, repository interfészek, SosContacts validáció
├── auth/sso/      Google (Credential Manager), Facebook, szimulált SSO
├── stream/        StreamController – RootEncoder (Camera2 + MediaCodec) RTMP publisher
├── location/      LocationTracker (Fused Location, Flow)
├── sms/           SosSmsSender
├── di/            Hilt modulok
└── ui/            Compose képernyők + ViewModel-ek (auth, stream, events, profile, navigation)
```

- Kotlin, Jetpack Compose (Material 3), MVVM + egyirányú adatfolyam (StateFlow + egyszeri effektek)
- Hilt DI, Coroutines/Flow, Retrofit + OkHttp + kotlinx.serialization, DataStore
- Navigation Compose típusos route-okkal; a bejelentkezett állapot dönti el, melyik NavHost látszik
- minSdk 26, targetSdk 35

## Backend (mock-server)

Node 20 + Express, JSON fájl alapú tárolás (`data/db.json`), JWT. Az API szerződés:
[`mock-server/openapi.yaml`](mock-server/openapi.yaml) – a valódi backendnek ezt kell
megvalósítania, és az app változtatás nélkül működik vele (csak `soslive.apiBaseUrl` kell).

```bash
cd mock-server && npm test
```

## Fejlesztés

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

A CI (`.github/workflows/ci.yml`) a mock szerver tesztjeit és az Android buildet/teszteket is futtatja.

### Ismert korlátok / következő lépések

- A közvetítés leáll, ha az app háttérbe kerül (nincs foreground service).
- A tokenek titkosítatlan DataStore-ban vannak – éles verzióhoz titkosítás (pl. Tink) javasolt.
- A release build a debug kulccsal van aláírva – publikálás előtt saját keystore kell.
- A mock szerver egy példányos, fájl alapú – csak fejlesztésre való.

## Köszönet

A korábbi verzió a [yasea](https://github.com/begeekmyfriend/yasea) projektre épült; az új
streaming motor a [RootEncoder](https://github.com/pedroSG94/RootEncoder).

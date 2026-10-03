# Üzenet a SOSlive web csapatnak – mobil app változások

Szia! A mobil appok (Android, iOS) két ponton változtak. Az alábbiak alapján kellene módosítani
a webet és a `docs/EVENT_FORMAT.md`-t.

## 1. Az események külön `events` almappába kerülnek

Új Drive szerkezet (a tagek `appProperties {"soslive": …}` értékek):

```
SOSlive/                     soslive=root     privát
  config.json                soslive=config   privát (változatlan)
  events/                    soslive=events   ÚJ – megosztható (lásd 2. pont)
    2026-10-03 14:03:22.json soslive=event    + "anyone with link" (változatlan)
    img 2026-10-03 14:05:10.jpg soslive=image + "anyone with link" (változatlan)
```

- Az `events` mappa a SOSlive mappán belül van. Ha több ilyen mappa van, a legrégebbi
  (`createdTime`) az érvényes, ugyanúgy, mint a root mappánál.
- A korábban közvetlenül a `SOSlive/` mappába írt esemény- és képfájlokat az app az első
  indításkor átmozgatja az `events/` mappába. Ugyanazzal a fájlazonosítóval mozgatja őket,
  ezért a már kiküldött `/e/{fileId}` linkek továbbra is működnek.

**Teendő a weben:**
- A saját események listáját ne a `'<SOSlive id>' in parents` feltétellel kérdezze le. A helyes
  lekérdezés:
  ```
  appProperties has { key='soslive' and value='event' } and '<events id>' in parents and trashed=false
  ```
  Az `<events id>` a `SOSlive` mappán belüli `soslive=events` mappa azonosítója.
- Átmeneti időre érdemes mindkét mappát lekérdezni, mert ha egy telefon még nem futtatta az új
  appot, a régi események még a root mappában vannak.
- A `config.json` ugyanott maradt, és csak a mobil app írja.
- Ha a web saját maga is létrehozza a SOSlive mappát (első belépésnél), akkor ugyanígy hozza
  létre az `events` almappát is, vagy hagyja, hogy a mobil app hozza létre.

## 2. Megosztás: „Kik látják az eseményeidet”

A mobil app Beállítások képernyőjén X (az események tulajdonosa) megadhatja Y Google fiókjának
e-mail címét. Az app ekkor:
- az `events` mappát **reader** joggal megosztja Y-nal (`permissions.create`, `type=user`,
  `role=reader`, `sendNotificationEmail=true`, így a Google e-mailben értesíti Y-t);
- visszavonáskor törli ezt a jogosultságot (`permissions.delete`).

Y így csak az eseményeket és a képeket látja, a `config.json`-t (X értesítendő
telefonszámai és e-mailjei) nem. A mappa-megosztás öröklődik, ezért X később létrejövő eseményei
is látszanak Y-nak. A megosztottak listája a Drive-on van tárolva (az `events` mappa
permissions listája), a `config.json`-be nem kerül.

**Teendő a weben (Y nézete):**
- Új rész, például „Velem megosztott események”: X neve vagy e-mail címe (`owners[0]`), alatta
  X eseményei. Ez a nézet csak olvasható: Y nem írhat, nem törölhet és nem küldhet üzenetet X
  fájljába.
- **Felfedezés:** a `drive.file` scope önmagában **nem** látja X mappáját, és a `sharedWithMe`
  lekérdezésben sem jelenik meg. Két lehetőség van:
  1. **Google Picker** (ajánlott, nem kell érzékeny scope): Y egyszer kiválasztja a vele
     megosztott `events` mappát. Ezután a web a mappa azonosítóját eltárolhatja (pl. Y saját
     SOSlive mappájában egy `shared.json`-ban vagy a böngészőben).
     **Ellenőrizni kell**, hogy a mappa kiválasztása után a `drive.file` token látja-e a mappa
     tartalmát, beleértve a később létrejövő fájlokat is (`'<events id>' in parents`). Ha nem,
     akkor marad a 2. lehetőség.
  2. **`drive.readonly`** (vagy `drive.metadata.readonly` és a fájlok letöltése) scope-pal:
     ```
     sharedWithMe and appProperties has { key='soslive' and value='events' } and trashed=false
     ```
     Ez korlátozott scope, ezért nyilvános apphoz Google ellenőrzés és CASA biztonsági audit kell.
- **Visszavonás kezelése:** ha X visszavonja Y hozzáférését, a lekérdezések 403-at vagy 404-et
  adnak. Ilyenkor a web vegye ki X-et Y listájából, és ne mutasson hibát.
- Az egyes eseménylinkek (`/e/{fileId}`) a megosztástól függetlenül továbbra is „anyone with
  the link” módban működnek.

## 3. Emlékeztető: új mezők az esemény JSON-ban (korábbi változás)

```json
{"v": 1, "stream": "…", "stream_page": "…", "recording": "…", "entries": […]}
```

| Mező | Jelentés |
|---|---|
| `stream` | Közvetlenül lejátszható URL (HLS `.m3u8` / MP4). **Üres is lehet.** |
| `stream_page` | Opcionális: a user stream szolgáltatójának nézői oldala (pl. YouTube / Twitch). Nem biztos, hogy beágyazható, ezért linkként érdemes megjeleníteni. |
| `recording` | Opcionális: link a felvétel letöltéséhez vagy visszanézéséhez. |

Az ismeretlen mezőket a web hagyja figyelmen kívül.

## 4. Képek URL-je (`img` bejegyzés `url` mezője)

```
https://drive.google.com/thumbnail?id={fileId}&sz=w1600
```

- A képfájl (`soslive=image`) az `events` mappában van, és **„anyone with the link”**
  megosztással rendelkezik, ezért ez az URL bejelentkezés nélkül, közvetlenül betölthető
  `<img>`-ként. Nem HTML oldal, hanem egy legfeljebb 1600 px széles kép.
- Figyelem: ez a végpont nincs hivatalosan dokumentálva. Általában működik, de lehet lassú,
  és esetenként korlátozhatják. Ajánlott tartalék a weben arra az esetre, ha az `<img>` hibát
  ad (`onerror`):
  1. a fájl azonosítója az URL `id=` paraméteréből kiolvasható;
  2. `GET https://www.googleapis.com/drive/v3/files/{id}?alt=media&key=<API kulcs>`
     publikus fájlra hivatalosan működik; vagy a bejelentkezett user tokenjével;
  3. a válaszból blob, majd `URL.createObjectURL(blob)` → `<img src>`.
- Ha egyszerűbb, a mobil app az `img` bejegyzésbe külön `id` mezőt is írhat (pl.
  `{"t":…,"type":"img","url":…,"id":"<fileId>"}`), így a webnek nem kell az URL-t
  feldolgoznia. Szóljatok, ha kéritek.

## 5. Valódi fiókos teszt – mobil oldal

A CI-ben készült debug APK erre **nem alkalmas**: Google kliens azonosító nélkül szimulált
Drive-val fut, és a CI ideiglenes aláíró kulcsa nincs regisztrálva. A tesztelő saját gépén
kell buildelni:

- **Android:** az `android/local.properties` fájlban `soslive.webappUrl` és
  `soslive.googleWebClientId` (a web Cloud projektjének **Web** kliens azonosítója). A saját
  debug kulcs SHA-1-ét (`./gradlew signingReport`) fel kell venni a projekt **Android** OAuth
  kliensébe (package `info.soslive.stream`). Build Android Studióból.
- **iOS:** `ios/Config/Secrets.xcconfig`: `SOSLIVE_WEBAPP_URL`, `GOOGLE_IOS_CLIENT_ID`,
  `GOOGLE_REVERSED_CLIENT_ID`, `SOSLIVE_DEVELOPMENT_TEAM`; build Xcode-ból (Mac kell).
- Ha az OAuth consent screen „Testing” módban van, X és Y fiókja is legyen tesztfelhasználó.

X lépései a telefonon (a web doksi lépéssorához illesztve):
1. Belépés Google-lel, Drive engedély.
2. Egy esemény létrehozása (a Fotó a leggyorsabb, és képet is ad).
3. Beállítások → „Kik látják az eseményeidet” → Y e-mail címe → Hozzáadás.
4. *(Y a weben a Pickerben kiválasztja X `events` mappáját, és ellenőrzi a 2. eseményt.)*
5. **X új eseményt indít fotóval** – ez a döntő lépés: Y a weben frissít, és látnia kell az
   új eseményt **és** a képet is, újbóli Picker-választás nélkül.
6. Beállítások → Y eltávolítása → Y-nál a mappa csendben kiesik.

Ha az 5. lépésben Y nem látja az új eseményt, akkor a `drive.file` + Picker mappaválasztás
nem elég, és megcsináljuk az `index.json` tartalékot.

Kérlek, vezessétek át ezeket a `docs/EVENT_FORMAT.md`-be. Ha valami nem világos, szóljatok!

# Totò · Eduardo · De Crescenzo

Client Android nativo (**Android TV + Mobile**) che raccoglie in un'unica griglia i film, le commedie e i documentari di **Totò**, **Eduardo De Filippo** e **Luciano De Crescenzo** disponibili gratuitamente su **RaiPlay**, **Mediaset Infinity**, **LA7** e sul canale YouTube **"Il Teatro di Eduardo"**.

Stessa architettura di *Una Giornata Particolare*: Jetpack Compose + Media3 ExoPlayer, griglia uniforme, ricerca istantanea, ripresa della visione, comandi da telecomando.

## Caratteristiche

- **Catalogo multi-piattaforma** filtrabile per autore (Totò / Eduardo / De Crescenzo), piattaforma e genere (Film, Teatro, Documentari, TV).
- **Aggiornamento automatico**: ogni lunedì GitHub Actions riscansiona le piattaforme, salva `catalogo.json` e pubblica un nuovo APK. L'app scarica il catalogo aggiornato all'avvio, senza reinstallare.
- **Scansione dal dispositivo** (pulsante ⟳): cerca subito i titoli comparsi su RaiPlay e Mediaset Infinity, dall'Italia e quindi senza geoblocchi.
- **Player integrato** per i titoli in chiaro:
  - RaiPlay via relinker Rai (HLS/DASH); per i titoli protetti la licenza **Widevine** viene richiesta al server di licenze Rai, come fa il loro player web. Sottotitoli Rai quando disponibili (tasto **CC**).
  - Mediaset Infinity via ThePlatform (DASH/HLS senza protezione).
  - LA7 via Kaltura (HLS).
- **Titoli con login o abbonamento** (Mediaset Infinity protetti, YouTube): si aprono nell'app ufficiale con un clic.
- **Riprendi da dove eri rimasto**, barra di avanzamento sulle schede, ±10 s con il D-pad, ±30 s con i tasti media.

## Come funziona il riconoscimento

`app/src/main/assets/filmografia.json` contiene ~180 opere di riferimento (99 per Totò, 74 per Eduardo tra film e commedie, 9 per De Crescenzo). Un titolo trovato su una piattaforma viene accettato se:

- il cast o la regia citano l'autore (es. *Totò*, *Eduardo De Filippo*, *Luciano De Crescenzo*), oppure
- il titolo coincide con un'opera della filmografia e l'anno corrisponde (±1), oppure il titolo è abbastanza specifico da non essere ambiguo.

Così *Totò Riina* o *Totò Schillaci* restano fuori, mentre gli adattamenti recenti delle commedie di Eduardo (es. *Natale in casa Cupiello* con Castellitto) entrano.

Fonti dei dati: indice A‑Z e JSON dei programmi RaiPlay, feed ThePlatform di Mediaset, pagine LA7, playlist YouTube via `yt-dlp`.

## Aggiornare il catalogo a mano

```bash
python3 scripts/scrape_catalog.py           # veloce
python3 scripts/scrape_catalog.py --deep    # controlla tutti i programmi RaiPlay (alcuni minuti)
```

Per aggiungere un titolo che lo scraper non trova, inseriscilo in `data/aggiunte.json` (stesso formato di `catalogo.json`, piattaforma `url` per un link diretto a un file video).

## Build

```bash
./gradlew assembleRelease
```

L'APK di ogni release è nella sezione **Releases**. Per installare gli aggiornamenti sopra la versione precedente serve una firma stabile: aggiungi nei *Secrets* del repository `KEYSTORE_BASE64` (keystore in base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Senza secret la CI firma con una chiave di debug temporanea.

## Note

Uso personale. L'app non scarica né ridistribuisce video e non aggira protezioni: riproduce i flussi che le piattaforme forniscono ai propri player e rimanda all'app ufficiale quando servono accesso o abbonamento. La disponibilità dei titoli dipende dai diritti delle piattaforme e cambia nel tempo (es. le commedie di Eduardo sono state ritirate da RaiPlay a fine 2022 per la scadenza dei diritti web).

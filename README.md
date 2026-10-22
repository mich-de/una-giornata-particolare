# Una Giornata Particolare (Aldo Cazzullo - LA7)

Client Android nativo (Android TV + Mobile) per la fruizione completa di **"Una Giornata Particolare"** di Aldo Cazzullo trasmesso su LA7.

## Caratteristiche

- **Catalogo Completo**: Tutte le puntate intere disponibili su RivediLa7 (oltre 65 puntate storiche integrali).
- **Video High Definition**: Risoluzione 720p HD nativa estratta via Kaltura Video Platform (`kdam.iltrovatore.it` / `awsvodpkg.iltrovatore.it`).
- **Player Avanzato Media3 / ExoPlayer**:
  - Supporto HLS (.m3u8) e fallback diretto MP4 ad alto bitrate.
  - Comandi avanzati TV/telecomando: Play, Pausa, Salto -10s, Avanti +10s, Seekbar e durata oraria.
  - Gestione ripresa della riproduzione ("Riprendi da dove eri rimasto").
- **Design UI/UX Pro Max**:
  - Tema editoriale Dark Slate `#0D1117` con accenti oro storico `#D4AF37`.
  - Schede puntata uniformi a griglia responsive sia per schermi TV che Mobile.
  - Barra di ricerca istantanea per titolo, descrizione o data.
  - Data di trasmissione sempre visibile su ogni puntata.

## Analisi Tecnica Streaming LA7

- **Piattaforma**: Kaltura Video Platform (`partnerId=103`, `subpartnerId=10300`).
- **Risoluzione massima disponibile per puntate broadcast intere**: 1280x720 (720p) a 1350 kbps. I profili 4K UHD non sono erogati da LA7 sui server VOD per i programmi broadcast di rete.
- **Sottotitoli**: L'endpoint Kaltura `caption_captionAsset` non espone tracce VTT/SRT separate per le puntate di archivio; i sottotitoli teletext LA7 (pag. 777) non sono al momento convertiti in sidecar WebVTT sulla CDN VOD.

## Download APK

Scarica l'APK precompilato [app-ugp.apk](app-ugp.apk) o dalla sezione Releases.

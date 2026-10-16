# Design brief

Dashboard Azure DevOps per tablet Android, da disegnare in figcraft (MCP `figcraft`,
vedi `.mcp.json`) e poi portare in Compose.

## Dispositivo

Samsung Galaxy Tab S6 Lite (SM-P610): 2000×1200 px, circa **1333×800 dp** in orizzontale.
Uso principale: appoggiato sulla scrivania, guardato anche da lontano; tocco occasionale.
Sotto 840 dp di larghezza (verticale, telefono) il layout passa a tab.

## Cosa c'è già (v0.1)

- Dashboard a 3 colonne: **Work item assegnati** (aperti, per priorità), **Pull request**
  (da revisionare con il mio voto, mie con i voti dei reviewer), **Attività** (feed con lette/non lette)
- Impostazioni: organizzazione, *Accedi con Microsoft* (Entra ID), PAT sotto *Altre opzioni*,
  intervallo di aggiornamento, schermo sempre acceso
- Notifiche Android: assegnazioni, menzioni (work item e PR), commenti sulle PR seguite,
  richieste di revisione, voti sulle mie PR
- Tema Material 3 chiaro/scuro (colori dinamici da Android 12), colori dei tipi di work item
  come Azure Boards

Screenshot attuali: `./gradlew testDebugUnitTest` → `app/build/outputs/roborazzi/`.

## Funzionalità da aggiungere (scelte il 2026-10-16)

### Contenuti
1. **Sprint del team**: iterazione corrente, i miei item per colonna della board, giorni
   rimanenti, burndown.
2. **Pipeline e build**: stato delle ultime build/release dei repo che uso; notifica quando
   fallisce la build di una mia PR.
3. **PR più ricche**: stato di check e policy, conflitti di merge, thread attivi/risolti,
   "pronta da completare".

### Azioni dal tablet
4. **Votare le PR**: approva, approva con suggerimenti, in attesa dell'autore, rifiuta.
5. **Commentare e rispondere**: thread di PR e commenti dei work item, soprattutto le menzioni.
6. **Aggiornare work item**: stato, assegnazione, priorità.

### Uso sul tablet
7. **Filtri**: progetti, area path, stati da nascondere (es. `Resolved` delle Epic, che il
   processo di Contoso classifica come In Progress).
8. **Notifiche configurabili**: per tipo, orari di silenzio, suono diverso per le menzioni.
9. **Widget home**: contatori e ultime menzioni.

Note tecniche: le azioni richiedono scritture (con Entra basta `user_impersonation`; un PAT
deve avere Code e Work Items in scrittura); le pipeline richiedono anche Build (read).

## Da disegnare

1. **Design system**: token colore chiaro/scuro, tipografia, spaziature; componenti: pannello,
   riga work item, riga PR, badge voto, avatar reviewer, chip stato, riga evento, bottoni azione.
2. **Home** rivista con sprint e pipeline: dove stanno senza togliere spazio ai tre pannelli?
3. **Dettaglio PR** (pannello laterale o foglio): check, policy, conflitti, thread, voto, risposta.
4. **Dettaglio work item**: campi principali, cambio stato/assegnazione/priorità, commenti, risposta.
5. **Sprint**: board con le mie card per colonna, burndown.
6. **Pipeline**: elenco build/release con stato e durata.
7. **Impostazioni**: account, filtri, notifiche.
8. **Widget** in due taglie.
9. Stati: vuoto, caricamento, errore (sessione scaduta), offline.

## Stato del design (2026-10-16)

File: `docs/design/ado-dashboard.figcraft`, anteprime in `docs/design/png/`. Organizzazione e
progetti d'esempio: **Contoso** (nessun riferimento ad aziende reali).

- **Design system**: variabili `Colori` (Chiaro/Scuro, tipi di work item incl. Chore e
  Documentazione, sfondi diff) e `Misure`, stili di testo Roboto + `Codice` (monospazio), icone
  24/20/16 dp, componenti: Chip stato, Contatore, Badge voto, Avatar, Bottone, Checkbox,
  Interruttore, Chip filtro, Campo, Composer, Thread, Banner, Pannello (slot), Riga work item/PR/
  evento/build/backlog/diff/file, Intestazione sezione.
- **Viste** (pagina Home): all'apertura si sceglie cosa vedere: una vista salvata (la *Vista
  globale* è quella di prima, con filtri personalizzabili per pannello) oppure progetto › board.
  Il titolo in alto è il selettore di vista (menu rapido); *Modifica vista* apre una modale con le
  condizioni per pannello (tipo, assegnato a, stato, ordinamento; PR da revisionare/mie/tutte,
  repo, bozze; tipi di attività).
- **Dettagli**: work item in **modale** (campi modificabili a destra, commenti e risposta);
  PR in **scheda a tutto schermo** con voto nella barra e tab Panoramica / File (diff unificato o
  affiancato, commenti in linea, "Visto") / Thread / Commit.
- **Sprint**: solo nelle viste per board (riquadro in alto → backlog). Backlog con padri a
  livello piatto (User Story, Bug, Chore, Documentazione…) e figli a scomparsa; filtri per tipo,
  "I miei / Tutto il team". Niente burndown. La vista globale non ha sprint: i miei item sono già
  nel pannello Work item.
- **Pipeline**: nessuna schermata dedicata. Resta il riquadro in alto con i conteggi; toccandolo,
  o toccando l'evento "Build fallita", si apre il dettaglio della build in modale.
- **Impostazioni**: filtri globali con albero unico *Progetti e aree* (checkbox a tre stati,
  ricerca che mostra i risultati con il loro percorso, seleziona/deseleziona tutto o solo i
  risultati), stati da nascondere, PR; notifiche. *Account*: ID applicazione e tenant di Entra
  modificabili anche dopo l'accesso (controllo del GUID, nuovo accesso al salvataggio), valori
  da copiare in Entra (pacchetto, hash, redirect URI). *Informazioni*: versione, controllo
  aggiornamenti dai rilasci GitHub con note di rilascio, download con avanzamento ed errori;
  banner "Aggiornamento disponibile" nella dashboard.
- **Widget** 3×1 e 4×2, **stati** vuoto,
  caricamento, sessione scaduta, offline.

- **Telefono** (solo esplorazione): pannelli a tab, dettagli a tutta pagina, voto in basso.

Da decidere: aspetto di una vista per board oltre al riquadro sprint (oggi uguale alla globale
ma filtrata); riquadri durante il caricamento.

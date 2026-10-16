# ado-dashboard

App Android (Kotlin, Jetpack Compose) che mostra una dashboard Azure DevOps su tablet e
manda notifiche. Segue la lingua del dispositivo: i testi dell'app stanno in `strings.xml`,
inglese (predefinito) in `res/values/` e italiano in `res/values-it/`, da aggiornare sempre
insieme. La documentazione in CLAUDE.md resta in italiano; codice e commenti in inglese.

## Build e test

Il JDK non è nel PATH: usare quello in `~/.local/share/jdk`.

```bash
export JAVA_HOME=~/.local/share/jdk/jdk-21.0.12.1+1 PATH=~/.local/share/jdk/jdk-21.0.12.1+1/bin:$PATH
./gradlew testDebugUnitTest   # test JVM + screenshot Roborazzi in app/build/outputs/roborazzi
./gradlew installDebug        # sul tablet collegato via USB
```

Nessun valore di Entra nella build: client ID e tenant ID si inseriscono nelle impostazioni.

Gli screenshot (`DashboardRenderTest`) servono a vedere la UI senza dispositivo: aggiornarli
quando cambia una schermata e guardarli prima di dire che una modifica visiva è fatta.

## Dispositivo

Tablet da ~1333×800 dp; seriale e comandi adb in `.claude/dispositivo.md`.

## Struttura

- `api/`: client REST Azure DevOps (`AdoApi` è l'interfaccia, `AdoClient` l'implementazione)
- `auth/`: login Microsoft con MSAL (config generata a runtime dall'hash della firma)
- `data/`: impostazioni (DataStore), eventi (Room), cifratura del PAT
- `sync/`: `SyncEngine` legge, `Events` calcola gli eventi (logica pura, testata),
  `SyncRepository` salva e notifica, `SyncWorker` in background
- `ui/`: schermate stateless (`DashboardScreen`, `SettingsScreen`) e `AppViewModel`
- `update/`: `Releases` (scelta della release e semver, logica pura, testata),
  `GitHubReleases` (API pubblica senza token), `Updater` (stato, notifica, installazione)

`local.properties` (non versionato) contiene `sdk.dir` ed eventualmente `signing.*`.

## Rilasci

Tag `release/X.Y.Z` → `.github/workflows/release.yml`. Istruzioni in
`.claude/rilasci.md`, fuori dal repo.

## Design

Il design si fa in figcraft tramite il suo MCP (`.mcp.json`, si collega all'app aperta con
`figcraft --control 7979`). Brief e funzionalità da fare: `docs/design-brief.md`.

# NuGet Auto-Fill — Rider Plugin

JetBrains Rider plugin that automatically detects NuGet credential dialogs and fills
them from securely stored credentials (system keychain via PasswordSafe API).

## Commands

```bash
./gradlew build          # Full build + tests
./gradlew test           # Run unit tests only
./gradlew runIde         # Launch Rider sandbox with plugin loaded
./gradlew buildPlugin    # Build distributable ZIP
./gradlew verifyPlugin   # Verify compatibility against multiple Rider versions
```

## Architecture

```
src/main/kotlin/com/tt4/rider/nuget/
├── NuGetCredentialsStore.kt       # State persistence + PasswordSafe integration
├── NuGetDialogInterceptor.kt      # AWT window listener + dialog filling logic
├── NuGetStartupListener.kt        # App lifecycle: wires up interceptor on activation
├── actions/
│   └── NuGetCredentialsAction.kt  # "Tools → Manage NuGet Credentials" menu item
└── ui/
    ├── CredentialsManagerDialog.kt  # Main CRUD table dialog
    └── FeedCredentialsDialog.kt     # Add/Edit feed dialog with Quick Setup templates
```

## Key Patterns

### Credential Storage (Two-Layer)
- **Passwords** → IntelliJ `PasswordSafe` API (system keychain)
- **Non-sensitive config** (URL, username, enabled, autoSubmit) → XML via `PersistentStateComponent`
- **Crash recovery**: backup file `nuget-credentials-backup.xml` written on every change;
  restored automatically if main state file is empty on startup

### Dialog Detection
- AWT `AWTEventListener` for `WindowEvent.WINDOW_OPENED`
- Case-insensitive title matching against known patterns ("Enter Credentials", "Sign In", etc.)
- Regex URL extraction from dialog label text (must end in `/index.json`)
- Component tree traversal (up to 2 levels) to find `JTextField` / `JPasswordField`

### Testable Pure Functions (no IntelliJ runtime needed)
- `NuGetCredentialsStore.normalizeUrl()` — lowercase, trim, scheme, trailing slash
- `NuGetCredentialsStore.computeNewState()` — crash-recovery guard logic
- `NuGetDialogInterceptor.matchesCredentialTitle()` — title pattern matching
- `NuGetDialogInterceptor.URL_PATTERN` — regex constant
- `FeedCredentialsDialog.isValidUrl()`, `buildAzureDevOpsUrl()`, `buildGitHubPackagesUrl()`

## Testing

JUnit 5 + MockK. Tests cover pure business logic only (no IntelliJ runtime required):

| Test file | What it covers |
|-----------|----------------|
| `NuGetCredentialsStoreTest` | URL normalization, crash-recovery state guard |
| `NuGetDialogInterceptorTest` | Title matching, URL regex extraction |
| `UrlValidationTest` | Feed URL validation |
| `PresetUrlGenerationTest` | Azure DevOps / GitHub Packages URL builders |

## Gotchas

- **`useInstaller = false` is required for Rider** — `select {}` in `pluginVerification` always
  defaults to `true`, which is unsupported (GitHub issue #1852). The build uses `create()`
  with dynamically fetched versions from the JetBrains releases API instead.
- **`verifyPlugin` makes HTTP calls at configuration time** — it queries
  `data.services.jetbrains.com/products/releases` to resolve Rider versions. Falls back
  gracefully to an empty list on network errors (CI without internet).
- **EAP versions need a build number** (e.g. `261.20869.54`), not the version string
  (`2026.1-EAP4`). The EAP is also validated via HTTP HEAD against the Maven snapshots repo
  before being included.
- **XML state files** live in the IDE config directory (`PathManager.getConfigPath()`),
  not in the project folder — safe to leave on disk.

## Versions

| Component | Version |
|-----------|---------|
| Plugin | 1.0.4 |
| Kotlin | 2.1.0 |
| IntelliJ Platform Gradle Plugin | 2.11.0 |
| Target Rider | 2024.3 (build 241+, no upper bound) |
| JVM | 21 |

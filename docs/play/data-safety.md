# Data safety form — answers (truthful, verifiable from the dependency list)

**Does your app collect or share any of the required user data types?** → **No.**

Rationale the form may ask you to confirm:

- **Collection**: none. Conversations, downloaded models, embeddings, and
  preferences live in app-private storage (Room/SharedPreferences/files) on the
  device. Nothing is transmitted to the developer — there is no backend.
- **Sharing**: none. No analytics, ads, or crash-reporting SDKs (verify:
  `gradle/libs.versions.toml` — Compose, Room, OkHttp, ML Kit [full flavor
  only, on-device], a markdown renderer).
- **Network traffic exists only as user actions**: (1) model downloads the user
  taps (Hugging Face URLs), (2) requests to cloud AI providers the user has
  configured with their own API key — chat content goes to that provider, by
  explicit per-use choice, under that provider's policy. The app marks every
  cloud provider as a "watched object" in the UI.
- **API keys**: encrypted at rest with Android Keystore (AES-GCM,
  `security/KeystoreSecret.kt`), sent only to the provider they belong to.
- Data deletion: uninstall removes everything (no account, no server copy).

Ephemeral processing note (if asked about "data sent off device"): chat text
sent to a user-configured cloud provider is user-initiated functionality, not
developer collection; declare per current form guidance if the form's
definitions require it.

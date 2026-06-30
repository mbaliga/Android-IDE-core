# Aarso privacy policy (draft)

_Last updated: 2026-06-12_

> **Hosting (owner decision): publish at `https://mdhv.xyz/aarso/privacy`** — no
> new domain, a path under the existing `mdhv.xyz`. Paste this text there and use
> that URL as the Play Console "Privacy policy URL".

Aarso is a local-first AI app. **We do not collect, store, or share any of your
data.** There is no Aarso server and no analytics.

- **Conversations and models stay on your device**, in app-private storage.
  Uninstalling the app deletes them.
- **Model downloads**: when you tap download, the file is fetched directly from
  the source you chose (e.g., Hugging Face). That host sees a normal HTTP
  request from your device.
- **Cloud providers (optional)**: if you add a provider (e.g., Anthropic,
  OpenAI-compatible, Gemini) with your own API key, the messages you explicitly
  send with that provider selected go to that provider under their privacy
  policy. Aarso marks every cloud provider as "watched" in the UI and never
  routes to the cloud by default.
- **API keys** are encrypted at rest with Android Keystore and are sent only to
  the provider they belong to.
- **Reports you send**: flagging an output prepares an email/share that you
  send yourself; nothing is transmitted automatically.

Contact: _owner email — fill in when hosting_.

**Hosting (owner decision, required by Play):** this repo is private, so the
policy needs a public URL — a one-page public repo with GitHub Pages, or any
owned domain.

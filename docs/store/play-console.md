# Fonebrew — Play Console answer sheet

> Only the **deltas** from `Personal-Tracker/store/HOUSE_DEFAULTS.md`. Listing text
> lives in `fastlane/metadata/android/en-US/`.

| | |
|---|---|
| applicationId | `dev.aarso` (**`play` flavor**) · `dev.aarso.full` is sideload-only |
| Version at time of writing | `0.13.0` (versionCode `17`) |
| Category | **Tools** |
| Tags | ai, local ai, llm, on device, offline, privacy, developer tools |
| Contact email | `fonebrew@asystemofcells.com` |
| Website | `https://fonebrew.app` |
| Privacy policy | `https://fonebrew.app/privacy` |

> `fonebrew.dev` is the developer-facing surface (loop format, SDK, docs). Play gets
> `fonebrew.app`. Do not put the `.dev` URL in the listing.

## The three things to settle before the first submission

1. **Ship the `play` flavor, never `full`.** This is the whole policy story. The
   `full` flavor carries `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`
   and `REQUEST_INSTALL_PACKAGES`, any of which turns an easy review into a hard
   one, and `REQUEST_INSTALL_PACKAGES` on a Play build is a likely rejection.
   `core-engine/src/full/AndroidManifest.xml` holds them; `play` has no flavor
   manifest at all, so it inherits only the four benign permissions in
   `src/main`. **Build `:app:bundlePlayRelease`.**
2. **The applicationId is `dev.aarso`, and it is permanent after the first
   production release.** The rename to a `fonebrew` namespace is deferred to
   "Sprint R" (`NAMES.md`). Deferring past the first production upload means never.
   Decide now, not after.
3. **The launcher label is still "Aarso".** `core-engine/src/main/res/values/strings.xml`
   has `<string name="app_name">Aarso</string>`, but the store title is "Fonebrew".
   Play does not require them to match, but a user who installs "Fonebrew" and finds
   "Aarso" on their home screen will file that as a bug. Change the string, or
   decide deliberately not to.

## Deltas from the house defaults

### Privacy policy hosting — a real blocker
This repo is **private** (`Personal-Tracker/STATE.md` D-V, confirmed 2026-08-07).
The policy therefore **cannot** be a link into this repo; Play requires a URL a
logged-out stranger can read. Publish `docs/store/privacy-policy.md` at
`https://fonebrew.app/privacy` before submitting.

### Data safety
**No data collected. No data shared.**

| Question | Answer |
|---|---|
| Collect or share any user data? | **No** |
| Encrypted in transit? | Yes |
| Deletion? | Users can delete data in the app |

The reasoning, which must stay true because it is a binding product rule (CLAUDE.md
rule 1, "no telemetry, analytics, or phoning home, ever"):

- Conversations, models and settings are in app-private storage. Nothing is
  transmitted to us; there is no server of ours.
- **Model downloads** fetch a file from the host the user picked (for example
  Hugging Face). That host sees a normal HTTP request from the device. This is a
  download, not a data transfer about the user, and is not declarable collection.
- **Cloud providers are the one user-directed transfer.** With the user's own key,
  the messages they explicitly send go to the provider they chose, under that
  provider's policy. We never see them and run no proxy. Disclosed in the privacy
  policy; not "collected" by this app under Google's definition.
- **API keys** are Keystore-encrypted at rest and sent only to their own provider.

### Permissions — the `play` flavor only
| Permission | Why | Play form? |
|---|---|---|
| `INTERNET` | Model downloads and, if configured, the user's own cloud provider. | No |
| `ACCESS_NETWORK_STATE` | Avoid starting a download while offline. | No |
| `FOREGROUND_SERVICE` | Generation continues while the screen is off. | No |
| `FOREGROUND_SERVICE_DATA_SYNC` | The declared type for that service. Needs a stated justification since Android 14: long-running local model inference and model downloads that must survive backgrounding. | Justification text only |

Not present in `play`, and must stay that way: `SYSTEM_ALERT_WINDOW`,
`FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`,
`REQUEST_INSTALL_PACKAGES`. Verify before every upload:

```sh
./gradlew :app:assemblePlayRelease
aapt2 dump permissions app/build/outputs/apk/play/release/*.apk
```

### Content rating
- Category `Utility, Productivity, Communication, or Other`.
- **"Does the app allow users to interact or exchange content with other users?"**
  → **No.** There is no social surface. The council is several models in one local
  conversation, not other people.
- **"Unrestricted internet browsing"** → **No.** There is no web browser.
- **"Does the app contain user-generated content?"** → **No** in the store sense:
  content is generated locally and never shared to any service of ours.
- Everything else No. Expected **Everyone**.

> A model can of course produce anything a model can produce. Play's questionnaire
> asks about content the *app* ships or distributes, not about what a general-purpose
> tool could be made to output. Answer the questions as asked, and keep the listing
> copy free of content claims either way (this is a standing rule in `CLAUDE.md`).

### Copy rules that are binding here, not stylistic
- **Never call the council feature "MoE" or "Mixture of Experts"** anywhere in the
  listing, screenshots or store copy. It is a *council*. This is binding rule 3.
- The **Me / Myself / I** drift surface ships **inert** and is blocked on Issue #2.
  It is not in the listing copy, and must not be added until that unblocks.
- Never state on-device behaviour as verified. The build environment has no device,
  so every runtime claim in the listing is owner-verified or it does not ship.

### Monetisation
- **No in-app purchases in this app, today.** The paid Studio layer lives in
  `Android-IDE-Studio` and currently shares this applicationId, which means it
  **cannot have its own listing** until the de-fork lands. When it does, it is
  either a separate app with its own applicationId, or an IAP inside this one.
  That is an owner decision and it changes this section.

## Pre-submit checklist

- [ ] Building `:app:bundlePlayRelease`, not `full`.
- [ ] Permission dump verified (command above).
- [ ] Privacy page live at `https://fonebrew.app/privacy`, readable logged out.
- [ ] `app_name` string decided: "Aarso" or "Fonebrew".
- [ ] applicationId decided as permanent.
- [ ] Icon 512x512 and feature graphic 1024x500 exported from the adaptive icon.
- [ ] 4 screenshots from a **real device** (no emulator exists here): first-run
      setup, a chat with markdown and the instruments strip open, the Tree map,
      the Models catalogue.

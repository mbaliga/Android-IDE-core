# Generative AI declaration notes (play flavor)

> **Scope (FAQ):** Play's GenAI declaration is about the app's **function** —
> Aarso uses generative AI to produce content for users (text + images) — and the
> **safeguards** shipped (the in-app output reporting below). It is **NOT** a
> disclosure that the app's source code was written with AI. Google requires no
> such "AI-authored code" declaration. Answer the questionnaire as "uses GenAI as
> a core function," not "coded by AI."

**What the app is**: a local-first client for open-weight text models (GGUF via
llama.cpp) and user-configured cloud providers; also on-device Stable Diffusion
image generation.

**Play catalog**: official instruct releases only (Qwen 2.5/3 instruct,
Gemma 3 IT, Llama 3.1 Instruct, Phi-4) — standard safety-tuned models. No
policy-restricted fine-tunes are listed or marketed in the Play build.
Users may import any GGUF by URL — user-supplied content, like any file the
user loads into a viewer.

**In-app reporting (policy requirement)**: long-press any model output → "Flag
this output…" → category dialog → the app prepares a report (category, model
id, the output) that **the user sends** — `mailto:` when a reporting address is
configured (`InvocationFeatures.FLAG_REPORT_EMAIL` — OWNER: set before
listing), else the system share sheet. The app never transmits anything itself:
the project's binding rule is zero telemetry, so reporting is user-initiated by
design. Reports that arrive inform catalog curation (e.g., delisting a model).

**Honest residual risk**: a reviewer may read "in-app reporting" as requiring
automated transmission. If rejected on that ground, the fallback is a hosted
report form opened from the same dialog (still user-initiated; never silent
telemetry). On-device generation also cannot enforce server-side output
filtering; mitigations are the safety-tuned catalog + reporting + content
rating. Comparable local-model apps are on Play.

package dev.aarso.domain.guide

/**
 * Guided walk-throughs (owner ask): a small help affordance on every setup that explains,
 * step by step, exactly what to do — "open this, tap that." Pure content + lookup so it's
 * JVM-tested and lives apart from the UI that renders it. Steps describe **our own flows**
 * only (never invented external facts — binding rule 8).
 */
data class GuideStep(
    val title: String,
    val body: String,
    /** An optional concrete pointer, e.g. "Settings → Git & coding". */
    val hint: String? = null,
)

data class Guide(val id: String, val title: String, val steps: List<GuideStep>)

object Guides {

    val CONNECT_GIT = Guide(
        "connect_git", "Connect your Git host",
        listOf(
            GuideStep("Open the form", "Everything below lives in one place.", "Settings → Git & coding"),
            GuideStep("Pick a host", "Choose GitHub or a Gitea server. For Gitea, enter its base URL."),
            GuideStep("Make a token", "On your host, create a personal access token with repo scope. It is encrypted on-device and only ever sent to this host."),
            GuideStep("Fill owner / repo / branch", "This is the repository your loops, backups, and issues sync with."),
            GuideStep("Test, then Save", "Tap Test to confirm the token works, then Save. A green result means you're connected.", "Test button"),
        ),
    )

    val ADD_CLOUD = Guide(
        "add_cloud", "Add a cloud model (watched)",
        listOf(
            GuideStep("On-device is the default", "You don't need cloud at all — local models run free and private. Add cloud only if you want it."),
            GuideStep("Open Text providers", "Cloud chat providers are listed here, each marked a watched object.", "Settings → Text"),
            GuideStep("Choose a provider kind", "Anthropic, OpenAI-compatible, or Gemini. OpenAI-compatible covers most vendors (DeepSeek, Together, Groq, …)."),
            GuideStep("Paste your API key", "Stored encrypted via the Android Keystore; sent only to that provider, never logged, never to us."),
            GuideStep("Save", "It now appears in the model switcher with a watched badge."),
        ),
    )

    val CONNECT_SSH = Guide(
        "connect_ssh", "Connect to a machine over SSH",
        listOf(
            GuideStep("Open Remote", "Your machines live here — a Pi, a server, a desktop.", "Settings → Remote"),
            GuideStep("Add a host", "Enter an alias, hostname/IP, port (22), and username."),
            GuideStep("Choose auth", "Paste a private key (PEM) or a password. It's encrypted on-device."),
            GuideStep("Connect & verify trust", "On first connect the server's fingerprint is shown — check it matches your machine, then Accept. No secret is sent before you do."),
            GuideStep("Run or open a shell", "Run a one-off command, or Open shell for an interactive terminal."),
        ),
    )

    val SET_PRICING = Guide(
        "set_pricing", "Set cloud pricing",
        listOf(
            GuideStep("Open the Cost facet", "Where you set what cloud turns cost you.", "Dev tools → Cost"),
            GuideStep("Enter per-1k rates", "Input and output price per 1,000 tokens, in whatever unit you choose. These are your numbers — we never bake in a vendor's."),
            GuideStep("Save", "Each cloud reply now shows its cost inline; on-device replies stay free."),
        ),
    )

    val RUN_LOOP = Guide(
        "run_loop", "Run your first loop",
        listOf(
            GuideStep("Open Loops", "Pinch out (zoom in) on the thread to descend into Loops."),
            GuideStep("Write an objective", "Say what you want. Pick a proposer and a critic model."),
            GuideStep("Run", "The loop proposes, critiques, and refines. The whole run is saved into your tree."),
            GuideStep("Sync (optional)", "Push the loop to your Git host as a .bpmn file from the Loops dialog."),
        ),
    )

    val all = listOf(CONNECT_GIT, ADD_CLOUD, CONNECT_SSH, SET_PRICING, RUN_LOOP)

    fun byId(id: String): Guide? = all.firstOrNull { it.id == id }
}

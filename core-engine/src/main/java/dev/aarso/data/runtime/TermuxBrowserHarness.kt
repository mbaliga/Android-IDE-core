package dev.aarso.data.runtime

import java.net.URI

data class BrowserCaptureResult(
    val command: TermuxCommandResult,
    val screenshotPath: String,
    val domPath: String,
)

/**
 * Small deterministic Selenium harness over the Termux runtime.
 *
 * It captures artifacts into the execution workspace. Assay (or another caller) decides whether
 * those artifacts satisfy a test/proof contract.
 */
class TermuxBrowserHarness(
    private val bridge: TermuxRunCommandBridge,
) {
    suspend fun capture(
        url: String,
        outputDirectory: String,
        width: Int = 1280,
        height: Int = 800,
    ): BrowserCaptureResult {
        val parsed = URI(url)
        require(parsed.scheme in setOf("http", "https")) { "Browser capture supports http/https URLs only." }
        require(width in 320..4096 && height in 240..4096) { "Viewport is outside supported bounds." }

        val screenshot = outputDirectory.trimEnd('/') + "/screenshot.png"
        val dom = outputDirectory.trimEnd('/') + "/page.html"

        val mkdir = bridge.run(
            executable = "\$PREFIX/bin/mkdir",
            args = listOf("-p", outputDirectory),
            timeoutMs = 30_000,
        )
        check(mkdir.exitCode == 0) { "Unable to create browser artifact directory." }

        val script = """
import shutil
import sys
from pathlib import Path
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service

url, screenshot_path, dom_path, width, height = sys.argv[1:]
chromium = shutil.which("chromium")
driver_bin = shutil.which("chromedriver")
if not chromium or not driver_bin:
    raise RuntimeError("chromium/chromedriver not found")

opts = Options()
opts.binary_location = chromium
opts.add_argument("--headless=new")
opts.add_argument("--no-sandbox")
opts.add_argument("--disable-dev-shm-usage")
opts.add_argument("--disable-gpu")
opts.add_argument(f"--window-size={width},{height}")

driver = webdriver.Chrome(service=Service(driver_bin), options=opts)
try:
    driver.get(url)
    Path(dom_path).write_text(driver.page_source, encoding="utf-8")
    if not driver.save_screenshot(screenshot_path):
        raise RuntimeError("screenshot capture failed")
    print(driver.title)
finally:
    driver.quit()
""".trimIndent()

        val result = bridge.run(
            executable = "\$PREFIX/bin/python",
            args = listOf("-c", script, url, screenshot, dom, width.toString(), height.toString()),
            workDir = outputDirectory,
            timeoutMs = 120_000,
        )
        return BrowserCaptureResult(result, screenshot, dom)
    }

    suspend fun runSeleniumPython(
        script: String,
        workDirectory: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 120_000,
    ): TermuxCommandResult {
        require(script.length <= 120_000) { "Selenium script is too large for the command-intent channel." }
        return bridge.run(
            executable = "\$PREFIX/bin/python",
            args = listOf("-c", script) + args,
            workDir = workDirectory,
            timeoutMs = timeoutMs,
        )
    }
}

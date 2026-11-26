package dev.fonebrew.domain.gitcore.client

import dev.fonebrew.data.gitcore.GitTransports
import dev.fonebrew.domain.gitcore.GitTestSupport
import dev.fonebrew.domain.gitcore.JvmFileSystem
import dev.fonebrew.domain.gitcore.client.ClientEnv.assertNoRepoAt
import dev.fonebrew.domain.gitcore.client.ClientEnv.client
import dev.fonebrew.domain.gitcore.client.ClientEnv.clone
import dev.fonebrew.domain.gitcore.client.ClientEnv.failure
import dev.fonebrew.domain.gitcore.client.ClientEnv.seed
import dev.fonebrew.domain.gitcore.transport.Credential
import dev.fonebrew.domain.gitcore.transport.CredentialProvider
import dev.fonebrew.domain.gitcore.transport.CredentialRequest
import dev.fonebrew.domain.gitcore.transport.GitHttpFixture
import dev.fonebrew.domain.gitcore.transport.GitHttpFixture.Answer
import dev.fonebrew.domain.gitcore.transport.HttpReq
import dev.fonebrew.domain.gitcore.transport.HttpResp
import dev.fonebrew.domain.gitcore.transport.MiniHttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Credentials, redirects, hostile HTTP behaviour and cancellation, against real git and small scripted HTTP servers. */
class AuthRedirectCancelTest {
    private lateinit var fx: GitHttpFixture
    private lateinit var work: File
    private val servers = ArrayList<AutoCloseable>()
    private fun dest() = File(work, "c")
    private val SECRET = "SEKRET-TOKEN-9f3a"

    @Before fun setUp() {
        assumeTrue("git http-backend is not available in this environment", ClientEnv.available)
        fx = GitHttpFixture(); work = GitTestSupport.tempDir("auth")
    }
    @After fun tearDown() { if (::fx.isInitialized) fx.close(); servers.forEach { try { it.close() } catch (_: Exception) {} } }

    /** Everything a failure could print: message, failure toString, cause chain, stack trace. */
    private fun everything(e: Throwable): String {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        return (e.message ?: "") + sw + ((e as? GitClientException)?.failure?.toString() ?: "") + ((e as? GitClientException)?.failure?.explanation ?: "")
    }

    private fun mini(handler: (HttpReq) -> HttpResp): MiniHttpServer = MiniHttpServer(handler = handler).also { servers.add(it) }

    // ---------------------------------------------------------------- authentication

    @Test fun credentialsAreAskedForOnlyAfterA401AndOnlyOnceThenReused() {
        seed(fx, "a.git"); fx.requireAuth = "alice" to SECRET
        var asked = 0
        val provider = CredentialProvider { asked++; Credential("alice", SECRET) }
        clone(client(provider), fx.url("a.git"), dest())
        assertEquals(1, asked)
        assertNull("first request must be anonymous", fx.log.first().authorization)
        assertTrue(fx.log.drop(1).all { it.authorization != null })
        assertTrue(File(dest(), "f1.txt").exists())
        // the URL stored in .git/config carries no credentials
        assertFalse(File(dest(), ".git/config").readText().contains(SECRET))
        GitTestSupport.git(dest(), "fsck", "--full")
    }

    @Test fun missingWrongAndRightCredentialsGiveDistinctTypedFailures() {
        seed(fx, "a.git"); fx.requireAuth = "alice" to SECRET
        val none = failure { clone(client(), fx.url("a.git"), dest()) }
        assertTrue(none is GitFailure.AuthRequired)
        assertNoRepoAt(work, dest())
        var asked = 0
        val wrong = failure { clone(client(CredentialProvider { asked++; Credential("alice", "not-the-token") }), fx.url("a.git"), dest()) }
        assertTrue(wrong is GitFailure.AuthFailed); assertEquals(1, asked)   // does not hammer the server
        assertNoRepoAt(work, dest())
        assertTrue(fx.log.count { it.authorization != null } <= 1)
        clone(client(ClientEnv.creds("alice", SECRET)), fx.url("a.git"), dest())
    }

    @Test fun noCredentialOrTokenCanReachAnyMessageToStringOrStackTrace() {
        seed(fx, "a.git"); fx.requireAuth = "alice" to SECRET
        val wrong = CredentialProvider { Credential("alice", SECRET + "-wrong") }
        val collected = StringBuilder()
        for (c in listOf(client(), client(wrong))) try { clone(c, fx.url("a.git"), dest()) } catch (e: Throwable) { collected.append(everything(e)) }
        val events = ArrayList<Any>()
        runBlocking { try { client(ClientEnv.creds("alice", SECRET)).clone(fx.url("a.git"), dest().absolutePath).collect { events.add(it) } } catch (_: Throwable) {} }
        events.forEach { collected.append(it.toString()) }
        collected.append(Credential("alice", SECRET)).append(CredentialRequest("https", "h", 443, "alice"))
        assertFalse(collected.contains(SECRET)); assertFalse(collected.contains("not-the-token"))
        assertFalse(collected.contains("YWxpY2U6")) // base64("alice:") - the Authorization header value
        // a push over an authenticated connection leaks nothing either
        val d = File(work, "p"); clone(client(ClientEnv.creds("alice", SECRET)), fx.url("a.git"), d)
        ClientEnv.localCommit(d, "x.txt", "x")
        val r = runBlocking { client(ClientEnv.creds("alice", SECRET)).push(d.absolutePath, specs = listOf(PushSpec.branch("main"))) }
        assertFalse(r.toString().contains(SECRET))
    }

    @Test fun aServerThatOnlyOffersABearerChallengeNeverGetsTheProviderAsked() {
        var asked = 0
        val s = mini { HttpResp(401, listOf("WWW-Authenticate" to "Bearer realm=x")) }
        val f = failure { clone(client(CredentialProvider { asked++; Credential("a", SECRET) }), "http://127.0.0.1:${s.port}/r.git", dest()) }
        assertTrue(f is GitFailure.AuthRequired); assertEquals(0, asked)
    }

    // ---------------------------------------------------------------- redirects

    @Test fun credentialsDoNotFollowARedirectToAnotherHostAndThatHostIsAskedByItsOwnName() {
        seed(fx, "r.git")
        val b = GitHttpFixture(fx.root, bindHost = "127.0.0.1").also { servers.add(it) }  // serves the same repos, reached as "localhost"
        b.requireAuth = "bob" to "bobs-password"
        fx.requireAuth = "alice" to SECRET
        fx.redirectTo = "http://localhost:${b.port}"
        val asked = ArrayList<String>()
        // Provider holds a credential for the ORIGIN host only.
        val onlyA = CredentialProvider { asked.add(it.host); if (it.host == "127.0.0.1") Credential("alice", SECRET) else null }
        val f = failure { clone(client(onlyA), fx.url("r.git"), dest()) }
        assertTrue(f.toString(), f is GitFailure.AuthRequired)
        assertEquals(listOf("127.0.0.1", "localhost"), asked)
        assertTrue("origin saw the credential, so the test is meaningful", fx.log.any { it.authorization != null })
        assertTrue("the other host must never see the first host's credential", b.log.isNotEmpty() && b.log.all { it.authorization == null })
        assertFalse(b.log.any { (it.authorization ?: "").contains("YWxpY2U6") })
        assertNoRepoAt(work, dest())
    }

    @Test fun aCrossHostRedirectWorksWhenEachHostHasItsOwnCredentialAndLaterRequestsGoToTheNewHost() {
        seed(fx, "r.git")
        val b = GitHttpFixture(fx.root).also { servers.add(it) }
        b.requireAuth = "bob" to "bobs-password"; fx.requireAuth = "alice" to SECRET
        fx.redirectTo = "http://localhost:${b.port}"
        val perHost = CredentialProvider { if (it.host == "localhost") Credential("bob", "bobs-password") else Credential("alice", SECRET) }
        clone(client(perHost), fx.url("r.git"), dest())
        assertTrue(File(dest(), "f1.txt").exists())
        assertTrue("data requests must go to the redirected-to host", fx.log.none { it.method == "POST" })
        assertTrue(b.log.any { it.method == "POST" })
    }

    @Test fun redirectsToNonInfoRefsTooManyRedirectsAndPostRedirectsAreRefused() {
        val other = mini { HttpResp(200) }
        val toElsewhere = mini { HttpResp.redirect("http://127.0.0.1:${other.port}/somewhere/else") }
        assertTrue(failure { clone(client(), "http://127.0.0.1:${toElsewhere.port}/r.git", dest()) } is GitFailure.RedirectRefused)
        lateinit var loop: MiniHttpServer
        loop = mini { r -> HttpResp.redirect("http://127.0.0.1:${loop.port}${r.path}?${r.query}") }
        assertTrue(failure { clone(client(), "http://127.0.0.1:${loop.port}/r.git", dest()) } is GitFailure.RedirectRefused)
        // a data (POST) request that is redirected is refused outright
        seed(fx, "r.git")
        fx.tamper = { m, _, _, _ -> if (m == "POST") Answer(302, "text/plain", ByteArray(0)) else null }
        val f = failure { clone(client(), fx.url("r.git"), dest()) }
        assertTrue(f.toString(), f is GitFailure.RedirectRefused || f is GitFailure.ProtocolError || f is GitFailure.Network)
        assertNoRepoAt(work, dest())
    }

    private fun trustAll() = object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test fun anHttpsToHttpDowngradeRedirectIsRefusedAndNoCredentialIsSentOverHttp() {
        val ks = File(GitTestSupport.tempDir("ks"), "ks.p12")
        val ok = try {
            ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-storetype", "PKCS12", "-keystore", ks.absolutePath, "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
        } catch (_: Exception) { false }
        assumeTrue("keytool not available to make a throwaway TLS certificate", ok)
        val plain = mini { HttpResp(200) }
        val store = KeyStore.getInstance("PKCS12").also { k -> ks.inputStream().use { k.load(it, "changeit".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also { it.init(store, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").also { it.init(kmf.keyManagers, null, SecureRandom()) }
        val https = MiniHttpServer(ssl = ctx) { HttpResp.redirect("http://127.0.0.1:${plain.port}/r.git/info/refs?service=git-upload-pack") }
        servers.add(https)
        val clientCtx = SSLContext.getInstance("TLS").also { it.init(null, arrayOf(trustAll()), SecureRandom()) }
        val okHttp = GitTransports(CredentialProvider { Credential("u", SECRET) }, httpClient = okhttp3.OkHttpClient.Builder().sslSocketFactory(clientCtx.socketFactory, trustAll()).hostnameVerifier { _, _ -> true }.build())
        val c = GitClient(JvmFileSystem(), okHttp)
        val f = failure { clone(c, "https://127.0.0.1:${https.port}/r.git", dest()) }
        assertTrue(f.toString(), f is GitFailure.RedirectRefused && f.explanation.contains("No sign-in was sent"))
        assertNoRepoAt(work, dest())
    }

    // ---------------------------------------------------------------- misc HTTP behaviour

    @Test fun notFoundForbiddenServerErrorAndDumbHttpAreTyped() {
        assertTrue(failure { clone(client(), fx.url("missing.git"), dest()) } is GitFailure.NotFound)
        val s403 = mini { HttpResp(403) }
        assertTrue(failure { clone(client(), "http://127.0.0.1:${s403.port}/r.git", dest()) } is GitFailure.AuthRequired)
        val s500 = mini { HttpResp(500) }
        assertTrue(failure { clone(client(), "http://127.0.0.1:${s500.port}/r.git", dest()) } is GitFailure.RemoteError)
        val dumb = mini { HttpResp(200, listOf("Content-Type" to "text/plain"), "hello".toByteArray()) }
        assertTrue(failure { clone(client(), "http://127.0.0.1:${dumb.port}/r.git", dest()) } is GitFailure.ProtocolError)
        assertTrue(failure { clone(client(), "http://127.0.0.1:1/r.git", dest()) } is GitFailure.Network)
        assertNoRepoAt(work, dest())
    }

    // ---------------------------------------------------------------- cancellation

    @Test fun cancellingACloneMidTransferLeavesNothingAtTheTargetOrBesideIt() {
        seed(fx, "cx.git")
        val entered = CountDownLatch(1)
        fx.tamper = { m, p, _, proxy -> if (m == "POST" && p.endsWith("git-upload-pack")) { val a = proxy(); entered.countDown(); Thread.sleep(3000); a } else null }
        runBlocking {
            withTimeout(60_000) {
                val job = launch(kotlinx.coroutines.Dispatchers.Default) { client().clone(fx.url("cx.git"), dest().absolutePath).collect { } }
                assertTrue(entered.await(30, TimeUnit.SECONDS))
                job.cancelAndJoin()
            }
        }
        assertNoRepoAt(work, dest())
        // and the very same clone works afterwards (no wedged state)
        fx.tamper = null
        clone(client(), fx.url("cx.git"), dest())
        assertTrue(File(dest(), "f1.txt").exists())
    }

    @Test fun cancellingADuringPackDownloadAlsoCleansUp() {
        seed(fx, "cx2.git", commits = 3)
        val gate = CountDownLatch(1)
        // Serve the real pack but only after the client has started waiting, then cancel while it is parsing.
        fx.tamper = { m, p, _, proxy -> if (m == "POST" && p.endsWith("git-upload-pack")) { gate.countDown(); Thread.sleep(500); proxy() } else null }
        runBlocking {
            withTimeout(60_000) {
                val job = async(kotlinx.coroutines.Dispatchers.Default, CoroutineStart.DEFAULT) { client().clone(fx.url("cx2.git"), dest().absolutePath).collect { } }
                assertTrue(gate.await(30, TimeUnit.SECONDS))
                job.cancelAndJoin()
            }
        }
        assertNoRepoAt(work, dest())
    }
}

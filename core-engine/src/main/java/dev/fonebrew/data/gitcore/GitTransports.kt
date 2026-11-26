package dev.fonebrew.data.gitcore

import dev.fonebrew.domain.gitcore.client.TransportFactory
import dev.fonebrew.domain.gitcore.transport.CredentialProvider
import dev.fonebrew.domain.gitcore.transport.GitTransport
import dev.fonebrew.domain.gitcore.transport.HostKeyPolicy
import dev.fonebrew.domain.gitcore.transport.RemoteUrl
import dev.fonebrew.domain.gitcore.transport.SshKeyProvider
import dev.fonebrew.domain.remote.KnownHosts
import okhttp3.OkHttpClient

/** The production [TransportFactory]: https/http on OkHttp, ssh on sshj, ports for every secret. */
class GitTransports(
    private val credentials: CredentialProvider,
    private val sshKeys: SshKeyProvider = SshKeyProvider.NONE,
    private val knownHosts: () -> KnownHosts = { KnownHosts() },
    private val hostKeyPolicy: HostKeyPolicy = HostKeyPolicy.vettedOnly(knownHosts),
    private val httpClient: OkHttpClient = OkHttpGitTransport.defaultClient(),
) : TransportFactory {
    override fun create(url: RemoteUrl): GitTransport = when (url) {
        is RemoteUrl.Http -> OkHttpGitTransport(url, credentials, httpClient)
        is RemoteUrl.Ssh -> SshGitTransport(url, sshKeys, hostKeyPolicy, knownHosts)
    }
}

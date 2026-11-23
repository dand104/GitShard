package io.gitshard.gitshard.git.jgit

import io.gitshard.gitshard.git.GitCredentials
import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

internal object JGitAuth {

    fun toCredentialsProvider(credentials: GitCredentials): CredentialsProvider? = when (credentials) {
        GitCredentials.None -> null
        is GitCredentials.HttpsToken -> UsernamePasswordCredentialsProvider(
            credentials.username,
            credentials.token,
        )
        is GitCredentials.HttpsPassword -> UsernamePasswordCredentialsProvider(
            credentials.username,
            credentials.password,
        )
        is GitCredentials.SshKey -> throw UnsupportedOperationException(
            "SSH transport is not configured.",
        )
    }

    fun <C : TransportCommand<C, *>> C.applyTransportConfig(
        credentials: GitCredentials,
        timeoutSeconds: Int = JGitConfig.NETWORK_TIMEOUT_SECONDS,
    ): C {
        setTimeout(timeoutSeconds)
        toCredentialsProvider(credentials)?.let(::setCredentialsProvider)
        return this
    }
}

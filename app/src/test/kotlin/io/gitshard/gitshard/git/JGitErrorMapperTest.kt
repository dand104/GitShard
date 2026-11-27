package io.gitshard.gitshard.git

import io.gitshard.gitshard.git.jgit.JGitErrorMapper
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException as ApiTransportException
import org.eclipse.jgit.errors.LockFailedException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.errors.NotSupportedException
import org.eclipse.jgit.errors.TransportException as CoreTransportException
import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class JGitErrorMapperTest {

    private val repoUri = URIish("https://example.com/project-401/notes.git")

    @Test
    fun `classifies transient network and lock failures as retryable`() {
        val dns = JGitErrorMapper.toGitException(
            ApiTransportException("fetch failed", CoreTransportException(repoUri, "host", UnknownHostException("example.com"))),
        )
        assertEquals(GitErrorKind.NETWORK, dns.kind)
        assertTrue(dns.kind.isRetryable)

        val timeout = JGitErrorMapper.toGitException(SocketTimeoutException("Read timed out"))
        assertEquals(GitErrorKind.NETWORK, timeout.kind)
        assertTrue(timeout.kind.isRetryable)

        val lock = JGitErrorMapper.toGitException(LockFailedException(File(".git/index.lock")))
        assertEquals(GitErrorKind.LOCK, lock.kind)
        assertTrue(lock.kind.isRetryable)

        val gateway = JGitErrorMapper.toGitException(
            ApiTransportException("503 Service Unavailable", CoreTransportException(repoUri, "503 Service Unavailable")),
        )
        assertEquals(GitErrorKind.NETWORK, gateway.kind)
        assertTrue(gateway.kind.isRetryable)

        val numericHost = URIish("http://403.example.com/status-401/notes.git")
        val resetOnNumericHost = JGitErrorMapper.toGitException(
            ApiTransportException("502 Bad Gateway", CoreTransportException(numericHost, "502 Bad Gateway")),
        )
        assertEquals(GitErrorKind.NETWORK, resetOnNumericHost.kind)
    }

    @Test
    fun `classifies auth config and unknown failures as permanent`() {
        val authLocalized = JGitErrorMapper.toGitException(
            ApiTransportException(JGitText.get().notAuthorized, CoreTransportException(repoUri, JGitText.get().notAuthorized)),
        )
        assertEquals(GitErrorKind.AUTH, authLocalized.kind)
        assertFalse(authLocalized.kind.isRetryable)

        val forbidden = JGitErrorMapper.toGitException(
            ApiTransportException("403 Forbidden", CoreTransportException(repoUri, "403 Forbidden")),
        )
        assertEquals(GitErrorKind.AUTH, forbidden.kind)

        val httpStatus = JGitErrorMapper.toGitException(
            ApiTransportException("HTTP/1.1 401", CoreTransportException(repoUri, "HTTP/1.1 401")),
        )
        assertEquals(GitErrorKind.AUTH, httpStatus.kind)

        val credPrompt = JGitErrorMapper.toGitException(UnsupportedCredentialItem(repoUri, "Password"))
        assertEquals(GitErrorKind.AUTH, credPrompt.kind)

        val missingRemote = JGitErrorMapper.toGitException(
            ApiTransportException("missing", NoRemoteRepositoryException(repoUri, "not found")),
        )
        assertEquals(GitErrorKind.CONFIG, missingRemote.kind)
        assertEquals(GitErrorKind.CONFIG, JGitErrorMapper.classify(InvalidRemoteException("bad remote")))
        assertEquals(GitErrorKind.CONFIG, JGitErrorMapper.classify(NotSupportedException("ssh")))
        assertEquals(GitErrorKind.CONFIG, JGitErrorMapper.classify(UnsupportedOperationException("SSH not configured")))
        assertEquals(GitErrorKind.UNKNOWN, JGitErrorMapper.classify(IllegalStateException("unexpected")))
    }
}

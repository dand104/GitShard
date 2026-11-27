package io.gitshard.gitshard.git.jgit

import io.gitshard.gitshard.git.GitErrorKind
import io.gitshard.gitshard.git.GitException
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException as ApiTransportException
import org.eclipse.jgit.errors.LockFailedException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.errors.NotSupportedException
import org.eclipse.jgit.errors.TransportException as CoreTransportException
import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.internal.JGitText
import java.io.IOException

internal object JGitErrorMapper {

    private val HTTP_AUTH_STATUS_REGEX = Regex(
        """(?i)(?:\b(?:http(?:/\d(?:\.\d)?)?\s+|status(?:\s+code)?\s*[:=]?\s*)(401|403)\b|\b(?:401\s+unauthorized|403\s+forbidden)\b)""",
    )

    fun toGitException(error: Exception): GitException {
        if (error is GitException) return error
        val kind = classify(error)
        val message = generateSequence<Throwable>(error) {
            it.cause
        }.firstNotNullOfOrNull {
            it.message?.trim()?.takeIf(String::isNotEmpty)
        }
            ?: error.javaClass.simpleName
        return GitException(kind = kind, message = message, cause = error)
    }

    fun classify(error: Throwable): GitErrorKind {
        val chain = generateSequence(error) { it.cause }.toList()

        if (chain.any { it is UnsupportedCredentialItem }) {
            return GitErrorKind.AUTH
        }
        if (chain.any {
                it is NoRemoteRepositoryException ||
                    it is InvalidRemoteException ||
                    it is NotSupportedException ||
                    it is UnsupportedOperationException
            }
        ) {
            return GitErrorKind.CONFIG
        }
        if (chain.any { it is LockFailedException }) {
            return GitErrorKind.LOCK
        }

        if (chain.any { it is IOException && !it.javaClass.name.startsWith("org.eclipse.jgit.") }) {
            return GitErrorKind.NETWORK
        }

        if (chain.any { it is ApiTransportException || it is CoreTransportException }) {
            val texts = JGitText.get()
            val isAuth = chain.mapNotNull { it.message }.any { msg ->
                msg.contains(texts.notAuthorized, ignoreCase = true) ||
                    msg.contains(texts.authenticationNotSupported, ignoreCase = true) ||
                    msg.contains("not authorized", ignoreCase = true) ||
                    msg.contains("authentication", ignoreCase = true) ||
                    HTTP_AUTH_STATUS_REGEX.containsMatchIn(msg)
            }
            return if (isAuth) GitErrorKind.AUTH else GitErrorKind.NETWORK
        }

        return GitErrorKind.UNKNOWN
    }
}

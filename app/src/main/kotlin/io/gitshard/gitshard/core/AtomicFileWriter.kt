package io.gitshard.gitshard.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object AtomicFileWriter {

    fun writeText(target: File, content: String, charset: Charset = Charsets.UTF_8) {
        writeBytes(target.toPath(), content.toByteArray(charset))
    }

    fun writeText(target: Path, content: String, charset: Charset = Charsets.UTF_8) {
        writeBytes(target, content.toByteArray(charset))
    }

    fun writeBytes(target: File, bytes: ByteArray) {
        writeBytes(target.toPath(), bytes)
    }

    fun writeBytes(target: Path, bytes: ByteArray) {
        val absoluteTarget = target.toAbsolutePath().normalize()
        val parent = requireNotNull(absoluteTarget.parent) {
            "Target path has no parent directory: $absoluteTarget"
        }
        Files.createDirectories(parent)

        val tempFile = Files.createTempFile(parent, ".gitshard-", ".tmp")
        var moved = false
        try {
            FileChannel.open(
                tempFile,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    channel.write(buffer)
                }
                channel.force(true)
            }

            try {
                Files.move(
                    tempFile,
                    absoluteTarget,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempFile,
                    absoluteTarget,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            moved = true
        } finally {
            if (!moved) {
                runCatching { Files.deleteIfExists(tempFile) }
            }
        }
    }
}

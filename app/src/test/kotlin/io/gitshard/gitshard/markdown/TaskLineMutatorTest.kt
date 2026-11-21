package io.gitshard.gitshard.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLineMutatorTest {

    @Test
    fun `toggles checkbox and preserves CRLF line endings`() {
        val original = "# Plan\r\n- [ ] First\r\n  - [x] Nested\r\n"

        val step1 = TaskLineMutator.toggleTask(original, lineNumber = 2)
        assertTrue(step1 is TaskMutationResult.Updated)
        step1 as TaskMutationResult.Updated
        assertEquals("# Plan\r\n- [x] First\r\n  - [x] Nested\r\n", step1.content)
        assertTrue(step1.isCompleted)

        val step2 = TaskLineMutator.toggleTask(step1.content, lineNumber = 3)
        assertTrue(step2 is TaskMutationResult.Updated)
        step2 as TaskMutationResult.Updated
        assertEquals("# Plan\r\n- [x] First\r\n  - [ ] Nested\r\n", step2.content)
    }

    @Test
    fun `rejects non-task lines and out of bounds`() {
        val content = "Plain line\n- regular bullet"
        assertTrue(TaskLineMutator.toggleTask(content, 2) is TaskMutationResult.NotATaskLine)
        assertTrue(TaskLineMutator.toggleTask(content, 5) is TaskMutationResult.LineOutOfBounds)
    }
}

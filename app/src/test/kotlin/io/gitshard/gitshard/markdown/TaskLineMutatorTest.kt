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

    @Test
    fun `toggles tasks inside blockquotes and callouts`() {
        val content = "> [!todo]\n> - [ ] Callout task\n> > 1. [x] Nested quote task"

        val toggledCallout = TaskLineMutator.toggleTask(content, lineNumber = 2)
        assertTrue(toggledCallout is TaskMutationResult.Updated)
        toggledCallout as TaskMutationResult.Updated
        assertEquals("> [!todo]\n> - [x] Callout task\n> > 1. [x] Nested quote task", toggledCallout.content)
        assertTrue(toggledCallout.isCompleted)

        val toggledNested = TaskLineMutator.toggleTask(toggledCallout.content, lineNumber = 3)
        assertTrue(toggledNested is TaskMutationResult.Updated)
        toggledNested as TaskMutationResult.Updated
        assertEquals("> [!todo]\n> - [x] Callout task\n> > 1. [ ] Nested quote task", toggledNested.content)
        assertEquals(false, toggledNested.isCompleted)
    }
}

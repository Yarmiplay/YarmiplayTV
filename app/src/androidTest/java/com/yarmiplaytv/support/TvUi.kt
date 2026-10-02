package com.yarmiplaytv.support

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/** Helpers for the D-pad driven TV UI, whose tiles carry no test tags. */
@OptIn(ExperimentalTestApi::class)
class TvUi(private val compose: ComposeTestRule) {
    private fun labelled(text: String): SemanticsMatcher =
        hasText(text, substring = true) or hasContentDescription(text, substring = true) or
            hasAnyDescendant(hasText(text, substring = true) or hasContentDescription(text, substring = true))

    /** Activates the tile or button showing [text] (or with that content description) without moving focus. */
    fun click(text: String) {
        compose.waitUntilAtLeastOneExists(hasClickAction() and labelled(text), 10_000)
        compose.onAllNodes(hasClickAction() and labelled(text)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    fun await(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)

    fun awaitGone(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntilDoesNotExist(hasText(text, substring = true), timeoutMs)

    /** A closed dialog's window keeps receiving keys until the activity's window has focus again. */
    fun awaitActivityFocus(timeoutMs: Long = 5_000) {
        compose.waitUntil(timeoutMs) {
            var focused = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                focused = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it.hasWindowFocus() }
            }
            focused
        }
    }

    /** Compose only recomposes while the test waits for it, so let the UI catch up before each key. */
    fun key(code: Int, times: Int = 1) {
        repeat(times) {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
            compose.waitForIdle()
        }
    }

    fun back() = key(KeyEvent.KEYCODE_BACK)

    /**
     * Waits until the focused element shows [text]: its own (merged) label, or that of its only child
     * (text fields focus a wrapper). Labels further down don't count, or a focused screen container
     * would match any button on it.
     */
    fun awaitFocus(text: String, timeoutMs: Long = 5_000) {
        val label = hasText(text, substring = true) or hasContentDescription(text, substring = true)
        val onlyChild = SemanticsMatcher("has a single child") { it.children.size == 1 }
        try {
            compose.waitUntilAtLeastOneExists(isFocused() and (label or (onlyChild and hasAnyChild(label))), timeoutMs)
        } catch (e: ComposeTimeoutException) {
            val shot = Screenshots.saveFailure("focus-${text.filter(Char::isLetterOrDigit)}")
            throw AssertionError("Expected focus on \"$text\" but focus is on: ${focusedLabels()} (screen: $shot)", e)
        }
    }

    /** Waits until a text field (or the wrapper around one) has focus. */
    fun awaitFocusedTextField(timeoutMs: Long = 5_000) {
        try {
            compose.waitUntilAtLeastOneExists(isFocused() and (hasSetTextAction() or hasAnyChild(hasSetTextAction())), timeoutMs)
        } catch (e: ComposeTimeoutException) {
            val shot = Screenshots.saveFailure("focus-text-field")
            throw AssertionError("Expected focus on a text field but focus is on: ${focusedLabels()} (screen: $shot)", e)
        }
    }

    private fun focusedLabels(): String {
        val nodes = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        if (nodes.isEmpty()) return "nothing"
        return nodes.joinToString { node ->
            val labels = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            labels.joinToString(" / ").ifEmpty { "an unlabelled container (${node.children.size} children)" }
        }
    }
}

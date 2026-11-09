package com.melhive.airx1.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Types into the focused text field through accessibility actions (used by AirKeys). */
object TextInjector {

    private fun focused(service: AccessibilityService): AccessibilityNodeInfo? =
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    fun hasField(service: AccessibilityService) = focused(service) != null

    private class State(val node: AccessibilityNodeInfo, val text: String, val start: Int, val end: Int)

    private fun state(service: AccessibilityService): State? {
        val node = focused(service) ?: return null
        val hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val text = if (hint) "" else node.text?.toString() ?: ""
        var a = node.textSelectionStart
        var b = node.textSelectionEnd
        if (a < 0 || b < 0 || a > text.length || b > text.length) {
            a = text.length
            b = text.length
        }
        return State(node, text, minOf(a, b), maxOf(a, b))
    }

    /** True when the next letter should be capitalized (empty field or after ". "). */
    fun wantsCapital(service: AccessibilityService): Boolean {
        val s = state(service) ?: return false
        val before = s.text.substring(0, s.start).trimEnd(' ')
        return before.isEmpty() || before.endsWith('.') || before.endsWith('!') || before.endsWith('?') || before.endsWith('\n')
    }

    fun insert(service: AccessibilityService, insert: String): Boolean {
        val s = state(service) ?: return false
        val newText = s.text.substring(0, s.start) + insert + s.text.substring(s.end)
        return set(s.node, newText, s.start + insert.length)
    }

    fun backspace(service: AccessibilityService): Boolean {
        val s = state(service) ?: return false
        if (s.start != s.end) return set(s.node, s.text.substring(0, s.start) + s.text.substring(s.end), s.start)
        if (s.start == 0) return true
        val cut = Character.charCount(s.text.codePointBefore(s.start))
        return set(s.node, s.text.substring(0, s.start - cut) + s.text.substring(s.start), s.start - cut)
    }

    fun enter(service: AccessibilityService): Boolean {
        val node = focused(service) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id }
        ) {
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return insert(service, "\n")
    }

    private fun set(node: AccessibilityNodeInfo, text: String, cursor: Int): Boolean {
        val ok = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
        if (ok) {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                },
            )
        }
        return ok
    }
}

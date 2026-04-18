package com.lumi.app.whatsapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads WhatsApp conversations and can silently send messages via the UI.
 * Activated only when the user enables it in Settings → Accessibility.
 */
class LumiAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: LumiAccessibilityService? = null

        fun isAvailable() = instance != null

        /**
         * Read up to [limit] message texts from the currently visible WhatsApp screen.
         * Returns empty list if service is unavailable or WhatsApp isn't in foreground.
         */
        fun readVisibleMessages(limit: Int = 25): List<String> =
            instance?.extractMessages(limit) ?: emptyList()

        /**
         * Type [text] into the currently focused WhatsApp input field and tap Send.
         * Returns true on success.
         */
        fun sendCurrentMessage(text: String): Boolean =
            instance?.performSend(text) ?: false

        /**
         * Tap the Save / Done button in the currently visible Samsung Notes compose screen.
         * Returns true on success.
         */
        fun saveSamsungNote(): Boolean =
            instance?.performSamsungNoteSave() ?: false
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            // Watch WhatsApp AND Samsung Notes so we can auto-tap their buttons
            packageNames = arrayOf(
                "com.whatsapp", "com.whatsapp.w4b",
                "com.samsung.android.app.notes"
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    private fun extractMessages(limit: Int): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        collectText(root, texts)
        root.recycle()
        // Filter out timestamps and UI labels; keep message-length strings
        return texts.filter { it.length > 2 && !it.matches(Regex("\\d{1,2}:\\d{2}.*")) }
            .takeLast(limit)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    private fun performSend(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Find WhatsApp message input by view ID
        val inputs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry")
            ?.takeIf { it.isNotEmpty() }
            ?: root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/input")
            ?: return false

        val inputNode = inputs.firstOrNull() ?: return false
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })

        val sendNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            ?: return false
        return sendNodes.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun performSamsungNoteSave(): Boolean {
        val root = rootInActiveWindow ?: return false
        // Try known Samsung Notes save/done button resource IDs
        val saveIds = listOf(
            "com.samsung.android.app.notes:id/action_bar_title_done",
            "com.samsung.android.app.notes:id/done_button",
            "com.samsung.android.app.notes:id/save_button",
            "com.samsung.android.app.notes:id/menu_save"
        )
        for (resId in saveIds) {
            root.findAccessibilityNodeInfosByViewId(resId)
                ?.firstOrNull()
                ?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        }
        // Fallback: find a clickable node whose text matches common save labels
        val saveLabels = listOf("Save", "Salvează", "Done", "Gata", "완료", "저장")
        for (label in saveLabels) {
            root.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.isClickable }
                ?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        }
        // Last resort: simulate Back (Samsung Notes auto-saves on back)
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
}

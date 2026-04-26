package com.lumi.app.whatsapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads conversations and drives UI actions across WhatsApp, social apps, Gmail, VPN apps,
 * and Samsung Notes. Activated only when the user enables it in Settings → Accessibility.
 */
class LumiAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: LumiAccessibilityService? = null

        fun isAvailable() = instance != null

        fun readVisibleMessages(limit: Int = 25): List<String> =
            instance?.extractMessages(limit) ?: emptyList()

        fun sendCurrentMessage(text: String): Boolean =
            instance?.performSend(text) ?: false

        fun saveSamsungNote(): Boolean =
            instance?.performSamsungNoteSave() ?: false

        /** Navigate inside a social media app to a user's conversation and send a message. */
        fun sendSocialMessage(pkg: String, username: String, text: String): Boolean =
            instance?.performSocialSend(pkg, username, text) ?: false

        /** Tap Connect/Disconnect in the currently open VPN app. */
        fun tapVpnConnect(pkg: String, connect: Boolean): Boolean =
            instance?.performVpnToggle(connect) ?: false

        /** Tap the Send action in Gmail compose (called after compose screen is open). */
        fun sendGmailAfterCompose(): Boolean =
            instance?.performGmailSend() ?: false

        /**
         * After opening WhatsApp share picker with an image, search for [contactName] and tap it,
         * then tap the Send/OK button. Call from a coroutine after a delay for WhatsApp to load.
         */
        fun tapWhatsAppShareContact(contactName: String): Boolean =
            instance?.performWhatsAppShareContact(contactName) ?: false

        /** Tap the Send button on the image preview/caption screen in WhatsApp. */
        fun tapWhatsAppImageSend(): Boolean =
            instance?.performWhatsAppImageSend() ?: false
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            packageNames = arrayOf(
                "com.whatsapp", "com.whatsapp.w4b",
                "com.samsung.android.app.notes",
                "com.instagram.android",
                "com.snapchat.android",
                "com.facebook.orca",
                "com.discord",
                "com.google.android.gm",
                "com.surfshark.vpnclient.android",
                "com.nordvpn.android",
                "com.spotify.music",
                "com.google.android.apps.youtube.music",
                "com.netflix.mediaclient",
                "com.revolut.revolut",
                "ro.btrl.mobile",
                "com.transferwise.android",
                "com.paypal.android.p2pmobile",
                "com.amazon.mShoppingApp",
                "com.binance.dev",
                "com.coinbase.android"
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    // ─── WhatsApp ─────────────────────────────────────────────────────────────

    private fun extractMessages(limit: Int): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        collectText(root, texts)
        root.recycle()
        return texts.filter { it.length > 2 && !it.matches(Regex("\\d{1,2}:\\d{2}.*")) }
            .takeLast(limit)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collectText(node.getChild(i), out)
    }

    private fun performSend(text: String): Boolean {
        // Wait for the chat input to actually appear (WhatsApp sometimes lags behind the deep-link)
        val inputIds = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp:id/input",
            "com.whatsapp:id/compose_box_layout",
            "com.whatsapp:id/message_edit_text",
            "com.whatsapp:id/chat_input_field"
        )
        val inputNode = waitFor(2500) { root ->
            nodeByIds(root, inputIds) ?: findEditText(root)
        } ?: return false
        if (!setNodeTextSafely(inputNode, text)) return false
        // Wait for the Send button to enable after typing
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/compose_box_send_button"
        )
        val sendNode = waitFor(2000) { root ->
            nodeByIds(root, sendIds)
                ?: nodeByDescs(root, listOf("Send", "Trimite", "Trimiteţi", "Send message"))
        } ?: return false
        return clickWithRetry(sendNode)
    }

    // ─── Samsung Notes ────────────────────────────────────────────────────────

    private fun performSamsungNoteSave(): Boolean {
        val root = rootInActiveWindow ?: return false
        val saveIds = listOf(
            "com.samsung.android.app.notes:id/action_bar_title_done",
            "com.samsung.android.app.notes:id/done_button",
            "com.samsung.android.app.notes:id/save_button",
            "com.samsung.android.app.notes:id/menu_save"
        )
        nodeByIds(root, saveIds)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        val saveLabels = listOf("Save", "Salvează", "Done", "Gata", "완료", "저장")
        for (label in saveLabels) {
            root.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.isClickable }
                ?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        }
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ─── Social media ─────────────────────────────────────────────────────────

    private fun performSocialSend(pkg: String, username: String, text: String): Boolean {
        // Step 0: if the input field is already visible (deep-link landed in chat), skip compose
        val directInput = rootInActiveWindow?.let { nodeByIds(it, socialInputIds(pkg)) ?: findEditText(it) }
        if (directInput != null) {
            if (!setNodeTextSafely(directInput, text)) return false
            val sendNode = waitFor(2000) { root ->
                nodeByIds(root, socialSendIds(pkg))
                    ?: nodeByDescs(root, listOf("Send", "Trimite", "Send Message", "Envoyer"))
            } ?: return false
            return clickWithRetry(sendNode)
        }

        // Step 1: tap compose / new-message button
        val composeNode = waitFor(2000) { root ->
            nodeByIds(root, socialComposeIds(pkg))
                ?: nodeByDescs(root, listOf("New message", "New Chat", "Write message",
                    "Direct", "Compose", "Mesaj nou", "Chat nou", "New DM"))
        }
        if (composeNode != null) {
            clickWithRetry(composeNode)
        }

        // Step 2: find search field and type username
        val searchNode = waitFor(2500) { root ->
            nodeByIds(root, socialSearchIds(pkg)) ?: findEditText(root)
        }
        if (searchNode != null) {
            setNodeTextSafely(searchNode, username)
            // Wait for the result list to render
            val resultNode = waitFor(3000) { root ->
                root.findAccessibilityNodeInfosByText(username)?.firstOrNull { it.isClickable }
                    ?: firstClickableLeaf(root)
            }
            resultNode?.let { clickWithRetry(it) }
        }

        // Step 3: find message input and send
        val inputNode = waitFor(3000) { root ->
            nodeByIds(root, socialInputIds(pkg)) ?: findEditText(root)
        } ?: return false
        if (!setNodeTextSafely(inputNode, text)) return false

        val sendNode = waitFor(2000) { root ->
            nodeByIds(root, socialSendIds(pkg))
                ?: nodeByDescs(root, listOf("Send", "Trimite", "Send Message", "Envoyer"))
        } ?: return false
        return clickWithRetry(sendNode)
    }

    private fun socialComposeIds(pkg: String) = when (pkg) {
        "com.instagram.android" -> listOf("com.instagram.android:id/row_inbox_new_thread",
            "com.instagram.android:id/action_bar_inbox_new_thread")
        "com.snapchat.android"  -> listOf("com.snapchat.android:id/chat_list_new_chat_btn")
        "com.facebook.orca"     -> listOf("com.facebook.orca:id/composer_button",
            "com.facebook.orca:id/new_thread_button")
        "com.discord"           -> listOf("com.discord:id/new_dm_button")
        else -> emptyList()
    }

    private fun socialSearchIds(pkg: String) = when (pkg) {
        "com.instagram.android" -> listOf("com.instagram.android:id/search_input_text")
        "com.snapchat.android"  -> listOf("com.snapchat.android:id/search_bar",
            "com.snapchat.android:id/search_input")
        "com.facebook.orca"     -> listOf("com.facebook.orca:id/search_box",
            "com.facebook.orca:id/typeahead_text_input")
        "com.discord"           -> listOf("com.discord:id/search_bar")
        else -> emptyList()
    }

    private fun socialInputIds(pkg: String) = when (pkg) {
        "com.instagram.android" -> listOf("com.instagram.android:id/row_thread_composer_edittext")
        "com.snapchat.android"  -> listOf("com.snapchat.android:id/input_bar_text_box",
            "com.snapchat.android:id/chat_input_field")
        "com.facebook.orca"     -> listOf("com.facebook.orca:id/message_text_input")
        "com.discord"           -> listOf("com.discord:id/chat_input")
        else -> emptyList()
    }

    private fun socialSendIds(pkg: String) = when (pkg) {
        "com.instagram.android" -> listOf("com.instagram.android:id/row_thread_composer_button_send")
        "com.snapchat.android"  -> listOf("com.snapchat.android:id/send_button",
            "com.snapchat.android:id/chat_send_button")
        "com.facebook.orca"     -> listOf("com.facebook.orca:id/send_button")
        "com.discord"           -> listOf("com.discord:id/send_message_button")
        else -> emptyList()
    }

    // ─── VPN ──────────────────────────────────────────────────────────────────

    private fun performVpnToggle(connect: Boolean): Boolean {
        val connectLabels   = listOf("Connect", "Quick Connect", "Conecteaza", "Conectare",
            "Connect Now", "Turn On", "Enable", "Start VPN", "Activate")
        val disconnectLabels = listOf("Disconnect", "Deconecteaza", "Deconectare",
            "Turn Off", "Disable", "Stop VPN", "Deactivate")
        val labels = if (connect) connectLabels else disconnectLabels
        val target = waitFor(3500) { root ->
            findVpnButton(root, labels)
        } ?: rootInActiveWindow?.let { firstClickableLeaf(it) } ?: return false
        return clickWithRetry(target)
    }

    private fun findVpnButton(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (label in labels) {
            root.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.isClickable }?.let { return it }
        }
        return nodeByDescs(root, labels)
    }

    // ─── WhatsApp share picker ────────────────────────────────────────────────

    private fun performWhatsAppShareContact(contactName: String): Boolean {
        // Wait for the "Send to" picker to load
        val searchIds = listOf(
            "com.whatsapp:id/search_bar",
            "com.whatsapp:id/search_input",
            "com.whatsapp:id/search_src_text",
            "com.whatsapp:id/query"
        )
        val searchNode = waitFor(2500) { root ->
            nodeByIds(root, searchIds) ?: findEditText(root)
        }
        if (searchNode != null) {
            setNodeTextSafely(searchNode, contactName)
        }

        // Wait for the contact row to render
        val contactNode = waitFor(2500) { root ->
            root.findAccessibilityNodeInfosByText(contactName)?.firstOrNull { it.isClickable }
                ?: firstClickableLeaf(root)
        } ?: return false
        clickWithRetry(contactNode)

        // Wait for Send / OK / Forward button after contact selection
        val sendIds = listOf(
            "com.whatsapp:id/share_forward_btn",
            "com.whatsapp:id/send",
            "com.whatsapp:id/ok_btn",
            "com.whatsapp:id/done",
            "com.whatsapp:id/forward"
        )
        val sendNode = waitFor(2500) { root ->
            nodeByIds(root, sendIds)
                ?: nodeByDescs(root, listOf("Send", "Trimite", "OK", "Forward", "Inainte", "Done"))
        } ?: return false
        return clickWithRetry(sendNode)
    }

    private fun performWhatsAppImageSend(): Boolean {
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/compose_box_send_button"
        )
        val sendNode = waitFor(2500) { root ->
            nodeByIds(root, sendIds)
                ?: nodeByDescs(root, listOf("Send", "Trimite", "Trimiteţi", "Send message"))
        } ?: return false
        return clickWithRetry(sendNode)
    }

    // ─── Gmail ────────────────────────────────────────────────────────────────

    private fun performGmailSend(): Boolean {
        val sendNode = waitFor(2500) { root ->
            nodeByIds(root, listOf(
                "com.google.android.gm:id/send_menu_item",
                "com.google.android.gm:id/send"
            )) ?: nodeByDescs(root, listOf("Send", "Trimite"))
        } ?: return false
        return clickWithRetry(sendNode)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun nodeByIds(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let { return it }
        }
        return null
    }

    private fun nodeByDescs(root: AccessibilityNodeInfo, descs: List<String>): AccessibilityNodeInfo? {
        for (desc in descs) {
            root.findAccessibilityNodeInfosByText(desc)?.firstOrNull { it.isClickable }?.let { return it }
        }
        return null
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }

    /**
     * Set the text on an EditText, retry up to 3 times if the text doesn't stick.
     * Some apps (Instagram, Snapchat) reject the first SET_TEXT call after the field
     * has just appeared. Returns true if the field eventually contains the text.
     */
    private fun setNodeTextSafely(node: AccessibilityNodeInfo, text: String): Boolean {
        repeat(3) { attempt ->
            setNodeText(node, text)
            // Small backoff: 200ms, 400ms, 800ms
            Thread.sleep(200L shl attempt)
            val current = node.text?.toString() ?: ""
            if (current.contains(text.take(20))) return true
            // Refresh the node — its text may have changed but the cached snapshot hasn't
            node.refresh()
        }
        return (node.text?.toString() ?: "").isNotBlank()
    }

    /**
     * Click a node and retry once if the action returns false. Some accessibility nodes
     * fail the first click when the UI is mid-animation.
     */
    private fun clickWithRetry(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        Thread.sleep(300)
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Poll [rootInActiveWindow] until [predicate] returns a non-null match or [timeoutMs]
     * elapses. This is far more robust than fixed `Thread.sleep` because it adapts to
     * slow UI loads (cold app starts) without wasting time on fast ones.
     */
    private inline fun <T> waitFor(timeoutMs: Long, intervalMs: Long = 150L, predicate: (AccessibilityNodeInfo) -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val result = predicate(root)
                if (result != null) return result
            }
            Thread.sleep(intervalMs)
        }
        return null
    }

    private fun findEditText(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.className?.contains("EditText") == true && node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditText(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun firstClickableLeaf(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 6) return null
        if (node.isClickable && !node.text.isNullOrBlank()) return node
        for (i in 0 until node.childCount) {
            firstClickableLeaf(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }
}

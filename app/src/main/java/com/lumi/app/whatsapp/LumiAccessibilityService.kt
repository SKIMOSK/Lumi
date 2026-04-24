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
        val root = rootInActiveWindow ?: return false
        // Try every known input field ID across WhatsApp versions
        val inputIds = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp:id/input",
            "com.whatsapp:id/compose_box_layout",
            "com.whatsapp:id/message_edit_text",
            "com.whatsapp:id/chat_input_field"
        )
        val inputNode = nodeByIds(root, inputIds) ?: findEditText(root) ?: return false
        setNodeText(inputNode, text)
        Thread.sleep(400)
        val root2 = rootInActiveWindow ?: return false
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/compose_box_send_button"
        )
        val sendNode = nodeByIds(root2, sendIds)
            ?: nodeByDescs(root2, listOf("Send", "Trimite", "Trimiteţi", "Send message"))
        return sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
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
        // Step 0: if the input field is already visible, skip the compose flow
        val rootCheck = rootInActiveWindow
        val directInput = rootCheck?.let { nodeByIds(it, socialInputIds(pkg)) ?: findEditText(it) }
        if (directInput != null) {
            setNodeText(directInput, text)
            Thread.sleep(400)
            val rootSend = rootInActiveWindow ?: return false
            val sendNode = nodeByIds(rootSend, socialSendIds(pkg))
                ?: nodeByDescs(rootSend, listOf("Send", "Trimite", "Send Message", "Envoyer"))
            return sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }

        // Step 1: tap compose / new-message button
        val root = rootInActiveWindow ?: return false
        val composeNode = nodeByIds(root, socialComposeIds(pkg))
            ?: nodeByDescs(root, listOf("New message", "New Chat", "Write message",
                "Direct", "Compose", "Mesaj nou", "Chat nou", "New DM"))
        if (composeNode != null) {
            composeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(1200)
        }

        // Step 2: find search field and type username
        val root2 = rootInActiveWindow ?: return false
        val searchNode = nodeByIds(root2, socialSearchIds(pkg)) ?: findEditText(root2)
        if (searchNode != null) {
            setNodeText(searchNode, username)
            Thread.sleep(1800)
            // Tap best matching result
            val root3 = rootInActiveWindow ?: return false
            val result = root3.findAccessibilityNodeInfosByText(username)
                ?.firstOrNull { it.isClickable }
                ?: firstClickableLeaf(root3)
            result?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(1200)
        }

        // Step 3: find message input and send
        val root4 = rootInActiveWindow ?: return false
        val inputNode = nodeByIds(root4, socialInputIds(pkg)) ?: findEditText(root4) ?: return false
        setNodeText(inputNode, text)
        Thread.sleep(500)

        val root5 = rootInActiveWindow ?: return false
        val sendNode = nodeByIds(root5, socialSendIds(pkg))
            ?: nodeByDescs(root5, listOf("Send", "Trimite", "Send Message", "Envoyer"))
            ?: return false
        return sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
        val root = rootInActiveWindow ?: return false
        val connectLabels   = listOf("Connect", "Quick Connect", "Conecteaza", "Conectare",
            "Connect Now", "Turn On", "Enable", "Start VPN", "Activate")
        val disconnectLabels = listOf("Disconnect", "Deconecteaza", "Deconectare",
            "Turn Off", "Disable", "Stop VPN", "Deactivate")
        val labels = if (connect) connectLabels else disconnectLabels
        // Text search (case-insensitive via API)
        for (label in labels) {
            root.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.isClickable }
                ?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        }
        // Content-description search
        nodeByDescs(root, labels)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        // Last resort: find any large central button (toggle) that's clickable
        val centralBtn = firstClickableLeaf(root) ?: return false
        return centralBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ─── WhatsApp share picker ────────────────────────────────────────────────

    private fun performWhatsAppShareContact(contactName: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Search field in the "Send to" picker
        val searchIds = listOf(
            "com.whatsapp:id/search_bar",
            "com.whatsapp:id/search_input",
            "com.whatsapp:id/search_src_text",
            "com.whatsapp:id/query"
        )
        val searchNode = nodeByIds(root, searchIds) ?: findEditText(root)
        if (searchNode != null) {
            setNodeText(searchNode, contactName)
            Thread.sleep(1600)
        }

        // Tap the contact row
        val root2 = rootInActiveWindow ?: return false
        val contactNode = root2.findAccessibilityNodeInfosByText(contactName)
            ?.firstOrNull { it.isClickable }
            ?: firstClickableLeaf(root2)
        contactNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(800)

        // Tap Send / OK / Forward button that appears after selecting a contact
        val root3 = rootInActiveWindow ?: return false
        val sendIds = listOf(
            "com.whatsapp:id/share_forward_btn",
            "com.whatsapp:id/send",
            "com.whatsapp:id/ok_btn",
            "com.whatsapp:id/done",
            "com.whatsapp:id/forward"
        )
        val sendNode = nodeByIds(root3, sendIds)
            ?: nodeByDescs(root3, listOf("Send", "Trimite", "OK", "Forward", "Inainte", "Done"))
        return sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun performWhatsAppImageSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        val sendIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/compose_box_send_button"
        )
        val sendNode = nodeByIds(root, sendIds)
            ?: nodeByDescs(root, listOf("Send", "Trimite", "Trimiteţi", "Send message"))
        return sendNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    // ─── Gmail ────────────────────────────────────────────────────────────────

    private fun performGmailSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        nodeByIds(root, listOf("com.google.android.gm:id/send_menu_item", "com.google.android.gm:id/send"))
            ?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        return nodeByDescs(root, listOf("Send", "Trimite"))
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
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

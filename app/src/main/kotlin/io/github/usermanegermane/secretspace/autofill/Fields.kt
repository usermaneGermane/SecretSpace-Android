package io.github.usermanegermane.secretspace.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import java.util.Locale

/**
 * The login fields of a screen another app shows, from what Android tells
 * an autofill service about it: the app's own hints first, then the kind
 * of text a field takes, then the words its id and hint use. Also the web
 * site the page is from (browsers say), or else the app's package.
 */
class Fields(val username: AutofillId?, val password: AutofillId?, val webDomain: String?, val packageName: String) {
    val ids: List<AutofillId> get() = listOfNotNull(username, password)
    val isLogin: Boolean get() = password != null || username != null

    companion object {
        private val USER_WORDS = listOf("user", "login", "email", "e-mail", "mail", "account", "identifier", "phone")
        private val PASS_WORDS = listOf("pass", "pwd", "pin", "secret")
        private val NOT_LOGIN = listOf("search", "captcha", "otp", "code", "card", "cvv", "cvc", "zip", "postal")

        fun of(structure: AssistStructure): Fields {
            val nodes = mutableListOf<AssistStructure.ViewNode>()
            var domain: String? = null
            fun walk(node: AssistStructure.ViewNode) {
                if (domain == null && !node.webDomain.isNullOrEmpty()) domain = node.webDomain
                nodes += node
                for (i in 0 until node.childCount) walk(node.getChildAt(i))
            }
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)
            val editable = nodes.filter { it.autofillId != null && it.autofillType == View.AUTOFILL_TYPE_TEXT }
            val password = editable.firstOrNull { isPassword(it) }
            val beforePassword = if (password == null) editable else editable.subList(0, editable.indexOf(password))
            val username = editable.firstOrNull { it !== password && hinted(it, View.AUTOFILL_HINT_USERNAME, View.AUTOFILL_HINT_EMAIL_ADDRESS) }
                ?: beforePassword.lastOrNull { it !== password && !isPassword(it) && words(it, USER_WORDS) }
                ?: if (password != null) beforePassword.lastOrNull { !isPassword(it) && !words(it, NOT_LOGIN) } else null
            return Fields(username?.autofillId, password?.autofillId, domain?.lowercase(Locale.ROOT),
                structure.activityComponent?.packageName ?: "")
        }

        private fun hinted(node: AssistStructure.ViewNode, vararg hints: String) =
            node.autofillHints?.any { h -> hints.any { it.equals(h, ignoreCase = true) } } == true

        private fun isPassword(node: AssistStructure.ViewNode): Boolean {
            if (hinted(node, View.AUTOFILL_HINT_PASSWORD, "newPassword", "currentPassword")) return true
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val klass = node.inputType and InputType.TYPE_MASK_CLASS
            if (klass == InputType.TYPE_CLASS_TEXT && variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return true
            if (klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
            if (node.htmlInfo?.attributes?.any { it.first == "type" && it.second == "password" } == true) return true
            return words(node, PASS_WORDS) && !words(node, NOT_LOGIN)
        }

        private fun words(node: AssistStructure.ViewNode, list: List<String>): Boolean {
            val text = listOfNotNull(node.idEntry, node.hint?.toString(),
                node.htmlInfo?.attributes?.firstOrNull { it.first == "name" }?.second,
                node.htmlInfo?.attributes?.firstOrNull { it.first == "id" }?.second)
                .joinToString(" ").lowercase(Locale.ROOT)
            return list.any { text.contains(it) }
        }
    }
}

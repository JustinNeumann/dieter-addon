package com.getdieter.web

import com.getdieter.client.v1.Command
import com.getdieter.client.v1.OpenConversationRequest
import com.getdieter.mobile.navigation.CardPane
import com.getdieter.mobile.navigation.MobileNavigation
import com.getdieter.mobile.navigation.MobileRoute
import com.getdieter.mobile.navigation.MobileTab
import com.getdieter.mobile.navigation.ToolPage
import com.getdieter.mobile.store.MobileStore
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where the page was, across loads. Home Assistant drops the add-on's frame when you leave it for another panel or
 * dashboard and loads it afresh when you come back, which started over at the Inbox. The page keeps its place (the
 * open tab and each tab's screens) in this browser's storage, so per device and per address (local or Nabu Casa),
 * and a load that asks for nothing else (no `?route=`, `?script=`) goes back there. Sheets (New task, Share) and the
 * screens that hold a live session (terminal, screen) or a file are not kept: the place ends on the screen below them.
 */
internal object LastPlace {
    /** Restores the place saved under [key], then saves every navigation there. */
    fun follow(store: MobileStore, key: String, restore: Boolean) {
        val saved = if (restore) storageRead(key)?.let(::decode) else null
        if (saved != null) {
            store.routes.value = saved
            store.replaceTop(saved.top) // binds the core's scopes to the restored screens
            MainScope().launch { dropGone(store, saved) }
        }
        MainScope().launch { store.routes.collect { storageWrite(key, encode(it)) } }
    }

    /** A conversation that is gone since leaves the place, as a deep link to it does. */
    private suspend fun dropGone(store: MobileStore, saved: MobileNavigation) {
        val ids = saved.stacks.values.flatten().mapNotNull { it.card }.toSet()
        val targets = ids.associateWith { id ->
            runCatching { store.core.dispatch(Command(open_conversation_request = OpenConversationRequest(id))) }
                .getOrNull()
                ?.notification_target
        }
        val stacks = saved.stacks.mapValues { (_, stack) ->
            val gone = stack.indexOfFirst { route -> route.card?.let { targets[it]?.inbox != null } == true }
            if (gone < 0) stack else stack.take(gone)
        }
        // only while nothing else moved the page
        if (stacks != saved.stacks && store.routes.value == saved) {
            val next = saved.copy(stacks = stacks)
            store.routes.value = next
            store.replaceTop(next.top)
        }
    }

    private val MobileRoute.card: String?
        get() = when (this) {
            is MobileRoute.Conversation -> cardId
            is MobileRoute.Pane -> cardId
            else -> null
        }

    internal fun encode(navigation: MobileNavigation): String =
        JsonObject(
            mapOf(
                "tab" to JsonPrimitive(navigation.tab.name),
                "stacks" to JsonObject(
                    navigation.stacks.map { (tab, stack) ->
                        tab.name to JsonArray(stack.map(::fields).takeWhile { it != null }.map { route ->
                            JsonArray(route!!.map(::JsonPrimitive))
                        })
                    }.toMap()
                ),
            )
        ).toString()

    internal fun decode(text: String): MobileNavigation? = runCatching {
        val json = Json.parseToJsonElement(text).jsonObject
        val tab = MobileTab.valueOf(json.getValue("tab").jsonPrimitive.content)
        val saved = json.getValue("stacks").jsonObject
        val stacks = MobileTab.entries.associateWith { each ->
            val routes = saved[each.name]?.jsonArray.orEmpty()
                .map { route -> runCatching { route(route.jsonArray.map { it.jsonPrimitive.content }) }.getOrNull() }
                .takeWhile { it != null }
                .filterNotNull()
            routes.takeIf { it.firstOrNull() == MobileRoute.Root(each) } ?: listOf(MobileRoute.Root(each))
        }
        MobileNavigation(tab = tab, stacks = stacks).takeIf { it.stacks.values.any { stack -> stack.size > 1 } || tab != MobileTab.INBOX }
    }.getOrNull()

    private fun fields(route: MobileRoute): List<String>? =
        when (route) {
            is MobileRoute.Root -> listOf("root", route.tab.name)
            is MobileRoute.Project -> listOf("project", route.projectId)
            is MobileRoute.Board -> listOf("board", route.boardId)
            is MobileRoute.Conversation -> listOf("conversation", route.cardId)
            is MobileRoute.Pane -> listOf("pane", route.cardId, route.pane.name)
            MobileRoute.Channels -> listOf("channels")
            MobileRoute.Mailbox -> listOf("mailbox")
            is MobileRoute.Channel -> listOf("channel", route.projectId)
            is MobileRoute.Tool -> listOf("tool", route.page.name, route.projectId, route.checkoutId)
            is MobileRoute.MailMessage -> listOf("mail", route.daemonId, route.id)
            is MobileRoute.Machine -> listOf("machine", route.machineId)
            is MobileRoute.Extension -> listOf("ext", route.id, route.arg)
            else -> null
        }

    private fun route(fields: List<String>): MobileRoute? {
        val f = fields.drop(1)
        return when (fields.firstOrNull()) {
            "root" -> MobileRoute.Root(MobileTab.valueOf(f[0]))
            "project" -> MobileRoute.Project(f[0])
            "board" -> MobileRoute.Board(f[0])
            "conversation" -> MobileRoute.Conversation(f[0])
            "pane" -> MobileRoute.Pane(f[0], CardPane.valueOf(f[1]))
            "channels" -> MobileRoute.Channels
            "mailbox" -> MobileRoute.Mailbox
            "channel" -> MobileRoute.Channel(f[0])
            "tool" -> MobileRoute.Tool(ToolPage.valueOf(f[0]), f[1], f[2])
            "mail" -> MobileRoute.MailMessage(f[0], f[1])
            "machine" -> MobileRoute.Machine(f[0])
            "ext" -> MobileRoute.Extension(f[0], f[1])
            else -> null
        }
    }
}

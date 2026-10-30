package com.getdieter.addon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.NameConversation
import com.getdieter.addon.v1.Slice
import com.getdieter.mobile.store.ConversationNamer
import com.getdieter.mobile.store.MobileStore

/**
 * While Settings → Task titles is Ollama, the add-on names new tasks and chats on its own machine; the add-on
 * core says which machine that is ([com.getdieter.addon.v1.SettingsSlice.titles_daemon_id]).
 */
@Composable
internal fun rememberConversationNamer(store: MobileStore): ConversationNamer? {
    val daemon = rememberAddonSlice(Slice.SLICE_SETTINGS)?.settings?.titles_daemon_id.orEmpty()
    val actions = rememberAddonActions(store)
    return remember(daemon, actions) {
        daemon.ifEmpty { null }?.let { mine ->
            object : ConversationNamer {
                override fun names(daemonId: String) = daemonId == mine

                override suspend fun name(id: String, chat: Boolean, prompt: String, provisional: String) {
                    actions.run(Command(name_conversation = NameConversation(id, chat, prompt, provisional)))
                }
            }
        }
    }
}

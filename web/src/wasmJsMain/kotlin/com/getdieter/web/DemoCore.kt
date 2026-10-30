package com.getdieter.web

import com.getdieter.client.v1.*
import com.getdieter.core.client.ClientSubscription
import com.getdieter.mobile.store.MobileCore

/**
 * A stand-in for the core: answers every observed slice from [DemoData] and handles the few
 * commands that change what the demo shows (binding a board, sending, moving a card).
 * No gateway, daemon or network is involved.
 */
class DemoCore(private val data: DemoData = DemoData(), private val extras: DemoMailFiles = DemoMailFiles()) : MobileCore {
    private var sequence = 0L
    /** The project last used on this "device" (the real core keeps it in its creation memory). */
    private var project = ""
    private val observers = mutableMapOf<Pair<Slice, String>, MutableList<(Update) -> Unit>>()

    override suspend fun dispatch(command: Command): Result {
        command.board_view?.let { view ->
            view.bind?.let { data.bindBoard(it.board_id) }
            publish(Slice.SLICE_BOARD_VIEW, view.scope)
        }
        command.send_message?.let { message ->
            data.send(message.card_id, message.text)
            publish(Slice.SLICE_CONVERSATION, message.card_id)
        }
        command.move_card?.let { move ->
            data.move(move.card_id, move.lane)
            observers.keys.filter { it.first == Slice.SLICE_BOARD_VIEW || it.first == Slice.SLICE_WORKSPACE }
                .forEach { publish(it.first, it.second) }
        }
        command.mail?.let { mail ->
            extras.mailCommand(mail)
            publish(Slice.SLICE_MAIL, mail.scope)
            return Result(mail = extras.mail())
        }
        command.open_conversation_request?.let { open ->
            val target =
                if (data.hasCard(open.card_id)) NotificationTarget(card_id = open.card_id) else NotificationTarget(inbox = Step())
            return Result(notification_target = target)
        }
        command.remember_creation?.let { remember ->
            remember.project_id.ifEmpty { null }?.let { project = it }
            publish(Slice.SLICE_CREATION, "")
        }
        command.load_tool_output?.let { return Result(tool_output = data.toolOutput(it)) }
        command.files?.let { files ->
            extras.filesCommand(files)
            publish(Slice.SLICE_FILES, files.scope)
            return Result(files = extras.files())
        }
        return Result(done = Done())
    }

    override fun observe(slice: Slice, scope: String, receive: (Update) -> Unit): ClientSubscription {
        val key = slice to scope
        observers.getOrPut(key) { mutableListOf() } += receive
        update(slice, scope)?.let { receive(numbered(it)) }
        return ClientSubscription { observers[key]?.remove(receive) }
    }

    private fun update(slice: Slice, scope: String): Update? =
        when (slice) {
            Slice.SLICE_MAIL -> Update(slice = slice, scope = scope, mail = extras.mail())
            Slice.SLICE_FILES -> Update(slice = slice, scope = scope, files = extras.files())
            Slice.SLICE_CREATION -> Update(slice = slice, scope = scope, creation = CreationSlice(project_id = project))
            else -> data.update(slice, scope)
        }

    private fun publish(slice: Slice, scope: String) {
        val update = update(slice, scope) ?: return
        observers[slice to scope].orEmpty().toList().forEach { it(numbered(update)) }
    }

    private fun numbered(update: Update) = update.copy(sequence = ++sequence)
}

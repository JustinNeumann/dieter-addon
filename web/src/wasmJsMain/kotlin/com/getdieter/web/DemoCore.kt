package com.getdieter.web

import com.getdieter.client.v1.*
import com.getdieter.core.client.ClientSubscription
import com.getdieter.mobile.store.MobileCore

/**
 * A stand-in for the core: answers every observed slice from [DemoData] and handles the few
 * commands that change what the demo shows (binding a board, sending, moving a card).
 * No gateway, daemon or network is involved.
 */
class DemoCore(private val data: DemoData = DemoData()) : MobileCore {
    private var sequence = 0L
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
        return Result(done = Done())
    }

    override fun observe(slice: Slice, scope: String, receive: (Update) -> Unit): ClientSubscription {
        val key = slice to scope
        observers.getOrPut(key) { mutableListOf() } += receive
        data.update(slice, scope)?.let { receive(numbered(it)) }
        return ClientSubscription { observers[key]?.remove(receive) }
    }

    private fun publish(slice: Slice, scope: String) {
        val update = data.update(slice, scope) ?: return
        observers[slice to scope].orEmpty().toList().forEach { it(numbered(update)) }
    }

    private fun numbered(update: Update) = update.copy(sequence = ++sequence)
}

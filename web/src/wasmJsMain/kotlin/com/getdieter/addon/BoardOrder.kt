package com.getdieter.addon

import androidx.compose.runtime.*
import com.getdieter.addon.core.AddonRules
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.OrderBoards
import com.getdieter.addon.v1.Slice
import com.getdieter.mobile.extensions.BoardOrder
import com.getdieter.mobile.store.MobileStore

/**
 * The board order the bridge keeps for both pages and every device (dragged on either page). A drop shows at once;
 * the add-on core's answer then replaces it.
 */
@Composable
internal fun rememberBoardOrder(store: MobileStore): BoardOrder? {
    val saved = rememberAddonSlice(Slice.SLICE_SETTINGS)?.settings?.board_order ?: return null
    val actions = rememberAddonActions(store)
    var dropped by remember(saved) { mutableStateOf<List<String>?>(null) }
    val order = dropped ?: saved
    return remember(order, actions) {
        BoardOrder(
            sorted = { boards -> AddonRules.inBoardOrder(boards, order) { it.id } },
            move = { ids ->
                dropped = ids + order.filter { it !in ids }
                actions.send(Command(order_boards = OrderBoards(ids)))
            },
        )
    }
}

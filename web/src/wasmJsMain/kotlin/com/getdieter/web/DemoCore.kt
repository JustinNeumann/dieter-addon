package com.getdieter.web

import com.getdieter.api.v1.Schedule
import com.getdieter.api.v1.ScheduleDraft
import com.getdieter.api.v1.ScheduleRun
import com.getdieter.client.v1.*
import com.getdieter.core.client.ClientSubscription
import com.getdieter.core.schedules.SchedulePresentations
import com.getdieter.mobile.store.MobileCore
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

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
    /** What Tools → Schedules shows: one project's schedules or every project's. */
    private var schedulesShown: ScheduleProject? = null
    /** The schedule whose run history is open ("" none). */
    private var scheduleSelected = ""

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
        command.admin?.set_board_retired?.let { retire ->
            data.setRetired(retire.board_id, retire.retired)
            observers.keys.filter { it.first == Slice.SLICE_BOARD_VIEW || it.first == Slice.SLICE_WORKSPACE }
                .forEach { publish(it.first, it.second) }
        }
        command.schedules?.let { schedules ->
            schedules.bind?.let { schedulesShown = it }
            schedules.select?.let { scheduleSelected = it.schedule_id }
            schedules.draft?.let { request ->
                val projectId =
                    if (schedulesShown?.all_projects == true) request.project_id else schedulesShown?.project_id.orEmpty()
                val board = data.update(Slice.SLICE_WORKSPACE, "")?.workspace?.boards?.firstOrNull { it.project_id == projectId }
                return Result(
                    schedule_draft = ScheduleDraft(
                        project_id = projectId, board_id = board?.id.orEmpty(), cron = "0 9 * * 1-5", timezone = "UTC",
                        title_template = "Scheduled work · {{date}}", workspace_mode = "worktree",
                    ),
                )
            }
            publish(Slice.SLICE_SCHEDULES, schedules.scope)
            return Result(schedules = schedulesSlice())
        }
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
            Slice.SLICE_SCHEDULES -> Update(slice = slice, scope = scope, schedules = schedulesSlice())
            else -> data.update(slice, scope)
        }

    private fun publish(slice: Slice, scope: String) {
        val update = update(slice, scope) ?: return
        observers[slice to scope].orEmpty().toList().forEach { it(numbered(update)) }
    }

    private fun numbered(update: Update) = update.copy(sequence = ++sequence)

    private fun schedulesSlice(): SchedulesSlice {
        val shown = schedulesShown ?: return SchedulesSlice()
        val schedules = SCHEDULES.filter { shown.all_projects || it.project_id == shown.project_id }
        return SchedulesSlice(
            project_id = if (shown.all_projects) "" else shown.project_id,
            all_projects = shown.all_projects,
            schedules = schedules,
            total_count = schedules.size,
            loaded = true,
            state = if (schedules.isEmpty()) SchedulesSlice.State.STATE_EMPTY else SchedulesSlice.State.STATE_LOADED,
            rows = schedules.map { SchedulePresentations.row(it) },
            selected_id = scheduleSelected,
            run_rows = if (scheduleSelected.isEmpty()) emptyList() else runs(scheduleSelected).map { SchedulePresentations.runRow(it) },
        )
    }

    /** A schedule's last two runs, newest first: last night's and a manual one the day before. */
    private fun runs(scheduleId: String): List<ScheduleRun> {
        val now = Clock.System.now()
        return listOf(
            ScheduleRun(id = "$scheduleId-run-2", schedule_id = scheduleId, status = "completed", scheduled_for = (now - 1.days).toString()),
            ScheduleRun(id = "$scheduleId-run-1", schedule_id = scheduleId, status = "failed", manual = true, created_at = (now - 2.days).toString(), message = "the machine was offline"),
        )
    }

    private companion object {
        /** In two of the three projects, by name like the daemon's list. */
        val SCHEDULES = listOf(
            Schedule(id = "sch-backup", project_id = "p-home", board_id = "b-home", name = "Backup review", cron = "0 18 * * 0", timezone = "Europe/Berlin", enabled = true, action = "create_card"),
            Schedule(id = "sch-deps", project_id = "p-addon", board_id = "b-addon", name = "Dependency check", cron = "0 3 * * *", timezone = "Europe/Berlin", enabled = true, action = "create_card"),
            Schedule(id = "sch-ideas", project_id = "p-home", board_id = "b-home", name = "Morning ideas", cron = "0 4 * * 1-5", timezone = "Europe/Berlin", enabled = false, action = "create_card"),
        )
    }
}


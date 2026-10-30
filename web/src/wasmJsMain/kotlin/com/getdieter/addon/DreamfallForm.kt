package com.getdieter.addon

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.getdieter.addon.core.AddonRules
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.CreateDreamfall
import com.getdieter.addon.v1.DreamfallSettings
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.UpdateDreamfall
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.navigation.MobileRoute
import com.getdieter.mobile.store.MobileStore

private val DAYS = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 0 to "Sun")
private val ROLES = listOf("enumeration" to "Enumeration", "navigation" to "Navigation", "implementation" to "Implementation")

/**
 * A new Dreamfall board ([scope] "new:" or "new:<project>") or a board's settings ([scope] its ID). The
 * core fills the form; the page keeps the edits until they are sent.
 */
@Composable
internal fun DreamfallFormScreen(store: MobileStore, scope: String) {
    val new = scope.startsWith("new:")
    var project by remember { mutableStateOf(scope.removePrefix("new:").takeIf { new }.orEmpty()) }
    // the browser's time zone is the default for a new board (and for one that has none)
    val zone = remember { browserTimeZone() }
    val form = rememberAddonSlice(Slice.SLICE_DREAMFALL_FORM, (if (new) "new:$project" else scope) + "@$zone")?.dreamfall_form
    var settings by remember { mutableStateOf<DreamfallSettings?>(null) }
    var perCategory by remember { mutableStateOf("") }
    val boards = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf(false) }
    val actions = rememberAddonActions(store)
    LaunchedEffect(form?.settings) {
        val loaded = form?.settings ?: return@LaunchedEffect
        if (settings == null) {
            settings = loaded
            perCategory = loaded.per_category.toString()
        }
    }
    val s = settings
    val problem =
        when {
            s == null -> "Loading…"
            new && project.isEmpty() -> "Choose the project for the Dreamfall board."
            else -> AddonRules.dreamfallProblem(s)
        }
    fun submit() {
        val current = settings ?: return
        if (busy || problem != null) return
        busy = true
        val command =
            if (new) Command(create_dreamfall = CreateDreamfall(project, current, boards.toMap()))
            else Command(update_dreamfall = UpdateDreamfall(scope, current))
        actions.sendFor(command) { result ->
            busy = false
            if (result != null) {
                store.dismiss()
                if (result.created_id.isNotEmpty()) store.push(MobileRoute.Extension("dreamfall", result.created_id))
            }
        }
    }
    val confirm = if (busy) (if (new) "Creating…" else "Saving…") else if (new) "Create" else "Save"
    Screen(
        ScreenChrome(
            form?.title?.ifEmpty { null } ?: if (new) "New Dreamfall board" else "Dreamfall settings",
            subtitle = form?.project_name.orEmpty(),
            cancel = ChromeAction("cancel-dreamfall", "Cancel", Glyph.CLOSE) { store.dismiss() },
            confirm = ChromeAction("submit-dreamfall", confirm, Glyph.CHECK, enabled = !busy && problem == null) { submit() },
        )
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("dreamfall-form"), state = listState, contentPadding = padding) {
            if (form == null || s == null) {
                item { StatusRow(form?.error.orEmpty(), "Dreamfall", actions::refresh) }
                return@LazyColumn
            }
            fun edit(change: DreamfallSettings.() -> DreamfallSettings) {
                settings = settings?.change()
            }
            if (new && form.projects.size > 1 || new && project.isEmpty())
                item("project") {
                    SectionHeader("Project")
                    Group(listOf(Unit)) { _, position ->
                        PickerRow(
                            "Project",
                            form.projects.firstOrNull { it.id == project }?.label ?: "Choose",
                            form.projects.map { it.id to it.label },
                            project,
                            enabled = true,
                            position = position,
                            glyph = Glyph.PROJECTS,
                            tag = "dreamfall-project",
                        ) {
                            project = it
                            boards.clear()
                        }
                    }
                }
            item("about") {
                FormColumn {
                    field("Name", s.name, "dreamfall-name", placeholder = "Dreamfall") { edit { copy(name = it) } }
                    field(
                        "What are the ideas about? (optional)",
                        s.goal,
                        "dreamfall-goal",
                        lines = 2,
                        placeholder = "Improvement ideas for our setup: new features and automations, not bug fixes",
                    ) {
                        edit { copy(goal = it) }
                    }
                }
            }
            item("categories") {
                SectionHeader("1 · Categories to enumerate")
                FormColumn {
                    field("Categories, one per line", s.categories, "dreamfall-categories", lines = 5, placeholder = "Energy\nHeating\nSecurity") {
                        edit { copy(categories = it) }
                    }
                    field("Or a categories file in the project", s.categories_file, "dreamfall-categories-file", placeholder = "ENUMERATION_CATEGORIES.md") {
                        edit { copy(categories_file = it) }
                    }
                }
            }
            item("goal") {
                SectionHeader("2 · Enumeration goal")
                FormColumn {
                    field("New ideas per category and night", perCategory, "dreamfall-per-category") {
                        perCategory = it.filter(Char::isDigit).take(2)
                        edit { copy(per_category = perCategory.toIntOrNull() ?: 0) }
                    }
                }
                SectionFooter("The Enumeration agent writes at least this many new ideas per category every night, none repeating an idea the board already had.")
            }
            item("logic") {
                SectionHeader("3 · Navigation logic")
                FormColumn {
                    field("How the Navigation agent decides which ideas go on", s.navigation_logic, "dreamfall-logic", lines = 8) { edit { copy(navigation_logic = it) } }
                }
                SectionFooter(
                    "Plain language: what makes an idea worth your time, what to drop, how selective to be. Every idea gets promoted or released by this text, with a one-sentence reason and a score."
                )
            }
            item("implementation") {
                SectionHeader("4 · Implementation")
                FormColumn {
                    field(
                        "Instructions for every picked card (optional)",
                        s.implementation_instructions,
                        "dreamfall-instructions",
                        lines = 3,
                        placeholder = "When done, add a chart that shows the result and mail me a short report with it.",
                    ) {
                        edit { copy(implementation_instructions = it) }
                    }
                }
                Group(listOf(Unit)) { _, position ->
                    switchRow("Picked cards may mail me", "Their agents can send you reports in Dieter Mail", s.implementation_mail, position, "dreamfall-implementation-mail") {
                        edit { copy(implementation_mail = it) }
                    }
                }
                SectionFooter("Added to each card you pick for Implementation from now on; cards picked earlier keep their brief. Mail access is fixed when a card is created.")
            }
            item("schedule") {
                SectionHeader("Schedule")
                FormColumn {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        field("Enumeration runs at", s.enumeration_time, "dreamfall-enumeration-time", Modifier.weight(1f), placeholder = "02:00") {
                            edit { copy(enumeration_time = it) }
                        }
                        field("Navigation runs at", s.navigation_time, "dreamfall-navigation-time", Modifier.weight(1f), placeholder = "05:00") {
                            edit { copy(navigation_time = it) }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DAYS.forEach { (day, name) ->
                            FilterPill(name, day in s.days, {
                                edit { copy(days = if (day in days) days - day else (days + day).sorted()) }
                            }, Modifier.testTag("dreamfall-day-$day"))
                        }
                    }
                    field("Time zone", s.timezone, "dreamfall-timezone", placeholder = "Europe/Berlin") { edit { copy(timezone = it) } }
                }
            }
            item("agents") {
                SectionHeader("Agents")
                val agent = form.agents.firstOrNull { it.id == s.provider }
                val model = agent?.models?.firstOrNull { it.id == s.model }
                val rows = listOfNotNull("agent", "model", if (model?.efforts?.isNotEmpty() == true) "effort" else null)
                Group(rows) { row, position ->
                    when (row) {
                        "agent" ->
                            PickerRow("Agent", agent?.label ?: s.provider.ifEmpty { "Choose" }, form.agents.map { it.id to it.label }, s.provider, true, position, Glyph.SPARKLES, "dreamfall-agent") { id ->
                                val chosen = form.agents.firstOrNull { it.id == id }
                                edit { copy(provider = id, model = chosen?.default_model?.ifEmpty { null } ?: chosen?.models?.firstOrNull()?.id.orEmpty(), effort = "") }
                            }
                        "model" ->
                            PickerRow("Model", model?.label ?: s.model.ifEmpty { "Default" }, agent?.models.orEmpty().map { it.id to it.label }, s.model, agent != null, position, Glyph.CPU, "dreamfall-model") {
                                edit { copy(model = it, effort = "") }
                            }
                        else ->
                            PickerRow(
                                "Effort",
                                model?.efforts?.firstOrNull { it.id == s.effort }?.label ?: "Default",
                                listOf("" to "Default") + model?.efforts.orEmpty().map { it.id to it.label },
                                s.effort,
                                true,
                                position,
                                Glyph.BOLT,
                                "dreamfall-effort",
                            ) {
                                edit { copy(effort = it) }
                            }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Group(listOf("enabled", "mail")) { row, position ->
                    if (row == "enabled")
                        switchRow("Run on schedule", "Enumeration and Navigation run at the times and on the days above", s.enabled, position, "dreamfall-enabled") {
                            edit { copy(enabled = it) }
                        }
                    else switchRow("Mail me the result", "The Navigation agent mails what it promoted", s.mail, position, "dreamfall-mail") { edit { copy(mail = it) } }
                }
            }
            if (new)
                item("boards") {
                    SectionHeader("Stage boards")
                    Group(ROLES) { (role, name), position ->
                        val taken = boards.filterKeys { it != role }.values.toSet()
                        PickerRow(
                            name,
                            form.boards.firstOrNull { it.id == boards[role] }?.label ?: "New board",
                            listOf("" to "New board") + form.boards.filter { it.id !in taken }.map { it.id to it.label },
                            boards[role].orEmpty(),
                            true,
                            position,
                            Glyph.BOARD,
                            "dreamfall-board-$role",
                        ) {
                            if (it.isEmpty()) boards.remove(role) else boards[role] = it
                        }
                    }
                    SectionFooter(
                        "Each stage is an ordinary board of the project (new, or one of its boards with a Review lane). Agents write to Enumeration and Navigation; Implementation is yours: only your Pick puts cards there."
                    )
                }
            if (problem != null && s.name.isNotBlank())
                item("problem") { Note(problem, Modifier.padding(horizontal = 32.dp, vertical = 8.dp).testTag("dreamfall-problem"), palette.destructive) }
            item("submit") {
                DButton(
                    if (busy) confirm else if (new) "Create Dreamfall board" else "Save",
                    ::submit,
                    Modifier.fillMaxWidth().padding(16.dp).testTag("dreamfall-submit"),
                    enabled = !busy && problem == null,
                    large = true,
                    loading = busy,
                )
            }
        }
    }
}

@Composable
private fun field(
    label: String,
    value: String,
    tag: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    lines: Int = 1,
    placeholder: String = "",
    change: (String) -> Unit,
) {
    MobileTextField(
        value,
        change,
        modifier.testTag(tag).named(label),
        singleLine = lines == 1,
        minLines = lines,
        label = { Text(label) },
        placeholder = if (placeholder.isEmpty()) null else ({ Text(placeholder) }),
    )
}

@Composable
private fun switchRow(title: String, subtitle: String, checked: Boolean, position: Position, tag: String, change: (Boolean) -> Unit) {
    ListRow(
        title,
        position = position,
        subtitle = subtitle,
        onClick = { change(!checked) },
        trailing = { DSwitch(checked, change, Modifier.testTag(tag).named(title)) },
    )
}

@JsFun("() => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone || ''; } catch (e) { return ''; } }")
private external fun browserTimeZone(): String

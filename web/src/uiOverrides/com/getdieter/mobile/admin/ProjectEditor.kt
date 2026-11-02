@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.getdieter.mobile.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.getdieter.addon.core.AddonRules
import com.getdieter.client.v1.*
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.navigation.*
import com.getdieter.mobile.store.*
import kotlin.coroutines.cancellation.CancellationException

// The add-on's copy of the fork's admin/ProjectEditor.kt (web/build.gradle.kts swaps it in and fails when the
// fork's file changes). Unlike the fork's sheet it
// - shows why Create failed in the sheet: the app's snackbar is behind the sheet's scrim, so a failure
//   looked like a dead button;
// - takes a relative path on the add-on's machine from /data/projects (the daemon resolves it from its
//   working directory, /) and shows the path it will register;
// - starts with the add-on's machine picked, and says that a project is a Git working tree.
@Composable
internal fun ProjectEditor(store: MobileStore, onDismiss: () -> Unit) {
    val session by store.session.slice.collectAsState()
    val busy by store.busy.collectAsState()
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var machine by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf("") }
    val machines = session.machines.filter { it.available }
    // until one is picked: this machine (the add-on's), else the only one
    val chosen = machines.firstOrNull { it.id == machine } ?: machines.firstOrNull { it.local } ?: machines.singleOrNull()
    val local = chosen?.local == true
    val problem = AddonRules.projectPathProblem(path, local)
    val target = AddonRules.projectPath(path, local)
    Sheet(
        "New Project",
        onDismiss,
        confirm =
            ChromeAction(
                "create-project",
                "Create",
                Glyph.CHECK,
                enabled = name.isNotBlank() && target.isNotEmpty() && chosen != null && !busy,
            ) {
                val daemon = chosen?.id.orEmpty()
                failure = ""
                store.action {
                    val result =
                        try {
                            store.core.dispatch(
                                Command(
                                    admin =
                                        AdminCommand(
                                            create_project =
                                                AdminCreateProject(
                                                    daemon_id = daemon,
                                                    path = target,
                                                    name = name.trim(),
                                                    create = create,
                                                    board_name = "Main",
                                                    workflow = if (review) "review" else "direct",
                                                )
                                        )
                                )
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            failure = error.message ?: "The project could not be created."
                            return@action
                        }
                    onDismiss()
                    result.created_project?.board?.let { store.push(MobileRoute.Board(it.id)) }
                }
            },
    ) {
        Group(listOf(0)) { _, position ->
            PickerRow(
                "Machine",
                chosen?.display_name.orEmpty(),
                machines.map { it.id to it.display_name },
                chosen?.id.orEmpty(),
                machines.isNotEmpty(),
                position,
                Glyph.MACHINE,
            ) {
                machine = it
            }
        }
        Spacer(Modifier.height(16.dp))
        FormColumn {
            MobileTextField(
                name,
                { name = it },
                Modifier.fillMaxWidth().testTag("project-name"),
                label = { Text("Name") },
                placeholder = { Text("Project name") },
                singleLine = true,
            )
            MobileTextField(
                path,
                {
                    path = it
                    failure = ""
                },
                Modifier.fillMaxWidth().testTag("project-path"),
                label = { Text("Path") },
                placeholder = {
                    Text(if (local) "Folder in ${AddonRules.PROJECTS_DIR} or an absolute path" else "Absolute path on the machine")
                },
                supportingText =
                    when {
                        path.isBlank() -> null
                        problem != null -> { { Text(problem, color = palette.destructive) } }
                        else -> { { Text(if (create) "Creates $target" else "Opens $target") } }
                    },
                singleLine = true,
            )
        }
        if (failure.isNotEmpty())
            Text(
                failure,
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag("project-failure"),
                style = type.footnote,
                color = palette.destructive,
            )
        SectionHeader("Options")
        Group(listOf(0, 1)) { index, position ->
            if (index == 0)
                ListRow(
                    "Create a new Git repository",
                    position = position,
                    trailing = {
                        DSwitch(create, {
                            create = it
                            failure = ""
                        })
                    },
                )
            else
                ListRow(
                    "Review before merging",
                    position = position,
                    subtitle = "Cards move to Review before Done",
                    trailing = { DSwitch(review, { review = it }) },
                )
        }
        SectionFooter(
            "A project is a Git working tree. For a new folder turn on Create a new Git repository: it starts a " +
                "local repository without a remote. Otherwise the path must already be a Git working tree."
        )
    }
}

package com.getdieter.addon

import com.getdieter.addon.core.AddonRules
import com.getdieter.addon.v1.AddRepository
import com.getdieter.addon.v1.Command

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.store.MobileStore

/**
 * Projects → Add: the add-on's own repositories, cloned from a Git URL or created empty in
 * /data/projects on this machine and registered with its daemon (the bridge does both).
 */
@Composable
internal fun AddRepositoryScreen(store: MobileStore, empty: Boolean) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val actions = rememberAddonActions(store)
    val problem = AddonRules.repositoryProblem(if (empty) "" else url, name, branch)
    val ready = !busy && problem == null
    fun submit() {
        // checked again here: Enter can arrive before the last keystroke's recomposition
        if (busy || AddonRules.repositoryProblem(if (empty) "" else url, name, branch) != null) return
        busy = true
        actions.send(Command(add_repository = AddRepository(git_url = if (empty) "" else url, name = name, branch = if (empty) "" else branch))) { added ->
            busy = false
            if (added) store.dismiss()
        }
    }
    Screen(
        ScreenChrome(
            if (empty) "New repository" else "Add repository",
            cancel = ChromeAction("cancel-repository", "Cancel", Glyph.CLOSE) { store.dismiss() },
            confirm =
                ChromeAction(
                    "submit-repository",
                    if (busy) (if (empty) "Creating…" else "Cloning…") else if (empty) "Create" else "Clone",
                    Glyph.CHECK,
                    enabled = ready,
                ) { submit() },
        )
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("repository-form"), state = listState, contentPadding = padding) {
            item {
                FormColumn {
                    if (!empty) {
                        MobileTextField(
                            url,
                            { url = it },
                            Modifier.fillMaxWidth().testTag("repository-url").named("Git URL"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            label = { Text("Git URL") },
                            placeholder = { Text("https://github.com/owner/repo.git") },
                        )
                    }
                    MobileTextField(
                        name,
                        { name = it },
                        Modifier.fillMaxWidth().testTag("repository-name").named("Name"),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        label = { Text("Name") },
                        placeholder = { Text(if (empty) "Folder name" else "Project name (from the URL)") },
                    )
                    if (!empty)
                        MobileTextField(
                            branch,
                            { branch = it },
                            Modifier.fillMaxWidth().testTag("repository-branch").named("Branch"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            label = { Text("Branch") },
                            placeholder = { Text("Base branch (default of the repository)") },
                        )
                }
            }
            if (problem != null && (url.isNotBlank() || name.isNotBlank()))
                item { Note(problem, Modifier.padding(horizontal = 32.dp, vertical = 4.dp), palette.destructive) }
            item {
                SectionFooter(
                    if (empty)
                        "Creates an empty Git repository in /data/projects/<name> on this machine and registers it as " +
                            "a project. A folder that already exists there is adopted. Letters, digits, . _ - only."
                    else
                        "The repository is cloned into /data/projects on this machine and registered as a project. " +
                            "Private GitHub repositories use the add-on's GitHub token."
                )
            }
            item {
                DButton(
                    if (busy) (if (empty) "Creating…" else "Cloning…") else if (empty) "Create repository" else "Clone and add",
                    ::submit,
                    Modifier.fillMaxWidth().padding(16.dp).testTag("repository-submit"),
                    enabled = ready,
                    large = true,
                    loading = busy,
                )
            }
        }
    }
}

package com.getdieter.addon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.getdieter.addon.core.AddonRules
import com.getdieter.addon.v1.ClaudeSignIn
import com.getdieter.addon.v1.ClaudeSlice
import com.getdieter.addon.v1.Command
import com.getdieter.addon.v1.EnrollGateway
import com.getdieter.addon.v1.Enrollment
import com.getdieter.addon.v1.GatewaySlice
import com.getdieter.addon.v1.SettingChange
import com.getdieter.addon.v1.Slice
import com.getdieter.addon.v1.Step
import com.getdieter.addon.v1.SubmitClaudeCode
import com.getdieter.addon.v1.TestSsh
import com.getdieter.addon.v1.Tone
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.navigation.*
import com.getdieter.mobile.store.MobileStore
import com.getdieter.web.openInNewTab

// ---- Machines: the add-on's own enrollment with the gateway, and its SSH key

@Composable
internal fun GatewaySection(store: MobileStore) {
    val g = rememberAddonSlice(Slice.SLICE_GATEWAY)?.gateway
    val actions = rememberAddonActions(store)
    var unenroll by remember { mutableStateOf(false) }
    Column(Modifier.testTag("addon-gateway")) {
        SectionHeader("Gateway of this add-on")
        when {
            g == null || g.loading -> StatusRow("", "Gateway status", actions::refresh)
            g.enrolled ->
                Group(listOf(g)) { _, position ->
                    ListRow(
                        g.title,
                        position = position,
                        subtitle = g.detail,
                        glyph = Glyph.GLOBE,
                        tile = toneColor(g.tone),
                        trailing = {
                            when (g.action) {
                                GatewaySlice.Action.ACTION_REENROLL ->
                                    DButton("Re-enroll", { unenroll = true }, kind = ButtonKind.PLAIN)
                                GatewaySlice.Action.ACTION_DEVICES ->
                                    DButton(
                                        "Devices",
                                        { store.showFrom(MobileRoute.Root(MobileTab.TOOLS), MobileRoute.Tool(ToolPage.DEVICES)) },
                                        kind = ButtonKind.PLAIN,
                                    )
                                else -> {}
                            }
                        },
                    )
                }
            g.machine_name.isEmpty() -> StatusRow(g.error, "Gateway status", actions::refresh)
            else -> NotEnrolled(g, actions)
        }
    }
    g?.enrollment?.takeIf { it.phase != Enrollment.Phase.PHASE_NONE }?.let { EnrollDialog(store, it, actions) }
    if (unenroll)
        ConfirmDialog(
            "Enroll again?",
            "Drop this add-on's enrollment so it can enroll again? It gets a new daemon ID: its projects stay and " +
                "are registered again under the new ID, its earlier conversations and schedules stay as read-only " +
                "history. You sign in with GitHub and approve the new pairing code.",
            "Drop enrollment",
            onConfirm = {
                unenroll = false
                actions.send(Command(unenroll_gateway = Step()))
            },
            onDismiss = { unenroll = false },
            destructive = true,
        )
}

@Composable
private fun NotEnrolled(g: GatewaySlice, actions: AddonActions) {
    var url by remember(g.gateway_url) { mutableStateOf(g.gateway_url) }
    val problem = AddonRules.gatewayUrlProblem(url)
    ContentCard(Modifier.padding(horizontal = ScreenMargin)) {
        Text(
            "Not enrolled: the Dieter apps cannot see this add-on. Enroll it with your gateway (the one the apps " +
                "use); it then appears as the machine \"${g.machine_name}\", and its code is approved under Devices.",
            style = type.body,
            color = palette.secondaryLabel,
        )
        Spacer(Modifier.height(12.dp))
        MobileTextField(
            url,
            { url = it },
            Modifier.fillMaxWidth().testTag("gateway-url").named("Gateway address"),
            readOnly = g.gateway_url_locked,
            singleLine = true,
            label = { Text("Gateway") },
            placeholder = { Text("https://gateway.example.net:4443") },
        )
        if (url.isNotBlank() && problem != null) Note(problem, Modifier.padding(top = 8.dp), palette.destructive)
        else if (!g.gateway_url_locked)
            Note(
                "Tip: set gateway_url (and gateway_host_ip for a Tailscale name this container cannot resolve) in the " +
                    "add-on options.",
                Modifier.padding(top = 8.dp),
            )
        Spacer(Modifier.height(12.dp))
        DButton(
            "Enroll",
            { actions.send(Command(enroll_gateway = EnrollGateway(url))) },
            Modifier.align(Alignment.End).testTag("gateway-enroll"),
            enabled = problem == null,
            glyph = Glyph.LINK,
        )
    }
}

@Composable
private fun EnrollDialog(store: MobileStore, e: Enrollment, actions: AddonActions) {
    val close = { actions.send(Command(close_enrollment = Step())) }
    AddonDialog(
        "Enroll with the gateway",
        "gateway-dialog",
        buttons = {
            DialogButton(
                when (e.phase) {
                    Enrollment.Phase.PHASE_DONE -> "Done"
                    Enrollment.Phase.PHASE_FAILED -> "Close"
                    else -> "Cancel"
                },
                close,
                tag = "gateway-cancel",
            )
        },
    ) {
        when (e.phase) {
            Enrollment.Phase.PHASE_WAITING -> {
                Text(
                    "1. Open the gateway's sign-in page and authorize this daemon with GitHub." +
                        if (e.code.isNotEmpty()) " If it asks for a code, enter ${e.code}." else "",
                    style = type.body,
                )
                DButton("Open sign-in page", { openInNewTab(e.sign_in_url) }, Modifier.fillMaxWidth(), glyph = Glyph.OPEN)
                SelectionContainer { Text(e.sign_in_url, style = type.monoSmall, color = palette.secondaryLabel) }
                DButton(
                    "Copy link",
                    { copyText(e.sign_in_url) { ok -> store.error.value = if (ok) "Link copied." else "Copy failed: select the link and copy it by hand." } },
                    Modifier.align(Alignment.End),
                    kind = ButtonKind.PLAIN,
                    glyph = Glyph.COPY,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(Modifier.size(16.dp))
                    Text("2. Waiting for the authorization… the daemon restarts afterwards.", style = type.footnote)
                }
            }
            Enrollment.Phase.PHASE_DONE -> Text(e.message, style = type.body)
            Enrollment.Phase.PHASE_FAILED -> Text(e.message, style = type.body, color = palette.destructive)
            else ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner()
                    Text("Contacting the gateway…", style = type.body)
                }
        }
    }
}

@Composable
internal fun SshKeySection(store: MobileStore) {
    val k = rememberAddonSlice(Slice.SLICE_SSH)?.ssh
    val actions = rememberAddonActions(store)
    var target by remember { mutableStateOf("") }
    Column(Modifier.testTag("addon-ssh")) {
        SectionHeader("SSH key of this add-on")
        when {
            k == null || k.loading || (k.error.isNotEmpty() && k.public_key.isEmpty()) -> StatusRow(k?.error.orEmpty(), "SSH key", actions::refresh)
            k.public_key.isEmpty() ->
                Group(listOf(k)) { _, position ->
                    ListRow("No key yet", position = position, subtitle = "The add-on generates one at its next start.", glyph = Glyph.KEY)
                }
            else ->
                ContentCard(Modifier.padding(horizontal = ScreenMargin)) {
                    Note("Fingerprint ${k.fingerprint}")
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer { Text(k.public_key, Modifier.testTag("ssh-key"), style = type.monoSmall) }
                    Spacer(Modifier.height(8.dp))
                    DButton(
                        "Copy key",
                        { copyText(k.public_key) { ok -> store.error.value = if (ok) "Public key copied." else "Copy failed: select the key and copy it by hand." } },
                        kind = ButtonKind.PLAIN,
                        glyph = Glyph.COPY,
                    )
                    Spacer(Modifier.height(8.dp))
                    MobileTextField(
                        target,
                        { target = it },
                        Modifier.fillMaxWidth().testTag("ssh-target").named("Test connection to user@host"),
                        singleLine = true,
                        label = { Text("Test connection") },
                        placeholder = { Text("user@host") },
                    )
                    Spacer(Modifier.height(10.dp))
                    val test = k.test
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DButton(
                            "Test",
                            { actions.send(Command(test_ssh = TestSsh(target))) },
                            Modifier.testTag("ssh-test"),
                            enabled = AddonRules.sshTargetProblem(target) == null && test?.running != true,
                            loading = test?.running == true,
                        )
                        if (test != null && !test.running)
                            Text(
                                test.message,
                                Modifier.weight(1f).testTag("ssh-result"),
                                style = type.footnote,
                                color = if (test.ok) palette.success else palette.destructive,
                            )
                    }
                }
        }
        SectionFooter(
            "Add the public key to ~/.ssh/authorized_keys on the computers Dieter should reach (Git remotes over " +
                "SSH, other machines). The private key stays in /data of the add-on."
        )
    }
}

// ---- Usage: the Claude Code login of the add-on's harness

private val CLAUDE = Color(0xFFD97757)

@Composable
internal fun ClaudeAccountSection(store: MobileStore) {
    val c = rememberAddonSlice(Slice.SLICE_CLAUDE)?.claude
    val actions = rememberAddonActions(store)
    Column(Modifier.testTag("addon-claude")) {
        SectionHeader("Claude Code account of this add-on")
        if (c == null || c.loading || c.title.isEmpty()) StatusRow(c?.error.orEmpty(), "Claude Code account", actions::refresh)
        else
            Group(listOf(c)) { _, position ->
                ListRow(
                    c.title,
                    position = position,
                    subtitle = c.detail,
                    subtitleMaxLines = 4,
                    glyph = Glyph.PERSON,
                    tile = if (c.tone == Tone.TONE_SUCCESS) CLAUDE else palette.secondaryLabel,
                    trailing = {
                        when (c.action) {
                            ClaudeSlice.Action.ACTION_SIGN_OUT ->
                                DButton("Sign out", { actions.send(Command(sign_out_of_claude = Step())) }, Modifier.testTag("claude-logout"), kind = ButtonKind.PLAIN)
                            ClaudeSlice.Action.ACTION_SIGN_IN ->
                                DButton("Sign in", { actions.send(Command(begin_claude_sign_in = Step())) }, Modifier.testTag("claude-login"))
                            else -> {}
                        }
                    },
                )
            }
    }
    c?.sign_in?.takeIf { it.phase != ClaudeSignIn.Phase.PHASE_NONE }?.let { ClaudeSignInDialog(it, actions) }
}

@Composable
private fun ClaudeSignInDialog(s: ClaudeSignIn, actions: AddonActions) {
    var code by remember { mutableStateOf("") }
    val close = { actions.send(Command(close_claude_sign_in = Step())) }
    AddonDialog(
        "Sign in to Claude",
        "claude-dialog",
        buttons = {
            Row {
                when (s.phase) {
                    ClaudeSignIn.Phase.PHASE_DONE -> DialogButton("Done", close, tag = "claude-cancel")
                    ClaudeSignIn.Phase.PHASE_FAILED -> {
                        DialogButton("Close", close, tag = "claude-cancel")
                        DialogButton("Try again", { actions.send(Command(begin_claude_sign_in = Step())) }, tag = "claude-retry")
                    }
                    else -> {
                        DialogButton("Cancel", close, tag = "claude-cancel")
                        if (s.phase == ClaudeSignIn.Phase.PHASE_CODE || s.phase == ClaudeSignIn.Phase.PHASE_SUBMITTING)
                            DialogButton(
                                if (s.phase == ClaudeSignIn.Phase.PHASE_SUBMITTING) "Signing in…" else "Sign in",
                                { actions.send(Command(submit_claude_code = SubmitClaudeCode(code))) },
                                enabled = code.isNotBlank() && s.phase == ClaudeSignIn.Phase.PHASE_CODE,
                                tag = "claude-submit",
                            )
                    }
                }
            }
        },
    ) {
        when (s.phase) {
            ClaudeSignIn.Phase.PHASE_CODE, ClaudeSignIn.Phase.PHASE_SUBMITTING -> {
                Text("1. Open the sign-in page and allow Claude Code. You get a code.", style = type.body)
                DButton("Open claude.com", { openInNewTab(s.sign_in_url) }, Modifier.fillMaxWidth().testTag("claude-open"), glyph = Glyph.OPEN)
                Text("2. Paste the code here.", style = type.body)
                MobileTextField(
                    code,
                    { code = it },
                    Modifier.fillMaxWidth().testTag("claude-code").named("Claude code"),
                    enabled = s.phase == ClaudeSignIn.Phase.PHASE_CODE,
                    singleLine = true,
                    label = { Text("Code") },
                )
            }
            ClaudeSignIn.Phase.PHASE_DONE -> Text(s.message, style = type.body)
            ClaudeSignIn.Phase.PHASE_FAILED -> Text(s.message, Modifier.testTag("claude-error"), style = type.body, color = palette.destructive)
            else ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner()
                    Text("Starting the sign-in…", style = type.body)
                }
        }
    }
}

// ---- Settings: what only the add-on decides

@Composable
internal fun AddonSettingsSection(store: MobileStore) {
    val s = rememberAddonSlice(Slice.SLICE_SETTINGS)?.settings
    val actions = rememberAddonActions(store)
    Column(Modifier.testTag("addon-settings")) {
        val model = s?.title_model
        val retention = s?.worktree_retention
        if (s == null || model == null || retention == null) {
            SectionHeader("Add-on")
            StatusRow(s?.error.orEmpty(), "Settings", actions::refresh)
            return@Column
        }
        SectionHeader("Task titles")
        Group(listOf(model)) { _, position ->
            PickerRow(
                "Title model",
                model.selected_label,
                model.options.map { it.id to it.label },
                model.selected,
                enabled = true,
                position = position,
                glyph = Glyph.WAND,
                tag = "settings-title-model",
            ) { actions.send(Command(change_setting = SettingChange(title_model = it))) }
        }
        SectionFooter(
            model.warning.ifEmpty {
                "Names tasks and chats created without a title. OpenAI: Dieter's auto-title. Ollama (HA): a local " +
                    "model through Home Assistant's AI Task; the task is saved under its first line and renamed a moment later."
            }
        )
        SectionHeader("Work folders")
        Group(listOf(retention)) { _, position ->
            PickerRow(
                "Keep finished work folders",
                retention.selected_label,
                retention.options.map { it.id to it.label },
                retention.selected,
                enabled = true,
                position = position,
                glyph = Glyph.FOLDER,
                tag = "settings-retention",
            ) { days -> days.toIntOrNull()?.let { actions.send(Command(change_setting = SettingChange(worktree_retention_days = it))) } }
        }
        SectionFooter(
            "Every task with its own worktree leaves a folder on the device. Once a task has been in Done or archived " +
                "and untouched for this long, its folder is removed, if its work is merged; the same for started tasks " +
                "of a Dreamfall Implementation board, wherever they sit. Unmerged work and running tasks are kept. " +
                "Checked every hour."
        )
    }
}

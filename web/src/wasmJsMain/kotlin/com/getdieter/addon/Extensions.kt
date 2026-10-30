package com.getdieter.addon

import com.getdieter.mobile.board.BoardScreen
import com.getdieter.mobile.design.Glyph
import com.getdieter.mobile.extensions.*
import com.getdieter.mobile.navigation.MobileRoute
import com.getdieter.mobile.navigation.ToolPage

/** The add-on's features in the shared UI (see `docs/PLAN.md`, decision D3). */
internal fun addonExtensions(): MobileExtensions =
    MobileExtensions(
        pages =
            listOf(
                ExtensionPage("add-repository", "Add repository", Glyph.FOLDER_ADD, 0xFF0A9EF5) { store, _ ->
                    AddRepositoryScreen(store, empty = false)
                },
                ExtensionPage("new-repository", "New repository", Glyph.FOLDER_ADD, 0xFF0A9EF5) { store, _ ->
                    AddRepositoryScreen(store, empty = true)
                },
                ExtensionPage("dreamfall", "Dreamfall", Glyph.SPARKLES, 0xFFAF52DE, ToolSection.WORKSPACE, { dreamfallWaiting() }) { store, id ->
                    if (id.isEmpty()) DreamfallListScreen(store) else DreamfallBoardScreen(store, id)
                },
                ExtensionPage("dreamfall-idea", "Idea", Glyph.SPARKLES, 0xFFAF52DE) { store, id -> IdeaScreen(store, id) },
                ExtensionPage("dreamfall-form", "Dreamfall settings", Glyph.SPARKLES, 0xFFAF52DE) { store, scope ->
                    DreamfallFormScreen(store, scope)
                },
                ExtensionPage("dreamfall-kanban", "Board", Glyph.BOARD, 0xFFAF52DE) { store, id -> BoardScreen(store, id) },
                ExtensionPage("agent-instructions", "Agent instructions", Glyph.CODE, 0xFFFF9F0A) { store, kind -> InstructionsScreen(store, kind) },
            ),
        toolSections =
            mapOf(
                ToolPage.MACHINES to
                    listOf(
                        ExtensionSection("gateway") { GatewaySection(it) },
                        ExtensionSection("ssh") { SshKeySection(it) },
                    ),
                ToolPage.USAGE to listOf(ExtensionSection("claude") { ClaudeAccountSection(it) }),
                ToolPage.SETTINGS to
                    listOf(
                        ExtensionSection("addon-settings") { AddonSettingsSection(it) },
                        ExtensionSection("addon-instructions") { InstructionsSection(it) },
                    ),
            ),
        inboxSections = listOf(ExtensionSection("questions") { WaitingForAnswerSection(it) }),
        conversationWidgets = listOf(ConversationWidget("questions") { store, cardId -> QuestionWidget(store, cardId) }),
        projectActions =
            listOf(
                ExtensionAction("add-repository", "Clone repository", Glyph.FOLDER_ADD) {
                    it.present(MobileRoute.Extension("add-repository"))
                },
                ExtensionAction("new-repository", "New empty repository", Glyph.FOLDER_ADD) {
                    it.present(MobileRoute.Extension("new-repository"))
                },
                ExtensionAction("new-dreamfall", "New Dreamfall board", Glyph.SPARKLES) {
                    it.present(MobileRoute.Extension("dreamfall-form", "new:${it.boards.selectedProject.value}"))
                },
            ),
        boardRenderer = dreamfallBoards(),
        conversationNamer = { rememberConversationNamer(it) },
    )

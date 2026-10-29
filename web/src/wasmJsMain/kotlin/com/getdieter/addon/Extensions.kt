package com.getdieter.addon

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
            ),
        toolSections =
            mapOf(
                ToolPage.MACHINES to
                    listOf(
                        ExtensionSection("gateway") { GatewaySection(it) },
                        ExtensionSection("ssh") { SshKeySection(it) },
                    ),
                ToolPage.USAGE to listOf(ExtensionSection("claude") { ClaudeAccountSection(it) }),
                ToolPage.SETTINGS to listOf(ExtensionSection("addon-settings") { AddonSettingsSection(it) }),
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
            ),
    )

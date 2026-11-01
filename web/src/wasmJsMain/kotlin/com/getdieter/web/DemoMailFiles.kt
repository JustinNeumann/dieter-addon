package com.getdieter.web

import com.getdieter.api.v1.FileDocument
import com.getdieter.api.v1.FileEntry
import com.getdieter.api.v1.MailFolder
import com.getdieter.client.v1.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Demo Mail and Files surfaces: a few agent messages with Markdown bodies and a small checkout. */
class DemoMailFiles {
    private val now = Clock.System.now()

    private fun millis(minutesAgo: Int) = (now - minutesAgo.minutes).toEpochMilliseconds()

    private class Message(
        val id: String,
        val subject: String,
        val sender: String,
        val detail: String,
        val initials: String,
        val minutesAgo: Int,
        val time: String,
        val body: String,
        var read: Boolean = false,
        var flagged: Boolean = false,
        /** The sender's checkout, which relative links in [body] resolve against. */
        val checkout: String = "",
    )

    private val messages =
        listOf(
            Message(
                "m-audit", "Dependency audit: 2 updates, 1 advisory", "Nightly dependency audit",
                "atlas-api · workstation · Claude", "DA", 35, "09:14",
                """
                # Dependency audit

                Two dependencies have newer releases and one has a security advisory. Nothing is
                exploitable in the service as configured, but the advisory affects the **sigstore**
                verification path, so it is worth updating this week.

                ## Updates

                | Package | Current | Latest | Notes |
                |---|---|---|---|
                | pgx | 5.7.1 | 5.7.2 | Connection pool fixes |
                | x/net | 0.32.0 | 0.33.0 | HTTP/2 header parsing |
                | cosign | 2.4.1 | 2.4.3 | Advisory GHSA-demo-0001 |

                ## Suggested change

                ```bash
                sed -i 's/cosign 2.4.1/cosign 2.4.3/' deploy/Dockerfile
                make test
                ```

                - Run the integration suite before the release
                - Bump the service revision
                - Mention the advisory in the changelog

                The release steps are in [the checklist](notes.txt) and [the changelog](docs/CHANGELOG.md);
                [the shared secrets](../secrets.txt) are outside the checkout.
                """.trimIndent(),
                checkout = "atlas-api-daemon-desktop",
            ),
            Message(
                "m-build", "Nightly build: index size and timings", "Nightly build",
                "atlas-api · workstation · Codex", "NB", 180, "07:02",
                """
                # Nightly build

                The search snapshot is **15.2 MB** (5.3 MB compressed). Build times stayed flat.

                ```mermaid
                flowchart LR
                  Postgres --> Indexer --> Snapshot --> S3
                ```

                ```vega-lite
                {"data": {"values": [{"day": "Mon", "s": 101}, {"day": "Tue", "s": 98}, {"day": "Wed", "s": 104}]},
                 "mark": "bar", "encoding": {"x": {"field": "day", "type": "nominal"}, "y": {"field": "s", "type": "quantitative"}}}
                ```

                The slowest step is tokenizing the archived webhook payloads.
                """.trimIndent() + "\n\n## Step log\n\n" +
                    (1..36).joinToString("\n") { "- Step $it finished in ${it * 3 % 17 + 2} s" } +
                    "\n\nThe last step uploads the tarball; its checksum ends in quokka.",
                read = true,
            ),
            Message(
                "m-ha", "Checkout LCP down to 1.6 s", "Lazy-load product images below the fold",
                "storefront · Home Assistant · Codex", "SF", 1500, "Mon",
                "Only the first row of `ProductGrid` gets `priority` now. Category LCP dropped from **2.8 s to 1.6 s**, performance score 96.",
                read = true,
                flagged = true,
            ),
        )
    private var box = MailBox.MAIL_BOX_INBOX
    private var selected: String? = null

    private fun ref(m: Message) = MailMessageRef(daemon_id = "daemon-desktop", id = m.id)

    private fun row(m: Message) =
        MailRow(
            ref = ref(m),
            sender = m.sender,
            sender_detail = m.detail,
            sender_style = MailSenderStyle.MAIL_SENDER_STYLE_CARD,
            harness = "claude-code",
            initials = m.initials,
            sender_key = m.sender,
            subject = m.subject,
            preview = m.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty().replace("**", ""),
            time_label = m.time,
            sent_at_millis = millis(m.minutesAgo),
            read = m.read,
            flagged = m.flagged,
            folder = MailFolder.MAIL_FOLDER_INBOX,
            selected = m.id == selected,
            accessibility_label = "${if (m.read) "" else "Unread. "}${m.sender}: ${m.subject}, ${m.time}",
        )

    fun mail(): MailSlice {
        val shown = messages.filter {
            when (box) {
                MailBox.MAIL_BOX_FLAGGED -> it.flagged
                MailBox.MAIL_BOX_UNREAD -> !it.read
                MailBox.MAIL_BOX_INBOX -> true
                else -> false
            }
        }
        val unread = messages.count { !it.read }
        val reader = messages.firstOrNull { it.id == selected }?.let { m ->
            MailReader(
                ref = ref(m), subject = m.subject, sender = m.sender, sender_detail = m.detail,
                sender_style = MailSenderStyle.MAIL_SENDER_STYLE_CARD, harness = "claude-code", initials = m.initials,
                sent_label = "Today ${m.time}", read = m.read, flagged = m.flagged, folder = MailFolder.MAIL_FOLDER_INBOX,
                body = m.body, can_open_conversation = false, open_conversation_unavailable = "Demo message",
                machine_name = "workstation", checkout_id = m.checkout,
            )
        }
        return MailSlice(
            boxes = listOf(
                MailBoxRow(MailBox.MAIL_BOX_INBOX, "Inbox", unread, messages.size, if (unread > 0) "$unread" else "", box == MailBox.MAIL_BOX_INBOX),
                MailBoxRow(MailBox.MAIL_BOX_FLAGGED, "Flagged", 0, messages.count { it.flagged }, "${messages.count { it.flagged }}", box == MailBox.MAIL_BOX_FLAGGED),
                MailBoxRow(MailBox.MAIL_BOX_UNREAD, "Unread", unread, unread, if (unread > 0) "$unread" else "", box == MailBox.MAIL_BOX_UNREAD),
                MailBoxRow(MailBox.MAIL_BOX_ARCHIVE, "Archive", 0, 0, "", box == MailBox.MAIL_BOX_ARCHIVE),
                MailBoxRow(MailBox.MAIL_BOX_TRASH, "Trash", 0, 0, "", box == MailBox.MAIL_BOX_TRASH),
            ),
            box = box,
            sections = listOf(
                MailSection("today", "Today", shown.filter { it.minutesAgo < 600 }.map(::row)),
                MailSection("this-week", "This week", shown.filter { it.minutesAgo >= 600 }.map(::row)),
            ).filter { it.rows.isNotEmpty() },
            count = shown.size,
            reader_ = reader,
            unread = unread,
            unread_label = if (unread > 0) "$unread" else "",
            empty_title = "No mail",
            empty_detail = "Agents write here when a schedule or a task reports back.",
        )
    }

    fun mailCommand(command: MailCommand) {
        command.select_box?.let { box = it }
        command.select?.let { ref ->
            selected = ref.id.ifEmpty { null }
            messages.firstOrNull { it.id == ref.id }?.read = true
        }
        command.change?.let { change ->
            change.refs.forEach { r ->
                messages.firstOrNull { it.id == r.id }?.let { m ->
                    change.read?.let { m.read = it }
                    change.flagged?.let { m.flagged = it }
                }
            }
        }
        if (command.mark_all_read != null) messages.forEach { it.read = true }
    }

    // Files ---------------------------------------------------------------------------------

    private val tree: Map<String, String> =
        mapOf(
            "README.md" to """
                # atlas-api

                The public API behind Atlas: search, webhooks and billing for every workspace, with an OpenAPI
                explorer compiled for the browser.

                ## Build

                ```bash
                go test -race ./...
                make run
                ```

                - Requests pass through request-ID, logging, auth and rate-limit middleware
                - Errors return RFC 9457 `application/problem+json` documents
                - Webhooks sign every delivery with HMAC-SHA256
                """.trimIndent(),
            "cmd/atlas/main.go" to """
                package main

                import (
                    "log/slog"
                    "net/http"
                    "os"

                    "example.com/atlas/internal/httpapi"
                )

                func main() {
                    logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
                    http.ListenAndServe(":8080", httpapi.NewRouter(httpapi.Dependencies{Logger: logger}))
                }
                """.trimIndent(),
            "notes.txt" to "Release checklist\n1. integration suite green\n2. bump the revision\n3. tag and push\n",
            "docs/CHANGELOG.md" to "# Changelog\n\n" +
                (40 downTo 1).joinToString("\n\n") {
                    "## 2.1.$it\n\n- Search index and webhook delivery improvements for 2.1.$it\n- OpenAPI schema updates for release $it"
                } + "\n\nThe first release was called quokka.",
        )
    private val defaultTarget =
        FilesTarget(daemon_id = "daemon-desktop", project_id = "p-addon", checkout_id = "atlas-api-daemon-desktop")
    // Listed from the start: the demo project has two checkouts, so Files never binds one itself.
    private var files = FilesSlice(target = defaultTarget, entries = entries(""))

    private fun entries(directory: String): List<FileEntry> {
        val prefix = if (directory.isEmpty()) "" else "$directory/"
        val names = tree.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val folders = names.filter { '/' in it }.map { it.substringBefore('/') }.distinct()
            .map { FileEntry(name = it, path = prefix + it, kind = "directory") }
        val plain = names.filter { '/' !in it }
            .map { FileEntry(name = it, path = prefix + it, kind = "file", size = tree.getValue(prefix + it).length.toLong()) }
        return folders + plain
    }

    fun files(): FilesSlice = files

    fun filesCommand(command: FilesCommand) {
        command.bind?.let { files = files.copy(target = it) }
        val target = files.target ?: defaultTarget
        fun list(directory: String) {
            files = files.copy(target = target, directory = directory, entries = entries(directory), listing_loading = false, document_unchanged = false)
        }
        command.load?.let { list(files.directory) }
        command.navigate?.let { list(it.path.trim('/')) }
        command.parent?.let { list(files.directory.substringBeforeLast('/', "")) }
        command.open_?.let { path ->
            val p = path.path.trim('/')
            val content = tree[p]
            files =
                files.copy(
                    target = target,
                    selected_path = p,
                    document = content?.let {
                        FileDocument(path = p, name = p.substringAfterLast('/'), size = it.length.toLong(), revision = "r1",
                            mime_type = if (p.endsWith(".md")) "text/markdown" else "text/plain", content = it)
                    },
                    document_unchanged = false,
                    document_error = if (content == null) "No such file in the demo checkout." else "",
                    document_key = "demo:$p",
                    language_name = if (p.endsWith(".go")) "Go" else if (p.endsWith(".md")) "Markdown" else "Plain text",
                    type_label = if (p.endsWith(".md")) "Markdown" else "text/plain",
                )
        }
    }
}

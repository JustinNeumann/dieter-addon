package com.getdieter.addon

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getdieter.addon.v1.AntigravityGroup
import com.getdieter.addon.v1.Slice
import com.getdieter.core.quotas.Quotas
import com.getdieter.mobile.app.*
import com.getdieter.mobile.design.*
import com.getdieter.mobile.store.MobileStore
import kotlin.time.Clock
import kotlinx.coroutines.delay

/**
 * Antigravity's quota groups, which the add-on reads with agy (the daemon reports no Antigravity usage): one card
 * per group, drawn like the provider accounts above it. Hidden while the add-on has no Antigravity credentials.
 */
@Composable
internal fun AntigravitySection(store: MobileStore) {
    val slice = rememberAddonSlice(Slice.SLICE_ANTIGRAVITY)?.antigravity ?: return
    if (!slice.configured && slice.groups.isEmpty()) return
    val now by produceState(Clock.System.now()) {
        while (true) {
            delay(60_000)
            value = Clock.System.now()
        }
    }
    Column(Modifier.testTag("addon-antigravity")) {
        SectionHeader(
            "Antigravity",
            prominent = true,
            trailing = {
                Text(
                    if (slice.groups.size == 1) "1 quota group" else "${slice.groups.size} quota groups",
                    style = type.footnote,
                    color = palette.secondaryLabel,
                )
            },
        )
        if (slice.groups.isEmpty())
            ContentCard(Modifier.padding(horizontal = ScreenMargin, vertical = 5.dp)) {
                Text(
                    "agy has not reported its quota yet; the add-on asks every few minutes.",
                    style = type.footnote,
                    color = palette.secondaryLabel,
                )
            }
        slice.groups.forEach { GroupCard(it, now) }
    }
}

@Composable
private fun GroupCard(group: AntigravityGroup, now: kotlin.time.Instant) {
    val low = group.remaining < 20
    ContentCard(Modifier.padding(horizontal = ScreenMargin, vertical = 5.dp).testTag("antigravity-${group.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = type.headline, color = palette.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (group.models.isNotEmpty())
                    Text(group.models.joinToString(", "), style = type.footnote, color = palette.secondaryLabel)
            }
            Text(
                "${group.remaining}%",
                style = type.title2,
                color = if (low) palette.warning.readableOn(palette.cell) else palette.label,
            )
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(group.remaining / 100f, color = if (low) palette.warning else palette.success)
        Spacer(Modifier.height(10.dp))
        group.windows.forEach { window ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(window.name, Modifier.weight(1f), style = type.subheadline, color = palette.label)
                Text(
                    listOfNotNull(
                            "${window.remaining}% left",
                            window.resets_at.takeIf { it.isNotEmpty() }?.let { Quotas.resetText(it, now, fine = false) },
                        )
                        .joinToString(" · "),
                    style = type.subheadline,
                    color = palette.secondaryLabel,
                )
            }
        }
    }
}

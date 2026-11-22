package com.getdieter.operator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The quota the board shows: the daemon's `quota list` and Antigravity's /quota groups, matched to its models. */
class UsageTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun theDaemonsQuotaIsOneItemPerAccount() {
        val q = obj(
            """{"groups": [{"provider": "PROVIDER_QUOTA_PROVIDER_CLAUDE", "accounts": [{"plan": "max", "displayEmail": "me@example.com", "availability": "QUOTA_AVAILABILITY_AVAILABLE",
                "refreshedAt": "2026-10-30T08:00:00Z", "windows": [{"id": "five_hour", "label": "5h", "usedPercent": 12, "remainingPercent": 88, "resetsAt": "2026-10-30T10:00:00Z"}]}]},
                {"provider": "PROVIDER_QUOTA_PROVIDER_CODEX", "accounts": [{"availability": "QUOTA_AVAILABILITY_EXHAUSTED"}]}]}""",
        )
        val out = Usage.normQuota(q)
        assertEquals(listOf("claude", "codex"), out.map { it.field("provider") })
        assertEquals(obj("""{"id": "five_hour", "label": "5h", "used": 12, "remaining": 88, "resets_at": "2026-10-30T10:00:00Z"}"""), out[0].objs("windows").single())
        assertEquals(listOf(true, false), out.map { it["available"].truthy() })
        assertEquals(JsonNull, out[1]["plan"])
        assertEquals(emptyList(), Usage.normQuota(null))
    }

    private val groups = listOf(
        obj(
            """{"name": "Gemini Flash", "description": "Models within this group: Gemini Flash, Gemini Flash Lite.", "buckets": [
                {"window": "5h", "remaining_fraction": 0.625, "reset_time": "2026-10-30T12:00:00Z"}, {"window": "weekly", "remaining_fraction": 0.125}]}""",
        ),
        obj("""{"name": "Claude", "description": "", "buckets": [{"id": "claude_week", "window": "weekly", "remaining_fraction": 1.5}, {"window": "5h"}]}"""),
    )

    @Test
    fun antigravitysGroupsBecomeWindows() {
        val w = Usage.agyWindows(groups)
        assertEquals(listOf("gemini_flash_5h", "gemini_flash_week", "claude_week"), w.map { it.field("id") })
        assertEquals(listOf("Gemini Flash · 5h", "Gemini Flash · Week", "Claude · Week"), w.map { it.field("label") })
        assertEquals(listOf(62, 12, 100), w.map { it.field("remaining").toInt() }, "halves to the even neighbour, at most 100")
        assertEquals(listOf("Gemini Flash", "Gemini Flash Lite"), w[0].strings("models"))
        assertEquals(listOf("Claude"), w[2].strings("models"))
    }

    @Test
    fun eachAntigravityModelGetsItsTightestWindow() {
        val currentGroups = listOf(
            obj(
                """{"name": "Gemini Models", "description": "Models within this group: Gemini 4 Neon, Gemini 4 Carbon, Gemini 4 Argon, Gemini 4 Hydrogen, Gemini 3.8 Flash", "buckets": [
                    {"window": "5h", "remaining_fraction": 0.87, "reset_time": "2026-11-21T10:23:28Z"}, {"window": "weekly", "remaining_fraction": 0.85, "reset_time": "2026-11-25T04:00:18Z"}]}""",
            ),
        )
        val quota = listOf(obj("""{"provider": "antigravity", "windows": ${Usage.agyWindows(groups + currentGroups)}}"""))
        val harnesses = listOf(
            obj(
                """{"id": "antigravity", "models": [
                    {"id": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash (High)"},
                    {"id": "gemini-4-neon-xhigh", "name": "Gemini 4 Neon (XHigh)"},
                    {"id": "gemini-4-carbon-high", "name": "Gemini 4 Carbon (High)"},
                    {"id": "gemini-4-argon-medium", "name": "Gemini 4 Argon (Medium)"},
                    {"id": "gemini-4-hydrogen-xhigh", "name": "Gemini 4 Hydrogen (XHigh)"},
                    {"id": "gpt-oss", "name": "GPT OSS"}
                ]}""",
            ),
            obj("""{"id": "claude-code", "models": [{"id": "opus"}]}"""),
        )
        val out = Usage.agyModelQuota(harnesses, quota)
        val models = out[0].objs("models")
        val flash = models[0]
        val oss = models.last()
        assertEquals("Gemini Flash · Week", flash.obj("quota")!!.field("label"))
        assertEquals("12", flash.obj("quota")!!.field("remaining"))
        for (m in models.subList(1, 5)) {
            assertEquals("Gemini Models · Week", m.obj("quota")!!.field("label"), m.field("id"))
            assertEquals("85", m.obj("quota")!!.field("remaining"), m.field("id"))
        }
        assertEquals(JsonNull, oss["quota"])
        assertEquals(harnesses[1], out[1], "other harnesses stay as they are")
        assertNull(Usage.agyModelQuota(harnesses, emptyList())[0].objs("models")[0].obj("quota"))
    }
}

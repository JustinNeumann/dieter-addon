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
        val quota = listOf(obj("""{"provider": "antigravity", "windows": ${Usage.agyWindows(groups)}}"""))
        val harnesses = listOf(
            obj("""{"id": "antigravity", "models": [{"id": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash (High)"}, {"id": "gpt-oss", "name": "GPT OSS"}]}"""),
            obj("""{"id": "claude-code", "models": [{"id": "opus"}]}"""),
        )
        val out = Usage.agyModelQuota(harnesses, quota)
        val (flash, oss) = out[0].objs("models")
        assertEquals("Gemini Flash · Week", flash.obj("quota")!!.field("label"))
        assertEquals("12", flash.obj("quota")!!.field("remaining"))
        assertEquals(JsonNull, oss["quota"])
        assertEquals(harnesses[1], out[1], "other harnesses stay as they are")
        assertNull(Usage.agyModelQuota(harnesses, emptyList())[0].objs("models")[0].obj("quota"))
    }
}

package com.getdieter.operator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive

class TurnCallersTest {
    private val token = "dtt1:c_1:t_1:" + "A".repeat(43)

    @Test
    fun theDaemonDecides() {
        val asked = mutableListOf<String>()
        fun callers(exit: Int, out: String, err: String = "") = TurnCallers { asked += it; Command(exit, out, err) }
        val allowed = callers(0, """{"cardId":"c_1","projectId":"p","homeAssistantAccess":true}""").resolve(token)
        assertEquals(JsonPrimitive(true), allowed["home_assistant_access"])
        assertEquals(JsonPrimitive("c_1"), allowed["card_id"])
        assertEquals(JsonPrimitive(false), callers(0, """{"cardId":"c_1"}""").resolve(token)["home_assistant_access"], "protojson leaves false out")
        val finished = callers(1, "", "permission_denied: the agent turn token is invalid or its turn has finished").resolve(token)
        assertEquals(JsonPrimitive(false), finished["home_assistant_access"])
        assertEquals(JsonPrimitive("permission_denied: the agent turn token is invalid or its turn has finished"), finished["reason"])
        assertEquals(listOf(token, token, token), asked)
        for (bad in listOf("", "supervisor-token", "dtt1:c_1:t_1:short", "dtt1:c 1:t:" + "A".repeat(43), "$token\nX=1"))
            assertEquals(JsonPrimitive(false), callers(0, "{}").resolve(bad)["home_assistant_access"], "never reaches the CLI: $bad")
        assertEquals(3, asked.size)
    }
}

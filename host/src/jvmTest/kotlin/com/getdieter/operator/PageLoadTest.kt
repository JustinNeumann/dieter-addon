package com.getdieter.operator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class PageLoadTest {
    private fun line(json: String) = PageLoad.line(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun aStartThatDownloadedTheWasmNamesTheBiggestFiles() {
        val app = "Mozilla/5.0 (Linux; Android 16; Pixel; wv) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36 Home Assistant/2026.10.1 (Android 16; Pixel)"
        assertEquals(
            "page: started in 4.3 s · Home Assistant app on Android · Nabu Casa · 9.5 Mbit/s · " +
                "downloaded 5.6 MB in 2 files (658820….wasm.gz 3.2 MB, d4100e….wasm.gz 2.4 MB) · 1 checked for changes · 1 from the cache",
            line("""{"ms": 4321, "client": "$app", "where": "nabu", "downlink": 9.5, "files": [
                {"name": "index.html", "transfer": 310, "size": 9800},
                {"name": "658820f72b4617efb380.wasm.gz", "transfer": 3180400, "size": 3180000},
                {"name": "d4100e8f922a77490058.wasm.gz", "transfer": 2410400, "size": 2410000},
                {"name": "dieter.7e69853c06081fc1.js", "transfer": 0, "size": 520000}]}"""),
        )
    }

    @Test
    fun aCachedStartAndAMalformedReport() {
        assertEquals(
            "page: started in 1.6 s · Chrome on Windows · local network · nothing downloaded · 2 from the cache",
            line("""{"ms": 1612, "client": "Mozilla/5.0 (Windows NT 10.0) Chrome/141.0 Safari/537.36", "where": "local",
                "files": [{"name": "composeResources/x/files/a.ttf", "transfer": 0, "size": 9}, {"name": "b.wasm.gz", "transfer": 0, "size": 9}]}"""),
        )
        assertEquals(
            "page: started in 600.0 s · browser · nothing downloaded · 1 from the cache",
            line("""{"ms": 99999999, "where": "elsewhere", "downlink": "fast", "files": [{"name": "../<b>\n", "transfer": -5}, 7, "x"]}"""),
        )
    }
}

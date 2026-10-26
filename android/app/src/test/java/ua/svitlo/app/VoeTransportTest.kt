package ua.svitlo.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ua.svitlo.app.data.VoeClient
import ua.svitlo.app.domain.*

class VoeTransportTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private fun ajax(html: String) = JSONArray().put(JSONObject().put("command", "insert").put("data", html)).toString()
    private val address = SavedAddress("a", "Дім", AddressItem("510100000", "Вінниця"), AddressItem("1652", "Соборна"), AddressItem("26590", "1"))

    @Test fun `one batch shares form GET and submits actual IDs directly to origin`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("schedule.html")))
            val response = ajax(fixture("schedule.html"))
            repeat(2) { server.enqueue(MockResponse().setBody(response)) }
            server.start()
            val batch = VoeClient(server.url("/")).Batch()
            batch.schedule(address)
            batch.schedule(address.copy(id = "b"))
            assertEquals(3, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
            val post = server.takeRequest()
            assertEquals("POST", post.method)
            assertEquals("/disconnection/detailed?ajax_form=1&_wrapper_format=drupal_ajax", post.path)
            val body = post.body.readUtf8()
            assertTrue(body.contains("city_id=510100000"))
            assertTrue(body.contains("street_id=1652"))
            assertTrue(body.contains("house_id=26590"))
        }
    }

    @Test fun `autocomplete caches repeated queries without a second network call`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("cities.json")))
            server.start()
            val client = VoeClient(server.url("/"))
            assertEquals(client.suggestions("city", "Він"), client.suggestions("city", "Він"))
            assertEquals(1, server.requestCount)
        }
    }
}

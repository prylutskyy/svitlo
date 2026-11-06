package ua.svitlo.app
import org.junit.Test
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
class QueueOnlyTransportTest {
 @Test fun oldAddressRequiresQueueWithoutAnyNetworkRequest() = runBlocking {
  MockWebServer().use { server ->
   val client=VoeClient(server.url("/"))
   val old=SavedAddress("old","Дім",AddressItem("1","Вінниця"),AddressItem("2","Вулиця"),AddressItem("3","3"))
   try { client.Batch().schedule(old);fail("Queue must be selected") }
   catch(e:VoeFormatException) { assertTrue(e.message.orEmpty().contains("оператора та чергу")) }
   assertEquals(0,server.requestCount)
  }
 }
}

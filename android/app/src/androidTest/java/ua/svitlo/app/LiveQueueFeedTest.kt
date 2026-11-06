package ua.svitlo.app
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.*
import java.time.Instant
@RunWith(AndroidJUnit4::class)
class LiveQueueFeedTest {
 @Test fun officialRegionalJournalWorksDirectlyOnAndroid()=runBlocking {
  val c=VoeClient();assertTrue(c.emergency.regions().any{it.id=="33"})
  val g=c.emergency.snapshot("33",Instant.now());val v=c.emergency.snapshot("23",Instant.now())
  assertTrue(g.checkedAt>0);assertTrue(v.checkedAt>0)
  val before=c.requestCount.get();c.emergency.snapshot("33",Instant.now());assertEquals(before,c.requestCount.get())
  val gop=g.records.filter{it.addresses.contains("ГОПЧИЦЯ",true)}
  val emergency=v.records.filter{it.kind.contains("аварій",true)}
  val file=java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"live-official-journal.txt")
  file.writeText("OFFICIAL VOE OK: region 33 rows=${g.records.size}, Gopchytsia records=${gop.size}; region 23 emergency rows=${emergency.size}; requests=${c.requestCount.get()}; shared regional cache; no Promin\n")
 }
}

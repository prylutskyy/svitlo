package ua.svitlo.app.data

data class FeedProvider(val id: String, val label: String, val url: String)
object FeedProviders {
    private val order = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("uk-UA"))
    val all = listOf(
        FeedProvider("Cherkasyoblenergo","Черкаська · Черкасиобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Cherkasyoblenergo.json"),
        FeedProvider("Chernihivoblenergo","Чернігівська · Чернігівобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Chernihivoblenergo.json"),
        FeedProvider("Kharkivoblenerho","Харківська · Харківобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Kharkivoblenerho.json"),
        FeedProvider("Khmelnytskoblenerho","Хмельницька · Хмельницькобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Khmelnytskoblenerho.json"),
        FeedProvider("Lvivoblenerho","Львівська · Львівобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Lvivoblenerho.json"),
        FeedProvider("Poltavaoblenergo","Полтавська · Полтаваобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Poltavaoblenergo.json"),
        FeedProvider("Prykarpattiaoblenerho","Івано-Франківська · Прикарпаттяобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Prykarpattiaoblenerho.json"),
        FeedProvider("Rivneoblenergo","Рівненська · Рівнеобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Rivneoblenergo.json"),
        FeedProvider("Ternopiloblenerho","Тернопільська · Тернопільобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Ternopiloblenerho.json"),
        FeedProvider("Zakarpattiaoblenerho","Закарпатська · Закарпаттяобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zakarpattiaoblenerho.json"),
        FeedProvider("Zaporizhzhiaoblenergo","Запорізька · Запоріжжяобленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zaporizhzhiaoblenergo.json"),
        FeedProvider("Zhytomyroblenergo","Житомирська · Житомиробленерго","https://raw.githubusercontent.com/yaroslav2901/OE_OUTAGE_DATA/main/data/Zhytomyroblenergo.json"),
        FeedProvider("ua/dnipro","Дніпропетровська · ДТЕК","https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/dnipro.json"),
        FeedProvider("ua/kyiv-region","Київська область · ДТЕК","https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/kyiv-region.json"),
        FeedProvider("ua/kyiv","Київ · ДТЕК","https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/kyiv.json"),
        FeedProvider("ua/odesa","Одеська · ДТЕК","https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/odesa.json"),
        FeedProvider("voe/Vinnytsiaoblenerho","Вінницька · Вінницяобленерго","https://raw.githubusercontent.com/vn-progr/gpv-voe-vinnytsia/main/data/Vinnytsiaoblenerho.json"),
        FeedProvider("cek/cek","Дніпропетровська · ЦЕК","https://raw.githubusercontent.com/szheliab/DNIPRO_CEK_PARSER/main/out/cek.json")
    ).sortedWith { a,b -> order.compare(a.label,b.label) }
    fun get(id: String) = all.singleOrNull { it.id == id } ?: throw VoeFormatException("Невідомий оператор")
}

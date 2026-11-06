package ua.svitlo.app.data

/** Catalog pinned to Promin's published APPS on 2026-11-04; Kyiv includes Kyivshchyna. */
data class ProminCity(val id: String,val name: String,val base: String,val path: String,val queueOnly: Boolean = false)
object ProminCities {
    val all = listOf(
        ProminCity("lviv","Львів","/","/lviv",false),
        ProminCity("ternopil","Тернопіль","/ternopil/","/ternopil",false),
        ProminCity("uzhhorod","Ужгород","/uzhhorod/","/uzhhorod",false),
        ProminCity("kropyvnytskyi","Кропивницький","/kropyvnytskyi/","/kropyvnytskyi",false),
        ProminCity("poltava","Полтава","/poltava/","/poltava",false),
        ProminCity("mykolaiv","Миколаїв","/mykolaiv/","/mykolaiv",false),
        ProminCity("ivano","Івано-Франківськ","/ivano-frankivsk/","/ivano-frankivsk",false),
        ProminCity("chernivtsi","Чернівці","/chernivtsi/","/chernivtsi",false),
        ProminCity("zaporizhzhia","Запоріжжя","/zaporizhzhia/","/zaporizhzhia",false),
        ProminCity("cherkasy","Черкаси","/cherkasy/","/cherkasy",false),
        ProminCity("lutsk","Луцьк","/lutsk/","/lutsk",false),
        ProminCity("zhytomyr","Житомир","/zhytomyr/","/zhytomyr",false),
        ProminCity("vinnytsia","Вінниця","/vinnytsia/","/vinnytsia",false),
        ProminCity("khmelnytskyi","Хмельницький","/khmelnytskyi/","/khmelnytskyi",false),
        ProminCity("kyiv","Київ та Київщина","/kyiv/","/kyiv",false),
        ProminCity("odesa","Одеса","/odesa/","/odesa",false),
        ProminCity("dnipro","Дніпро","/dnipro/","/dnipro",false),
        ProminCity("kharkiv","Харків","/kharkiv/","/kharkiv",true),
        ProminCity("rivne","Рівне","/rivne/","/rivne",true)
    ).sortedWith { a,b -> java.text.Collator.getInstance(java.util.Locale.forLanguageTag("uk")).compare(a.name,b.name) }
    fun get(id: String) = all.singleOrNull { it.id==id } ?: throw VoeFormatException("Невідоме місто Променя")
}

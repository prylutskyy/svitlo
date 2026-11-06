@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package ua.svitlo.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ua.svitlo.app.data.*
import ua.svitlo.app.domain.SavedAddress

@Composable internal fun AddAddressPage(vm: MainViewModel,onDismiss: () -> Unit) = QueueFeedPage(vm,null,onDismiss)

/** Visible scrollbar stays present whenever a list contains more than fits on screen. */
private fun Modifier.scrollThumb(scroll: ScrollState,color: Color) = drawWithContent {
    drawContent()
    if (scroll.maxValue > 0) {
        val track=(size.height-16.dp.toPx()).coerceAtLeast(0f)
        val thumb=(track*size.height/(size.height+scroll.maxValue)).coerceAtLeast(24.dp.toPx()).coerceAtMost(track)
        drawRoundRect(color.copy(alpha=.16f),Offset(size.width-6.dp.toPx(),8.dp.toPx()),Size(3.dp.toPx(),track),CornerRadius(2.dp.toPx()))
        drawRoundRect(color.copy(alpha=.65f),Offset(size.width-6.dp.toPx(),8.dp.toPx()+(track-thumb)*scroll.value/scroll.maxValue),Size(3.dp.toPx(),thumb),CornerRadius(2.dp.toPx()))
    }
}

@Composable private fun SourceSelect(label: String,value: String,selectedId: String,placeholder: String,enabled: Boolean,tag: String,
    options: List<Pair<String,String>>,onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val scroll=rememberScrollState()
    val colors=MaterialTheme.colorScheme
    LaunchedEffect(enabled) { if (!enabled) expanded=false }
    ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={ if(enabled) expanded=it }) {
        OutlinedTextField(value=value,onValueChange={},readOnly=true,enabled=enabled,
            label={ Text(label) },placeholder={ Text(placeholder) },maxLines=2,
            trailingIcon={ ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded) },
            shape=MaterialTheme.shapes.medium,
            modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag(tag))
        ExposedDropdownMenu(expanded=expanded,onDismissRequest={ expanded=false },scrollState=scroll,
            modifier=Modifier.heightIn(max=320.dp).scrollThumb(scroll,colors.onSurfaceVariant)) {
            options.forEach { (id,text) ->
                DropdownMenuItem(text={ Text(text,style=MaterialTheme.typography.bodyLarge) },
                    modifier=Modifier.semantics { selected=id==selectedId },
                    trailingIcon=if(id==selectedId) { { Icon(SvitloIcons.Check,"Вибрано",tint=colors.primary) } } else null,
                    contentPadding=PaddingValues(start=16.dp,end=24.dp),
                    onClick={ expanded=false; onSelect(id) })
            }
        }
    }
}

/** Searchable address pickers keep long regional catalogs usable without rendering thousands of rows. */
@Composable private fun SearchSelect(label: String,value: String,enabled: Boolean,tag: String,options: List<Pair<String,String>>,onSelect: (String)->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val matches=remember(options,query) { options.filter { it.second.contains(query,true) }.take(60) }
    val scroll=rememberScrollState()
    LaunchedEffect(enabled) { if(!enabled) expanded=false }
    ExposedDropdownMenuBox(expanded,onExpandedChange={ if(enabled) { expanded=it;query="" } }) {
        OutlinedTextField(if(expanded) query else value,{ query=it;expanded=true },enabled=enabled,
            label={Text(label)},placeholder={Text("Введіть для пошуку")},singleLine=true,
            trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},shape=MaterialTheme.shapes.medium,
            modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).testTag(tag))
        ExposedDropdownMenu(expanded,{expanded=false},scrollState=scroll,modifier=Modifier.heightIn(max=280.dp).scrollThumb(scroll,MaterialTheme.colorScheme.onSurfaceVariant)) {
            matches.forEach { (id,name) -> DropdownMenuItem(text={Text(name)},onClick={expanded=false;query="";onSelect(id);focus.clearFocus();keyboard?.hide()}) }
            if(matches.isEmpty()) DropdownMenuItem(text={Text("Нічого не знайдено")},onClick={},enabled=false)
            if(matches.size==60) DropdownMenuItem(text={Text("Уточніть пошук, щоб звузити список")},onClick={},enabled=false)
        }
    }
}

@Composable internal fun ProminSourcePage(vm: MainViewModel,existing: SavedAddress?,onDismiss: () -> Unit,
    loadConfig: suspend (String)->ProminConfig = vm::prominConfig,
    loadStreets: suspend (String)->List<ProminStreet> = vm::prominStreets,
    loadHouses: suspend (String,ProminStreet)->List<ProminHouse> = vm::prominHouses,
    save: suspend (ProminSelection,String?)->Unit = vm::savePromin) {
    var cityId by rememberSaveable(existing?.id) { mutableStateOf(existing?.prominCity ?: "vinnytsia") }
    var queueMode by rememberSaveable(existing?.id) { mutableStateOf(existing?.street?.id=="queue") }
    var group by rememberSaveable(existing?.id) { mutableStateOf(existing?.feedGroup.orEmpty().takeIf { existing?.prominCity!=null }.orEmpty()) }
    var title by rememberSaveable(existing?.id) { mutableStateOf("") }
    var town by rememberSaveable(existing?.id) { mutableStateOf("") }
    var street by remember { mutableStateOf<ProminStreet?>(null) }
    var house by remember { mutableStateOf<ProminHouse?>(null) }
    var config by remember { mutableStateOf<ProminConfig?>(null) }
    var streets by remember { mutableStateOf<List<ProminStreet>>(emptyList()) }
    var houses by remember { mutableStateOf<List<ProminHouse>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var streetError by remember { mutableStateOf<String?>(null) }
    var houseError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var houseLoading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val city=ProminCities.get(cityId)
    val byQueue=city.queueOnly || (queueMode && config?.addressSchedule!=true)
    val scope=rememberCoroutineScope()
    val scroll=rememberScrollState()
    val colors=MaterialTheme.colorScheme
    fun townKey(s: ProminStreet)="${s.cityId}|${s.city}"
    LaunchedEffect(cityId,retry) {
        loading=true;error=null;streetError=null;config=null;streets=emptyList()
        try {
            config=loadConfig(cityId)
            if(!city.queueOnly) {
                try {streets=loadStreets(cityId)}
                catch(e:CancellationException){throw e}
                catch(e:Exception){streetError=e.message ?: "Не вдалося завантажити вулиці"}
            }
            if(existing?.prominCity==cityId && street==null && existing.street.id!="queue") {
                street=streets.firstOrNull { it.id==existing.street.id && it.cityId==existing.city.id }
                town=street?.let(::townKey).orEmpty()
            }
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {error=e.message ?: "Не вдалося отримати дані Променя"}
        finally {loading=false}
    }
    LaunchedEffect(cityId,street,retry) {
        houses=emptyList();house=null;houseError=null
        val selectedStreet=street ?: return@LaunchedEffect
        houseLoading=true
        try {
            houses=loadHouses(cityId,selectedStreet)
            if(existing?.prominCity==cityId && existing.street.id==selectedStreet.id) house=houses.firstOrNull { it.number==existing.house.name }
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {houseError=e.message ?: "Не вдалося отримати будинки"}
        finally {houseLoading=false}
    }
    FullScreenPage(if(existing==null) "Нове місце" else "Місце на Промені",onBack=onDismiss) {
        Column(Modifier.fillMaxSize().imePadding().semantics { testTagsAsResourceId=true }) {
            Column(Modifier.weight(1f).fillMaxWidth().scrollThumb(scroll,colors.onSurfaceVariant).verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("Дані з Променя",style=MaterialTheme.typography.headlineSmall)
                Text("Оберіть будинок для адресних повідомлень або свою чергу для погодинного плану.",style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant)
                SourceSelect("Місто / регіон",city.name,cityId,"Оберіть місто",!saving,"provider-selector",ProminCities.all.map { it.id to it.name }) {
                    cityId=it;town="";street=null;house=null;group="";queueMode=false
                }
                if(!city.queueOnly && config?.addressSchedule!=true) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=!queueMode,onClick={queueMode=false;error=null},label={Text("Адреса")},enabled=!saving)
                    FilterChip(selected=queueMode,onClick={queueMode=true;error=null},label={Text("Черга")},enabled=!saving)
                }
                if(byQueue) SourceSelect("Черга",config?.groupName(group).takeUnless { group.isEmpty() }.orEmpty(),group,"Оберіть чергу",!loading && !saving && config!=null,"queue-selector",
                    config?.groups.orEmpty().map { it to config!!.groupName(it) }) {group=it;error=null}
                else {
                    val towns=remember(streets) { streets.distinctBy { townKey(it) }.map { townKey(it) to it.city }.sortedBy { it.second } }
                    SearchSelect("Населений пункт",towns.firstOrNull {it.first==town}?.second.orEmpty(),!loading && !saving,"town-selector",towns) {town=it;street=null;house=null;error=null}
                    SearchSelect("Вулиця",street?.name.orEmpty(),town.isNotBlank() && !loading && !saving,"street-selector",
                        streets.filter {townKey(it)==town}.map {it.id to it.name}) { id ->street=streets.first {it.id==id && townKey(it)==town} }
                    SearchSelect("Будинок",house?.number.orEmpty(),street!=null && !houseLoading && !saving,"house-selector",houses.map {it.number to it.number}) {number ->house=houses.first {it.number==number};error=null}
                    if(houseLoading) Text("Завантажуємо будинки…",style=MaterialTheme.typography.bodySmall)
                    house?.let { h -> Text(if(config?.addressSchedule==true) "Відключення перевірятимуться за адресою."
                        else if(h.group.isBlank()) "Чергу не визначено. Доступні адресні повідомлення."
                        else if(h.estimated) "Черга приблизна. Показуємо лише адресні повідомлення."
                        else config?.groupName(h.group).orEmpty(),style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant) }
                }
                if(loading) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Text("Завантажуємо з Променя…")}
                (error ?: if(byQueue) null else (streetError ?: houseError))?.let { message -> Surface(color=colors.errorContainer,shape=MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {Text(message,color=colors.onErrorContainer);TextButton(onClick={retry++},enabled=!saving){Text("Повторити")}}
                } }
                if(config!=null) Text(when {
                    config!!.imageSchedule -> "Графік цього міста доступний на Промені зображенням. Адресні події показуємо окремо."
                    !config!!.outages -> "Промінь не надає адресних аварій для цього міста. Доступний погодинний план."
                    byQueue -> "Аварії стосуються конкретних будинків. Для їх відображення виберіть адресу."
                    else -> "Час відновлення орієнтовний. Відсутність повідомлень не гарантує наявності світла."
                },style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                if(existing==null) OutlinedTextField(title,{title=it.take(60)},label={Text("Назва · необов’язково")},placeholder={Text("Дім, робота…")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("place-name"))
            }
            HorizontalDivider()
            Button(onClick={scope.launch {
                saving=true;error=null
                try {save(ProminSelection(cityId,street.takeUnless {byQueue},house.takeUnless {byQueue},group,title),existing?.id);onDismiss()}
                catch(e:CancellationException){throw e}
                catch(e:Exception){error=e.message ?: "Не вдалося зберегти";scroll.animateScrollTo(scroll.maxValue)}
                finally{saving=false}
            }},enabled=!saving && !loading && error==null && config!=null && (if(byQueue) group in config!!.groups else house!=null && houseError==null && streetError==null),modifier=Modifier.fillMaxWidth().padding(24.dp).heightIn(min=56.dp)) {
                Text(if(saving) "Зберігаємо…" else "Зберегти")
            }
        }
    }
}

@Composable internal fun QueueFeedPage(vm:MainViewModel,existing:SavedAddress?,onDismiss:()->Unit,
    loadGroups:suspend(String)->List<String> = vm::feedGroups,
    loadRegions:suspend()->List<ua.svitlo.app.domain.AddressItem> = vm::emergencyRegions,
    save:suspend(String,String,String,String?,ua.svitlo.app.domain.VoeIncidentAddress?)->Unit = vm::saveFeed) {
    val oldProvider=existing?.feedId?.takeIf {id->FeedProviders.all.any{it.id==id}}
    var providerId by rememberSaveable(existing?.id){mutableStateOf(oldProvider ?: "voe/Vinnytsiaoblenerho")}
    var group by rememberSaveable(existing?.id){mutableStateOf(existing?.feedGroup.takeIf{oldProvider!=null}.orEmpty())}
    var title by rememberSaveable{mutableStateOf("")}
    var enabled by rememberSaveable{mutableStateOf(existing?.voeIncidentAddress!=null)}
    var region by rememberSaveable{mutableStateOf(existing?.voeIncidentAddress?.regionId.orEmpty())}
    var city by rememberSaveable{mutableStateOf(existing?.voeIncidentAddress?.city.orEmpty())}
    var street by rememberSaveable{mutableStateOf(existing?.voeIncidentAddress?.street.orEmpty())}
    var house by rememberSaveable{mutableStateOf(existing?.voeIncidentAddress?.house.orEmpty())}
    var groups by remember{mutableStateOf<List<String>>(emptyList())}
    var regions by remember{mutableStateOf<List<ua.svitlo.app.domain.AddressItem>>(emptyList())}
    var loading by remember{mutableStateOf(true)}
    var saving by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var regionError by remember{mutableStateOf<String?>(null)}
    var retry by remember{mutableIntStateOf(0)}
    val scope=rememberCoroutineScope();val scroll=rememberScrollState();val colors=MaterialTheme.colorScheme
    LaunchedEffect(providerId,retry) {
        loading=true;error=null;groups=emptyList()
        try{groups=loadGroups(providerId);if(group !in groups)group=""}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=e.message ?: "Не вдалося завантажити графік"}
        finally{loading=false}
    }
    LaunchedEffect(enabled,providerId,retry) {
        regionError=null
        if(enabled && providerId=="voe/Vinnytsiaoblenerho") try{regions=loadRegions()}
        catch(e:CancellationException){throw e}
        catch(e:Exception){regionError=e.message ?: "Журнал VOE недоступний"}
    }
    FullScreenPage(if(existing==null)"Нове місце" else "Джерело та аварії",onBack=onDismiss) {
        Column(Modifier.fillMaxSize().imePadding().semantics{testTagsAsResourceId=true}) {
            Column(Modifier.weight(1f).fillMaxWidth().scrollThumb(scroll,colors.onSurfaceVariant).verticalScroll(scroll).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("Погодинний графік",style=MaterialTheme.typography.headlineSmall)
                SourceSelect("Область та оператор",FeedProviders.get(providerId).label,providerId,"Оберіть оператора",!saving,"provider-selector",FeedProviders.all.map{it.id to it.label}){providerId=it;group="";enabled=false}
                SourceSelect("Черга",group.removePrefix("GPV"),group,"Оберіть чергу",!loading && !saving,"queue-selector",groups.map{it to "Черга ${it.removePrefix("GPV")}"}){group=it;error=null}
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(providerId=="voe/Vinnytsiaoblenerho") {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)){Text("Перевіряти адресні відключення",style=MaterialTheme.typography.titleMedium);Text("Офіційний журнал Вінницяобленерго",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)}
                        Switch(enabled,{enabled=it;error=null},enabled=!saving,modifier=Modifier.testTag("incident-switch"))
                    }
                    if(enabled) {
                        Text("Черга задає план. Для аварій та інших адресних подій потрібен будинок; перевірка охоплює також села області.",style=MaterialTheme.typography.bodySmall)
                        SourceSelect("Район у журналі VOE",regions.firstOrNull{it.id==region}?.name.orEmpty(),region,"Оберіть район",!saving && regions.isNotEmpty(),"incident-region",regions.map{it.id to it.name}){region=it;error=null}
                        OutlinedTextField(city,{city=it.take(100);error=null},label={Text("Населений пункт")},placeholder={Text("Наприклад, Гопчиця")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("incident-city"))
                        OutlinedTextField(street,{street=it.take(120);error=null},label={Text("Вулиця / провулок")},placeholder={Text("Наприклад, вулиця Центральна")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("incident-street"))
                        OutlinedTextField(house,{house=it.take(30);error=null},label={Text("Будинок")},placeholder={Text("Наприклад, 12 або 81/2")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("incident-house"))
                        Text("Оберіть структурний район зі списку VOE; він може мати стару назву. Назви вулиць вказуйте разом із типом: вулиця, провулок, проспект.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                    }
                } else Text("Для цього оператора поки доступний лише графік за чергою.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                (error ?: if(enabled) regionError else null)?.let{message->Surface(color=colors.errorContainer,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(16.dp)){Text(message);TextButton(onClick={retry++}){Text("Повторити")}}}}
                if(existing==null)OutlinedTextField(title,{title=it.take(60)},label={Text("Назва · необов’язково")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("place-name"))
                Text("Час завершення подій може бути орієнтовним. Відсутність повідомлень не підтверджує наявність напруги.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
            }
            HorizontalDivider()
            Button(onClick={scope.launch{saving=true;error=null;try{
                val address=if(enabled)ua.svitlo.app.domain.VoeIncidentAddress(region,city.trim(),street.trim(),house.trim()) else null
                save(providerId,group,title,existing?.id,address);onDismiss()
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "Не вдалося зберегти";scroll.animateScrollTo(scroll.maxValue)}finally{saving=false}}},
                enabled=!saving && !loading && group in groups && (!enabled || (region in regions.map{it.id} && city.isNotBlank() && street.isNotBlank() && house.isNotBlank() && regionError==null)),
                modifier=Modifier.fillMaxWidth().padding(24.dp).heightIn(min=56.dp)){Text(if(saving)"Зберігаємо…" else "Зберегти")}
        }
    }
}

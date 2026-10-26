@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package ua.svitlo.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.svitlo.app.domain.AddressItem

@Composable
internal fun AddAddressPage(vm: MainViewModel, onDismiss: () -> Unit) {
    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var cityId by rememberSaveable { mutableStateOf("") }
    var streetId by rememberSaveable { mutableStateOf("") }
    var houseId by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var street by rememberSaveable { mutableStateOf("") }
    var house by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val lookup by vm.suggestions.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val duplicate = state.addresses.any { it.id == "$cityId:$streetId:$houseId" }
    val focus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(currentStep) { focus.requestFocus() }
    LaunchedEffect(Unit) {
        if (currentStep < 3 && query.isNotBlank()) vm.search(listOf("city", "street", "house")[currentStep], query,
            when (currentStep) { 1 -> cityId; 2 -> streetId; else -> null })
    }
    val form: @Composable (Int, Boolean) -> Unit = { step, preview ->
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(top = 16.dp,bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Крок ${step+1} із 4 · ${listOf("Місто", "Вулиця", "Будинок", "Назва")[step]}",
                style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { index -> Surface(Modifier.weight(1f).height(4.dp),shape = MaterialTheme.shapes.small,
                    color = if (index <= step) colors.primary else colors.outlineVariant) {} }
            }
            if (step > 0) Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer) {
                Text(listOf(city.takeIf { cityId.isNotEmpty() }, street.takeIf { streetId.isNotEmpty() },
                    house.takeIf { houseId.isNotEmpty() }).filterNotNull().joinToString(", "),
                    modifier = Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(listOf("Де перевіряємо світло?", "Яка вулиця?", "Номер будинку", "Як назвати адресу?")[step],
                style = MaterialTheme.typography.titleLarge)
            val value = listOf(city, street, house, title)[step]
            OutlinedTextField(value, { text ->
                val input = text.take(if (step == 3) 60 else 128)
                when (step) {
                    0 -> { city = input; cityId = ""; streetId = ""; houseId = ""; street = ""; house = "" }
                    1 -> { street = input; streetId = ""; houseId = ""; house = "" }
                    2 -> { house = input; houseId = "" }
                    3 -> title = input
                }
                if (step < 3) {
                    query = input
                    vm.search(listOf("city", "street", "house")[step], input,
                        when (step) { 1 -> cityId; 2 -> streetId; else -> null })
                }
            }, modifier = Modifier.fillMaxWidth().then(if (preview) Modifier else Modifier.focusRequester(focus)), singleLine = true,
                readOnly = preview,
                label = { Text(listOf("Населений пункт", "Вулиця", "Будинок", "Назва")[step]) },
                placeholder = { Text(listOf("Наприклад, Вінниця", "Почніть вводити назву", "Наприклад, 9", "Наприклад, Дім")[step]) },
                shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
            if (step < 3) {
                val level = listOf("city", "street", "house")[step]
                val parent = when (step) { 1 -> cityId; 2 -> streetId; else -> null }
                val enough = query.trim().length >= if (step == 2) 1 else 2
                val matching = lookup.request == Lookup(level, parent, query)
                when {
                    !enough -> Text("Введіть ${if (step == 2) "номер будинку" else "щонайменше 2 літери"}.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    !matching || lookup.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(36.dp)); Text("Шукаємо адресу…")
                    }
                    lookup.error != null -> Text(lookup.error!!, color = colors.error)
                    lookup.items.isEmpty() -> Text("Нічого не знайдено. Спробуйте іншу частину назви.", color = colors.onSurfaceVariant)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        lookup.items.forEach { item ->
                            Surface(onClick = {
                                when (step) {
                                    0 -> { cityId = item.id; city = item.name }
                                    1 -> { streetId = item.id; street = item.name }
                                    2 -> { houseId = item.id; house = item.name }
                                }
                                currentStep++; query = ""; vm.clearSearch()
                            }, shape = MaterialTheme.shapes.small, color = colors.surfaceContainer) {
                                ListItem(headlineContent = { Text(item.name) },
                                    leadingContent = { Icon(SvitloIcons.Home, null) },
                                    colors = ListItemDefaults.colors(containerColor = colors.surfaceContainer))
                            }
                        }
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Дім", "Батьки", "Робота").forEach { name ->
                        SuggestionChip(onClick = { title = name }, label = { Text(name) })
                    }
                }
                if (duplicate) Text("Ця адреса вже є у вашому списку.", color = colors.error)
                Button(onClick = {
                    vm.add(title, AddressItem(cityId, city), AddressItem(streetId, street), AddressItem(houseId, house))
                    onDismiss()
                }, enabled = title.isNotBlank() && !duplicate, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(SvitloIcons.Check, null); Spacer(Modifier.width(8.dp)); Text("Зберегти адресу")
                }
            }
        }
    }
    FullScreenPage("Нова адреса",onBack = {
        if (currentStep > 0) { currentStep--; query = ""; vm.clearSearch() } else onDismiss()
    }, previousContent = if (currentStep > 0) { { form(currentStep-1,true) } } else null) {
        form(currentStep,false)
    }
}

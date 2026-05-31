package com.doselfurioso.musvisto.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.doselfurioso.musvisto.model.GameSettings

private val GAME_GREEN = Color(0xFF006A4E)
private val BUTTON_GREEN = Color(0xFF6A994E)
private val CARD_DARK = Color.Black.copy(alpha = 0.30f)
private const val ACTION_WIDTH_FRACTION = 0.8f
private const val TRAIT_MAX = 100

/**
 * Pantalla de selección de mesa (#34/#36): el usuario edita su nombre y asigna
 * personajes del [CharacterRoster] a los 4 asientos (Tú, Pareja, Rival Izq.,
 * Rival Der.). Un asiento queda "activo"; al tocar un personaje se asigna a ese
 * asiento (con intercambio para no repetir). "Comenzar" guarda y arranca partida.
 *
 * En Fase B el arquetipo de cada personaje es informativo (barras de rasgos); su
 * efecto en el juego llega en Fase C. Reutiliza el patrón visual de OptionsScreen.
 */
@Composable
fun CharacterSetupScreen(navController: NavController, viewModel: MainMenuViewModel) {
    val settings by viewModel.settings.collectAsState()
    var activeSlot by remember { mutableStateOf(TableSlot.PARTNER) }
    val activeCharId = characterIdFor(settings, activeSlot)
    // Callbacks estables → la fila de asientos y la rejilla se saltan la
    // recomposición cuando solo cambia el nombre tecleado.
    val onActivate: (TableSlot) -> Unit = remember { { activeSlot = it } }
    val onPickCharacter: (String) -> Unit =
        remember(activeSlot) { { id -> viewModel.assignCharacter(activeSlot, id) } }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(modifier = Modifier.fillMaxSize(), color = GAME_GREEN) {}

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Elige tu mesa", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            NameField(settings.humanName, viewModel::setHumanName)
            Spacer(Modifier.height(20.dp))

            TableSlotsRow(
                humanId = settings.humanCharacterId,
                partnerId = settings.partnerCharacterId,
                rivalLeftId = settings.rivalLeftCharacterId,
                rivalRightId = settings.rivalRightCharacterId,
                activeSlot = activeSlot,
                onActivate = onActivate
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Toca un asiento y elige un personaje",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))

            RosterGrid(activeCharId, onPickCharacter)
            Spacer(Modifier.height(8.dp))

            TraitPanel(activeSlot, CharacterRoster.byId(activeCharId))
            Spacer(Modifier.height(24.dp))

            SetupActions(
                onStart = {
                    viewModel.commitSettings()
                    viewModel.startNewGame()
                    navController.navigate("game_screen") { popUpTo("main_menu") }
                },
                onBack = {
                    viewModel.commitSettings()
                    navController.popBackStack()
                }
            )
        }
    }
}

private fun characterIdFor(settings: GameSettings, slot: TableSlot): String = when (slot) {
    TableSlot.HUMAN -> settings.humanCharacterId
    TableSlot.PARTNER -> settings.partnerCharacterId
    TableSlot.RIVAL_LEFT -> settings.rivalLeftCharacterId
    TableSlot.RIVAL_RIGHT -> settings.rivalRightCharacterId
}

@Composable
private fun NameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onChange,
        singleLine = true,
        label = { Text("Tu nombre") },
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = CARD_DARK,
            unfocusedContainerColor = CARD_DARK,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color.White.copy(alpha = 0.7f),
            focusedIndicatorColor = BUTTON_GREEN,
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.4f),
            cursorColor = Color.White
        )
    )
}

@Composable
private fun TableSlotsRow(
    humanId: String,
    partnerId: String,
    rivalLeftId: String,
    rivalRightId: String,
    activeSlot: TableSlot,
    onActivate: (TableSlot) -> Unit
) {
    // Ids estables (String) por asiento → la fila se salta la recomposición al
    // teclear el nombre. El asiento activo se resalta; tocar uno lo activa.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        SlotChip(TableSlot.HUMAN, "Tú", humanId, activeSlot, onActivate)
        SlotChip(TableSlot.PARTNER, "Pareja", partnerId, activeSlot, onActivate)
        SlotChip(TableSlot.RIVAL_LEFT, "Rival Izq.", rivalLeftId, activeSlot, onActivate)
        SlotChip(TableSlot.RIVAL_RIGHT, "Rival Der.", rivalRightId, activeSlot, onActivate)
    }
}

@Composable
private fun SlotChip(
    slot: TableSlot,
    label: String,
    characterId: String,
    activeSlot: TableSlot,
    onActivate: (TableSlot) -> Unit
) {
    val isActive = slot == activeSlot
    val character = CharacterRoster.byId(characterId)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) BUTTON_GREEN else CARD_DARK)
            .clickable { onActivate(slot) }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .width(72.dp)
    ) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Image(
            painter = painterResource(character.avatarResId),
            contentDescription = character.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            character.name,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RosterGrid(activeCharId: String, onPick: (String) -> Unit) {
    CharacterRoster.all.chunked(3).forEach { rowChars ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            rowChars.forEach { character ->
                CharacterCard(
                    character = character,
                    selected = character.id == activeCharId,
                    onClick = { onPick(character.id) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun CharacterCard(character: Character, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CARD_DARK)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(8.dp)
            .width(96.dp)
    ) {
        Image(
            painter = painterResource(character.avatarResId),
            contentDescription = character.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            character.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            character.archetype.displayName,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TraitPanel(slot: TableSlot, character: Character) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CARD_DARK)
            .padding(16.dp)
    ) {
        Text(
            "${character.name} · ${character.archetype.displayName}",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (slot == TableSlot.HUMAN) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Es tu avatar: juegas a mano, su estilo no se aplica.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        CharacterRoster.TRAIT_AXES.forEachIndexed { i, axis ->
            TraitBar(axis, character.traits.getOrElse(i) { 0 })
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TraitBar(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0, TRAIT_MAX) / TRAIT_MAX.toFloat())
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(BUTTON_GREEN)
            )
        }
    }
}

@Composable
private fun SetupActions(onStart: () -> Unit, onBack: () -> Unit) {
    Button(
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth(ACTION_WIDTH_FRACTION)
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BUTTON_GREEN, contentColor = Color.White)
    ) {
        Text("Comenzar", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onBack,
        modifier = Modifier
            .fillMaxWidth(ACTION_WIDTH_FRACTION)
            .height(46.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CARD_DARK, contentColor = Color.White)
    ) {
        Text("Volver", fontSize = 16.sp)
    }
}

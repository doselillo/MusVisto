package com.doselfurioso.musvisto.debug

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.DebugScenario
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit

/** Orden y etiquetas de jugadores tal como los crea GameViewModel.defaultPlayers(). */
private val PLAYER_SLOTS = listOf(
    "p1" to "Tú",
    "p3" to "Pareja",
    "p2" to "Rival Der.",
    "p4" to "Rival Izq."
)

private fun suitColor(suit: Suit): Color = when (suit) {
    Suit.OROS -> Color(0xFFFFC107)
    Suit.COPAS -> Color(0xFFE53935)
    Suit.ESPADAS -> Color(0xFF42A5F5)
    Suit.BASTOS -> Color(0xFF66BB6A)
}

private fun rankLabel(rank: Rank): String = when (rank) {
    Rank.AS -> "A"; Rank.DOS -> "2"; Rank.TRES -> "3"; Rank.CUATRO -> "4"
    Rank.CINCO -> "5"; Rank.SEIS -> "6"; Rank.SIETE -> "7"; Rank.SOTA -> "S"
    Rank.CABALLO -> "C"; Rank.REY -> "R"
}

/**
 * Editor de escenarios in-app (solo build debug).
 *
 * Permite construir las 4 manos carta a carta sin recompilar: evita cartas
 * repetidas, elige mano y `startAtMus`, y guarda/juega el escenario. `initial`
 * != null edita uno existente (la edición se identifica por nombre).
 */
@Composable
fun ScenarioEditor(
    initial: DebugScenario?,
    onSave: (DebugScenario) -> Unit,
    onPlay: (DebugScenario) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var manoId by remember { mutableStateOf(initial?.manoId ?: "p1") }
    var startAtMus by remember { mutableStateOf(initial?.startAtMus ?: false) }
    // #29 vacas: marcador y chicos iniciales para testear finales de chico/vaca.
    var teamAScore by remember { mutableStateOf(initial?.teamAScore ?: 0) }
    var teamBScore by remember { mutableStateOf(initial?.teamBScore ?: 0) }
    var chicosA by remember { mutableStateOf(initial?.chicosWonA ?: 0) }
    var chicosB by remember { mutableStateOf(initial?.chicosWonB ?: 0) }

    // hands: por jugador, 4 huecos nullable. Se reemplaza el mapa entero en
    // cada edición para que Compose recomponga (sin estado mutable anidado).
    var hands by remember {
        mutableStateOf(
            PLAYER_SLOTS.associate { (id, _) ->
                val h = initial?.hands?.get(id) ?: emptyList()
                id to List(4) { i -> h.getOrNull(i) }
            }
        )
    }
    var activeSlot by remember { mutableStateOf<Pair<String, Int>?>("p1" to 0) }

    val usedCards = hands.values.flatten().filterNotNull().toSet()

    fun firstEmptySlot(): Pair<String, Int>? {
        for ((id, _) in PLAYER_SLOTS) {
            val idx = hands[id]!!.indexOfFirst { it == null }
            if (idx >= 0) return id to idx
        }
        return null
    }

    fun setSlot(target: Pair<String, Int>, card: Card?) {
        hands = hands.toMutableMap().apply {
            this[target.first] = this[target.first]!!.toMutableList().apply {
                this[target.second] = card
            }
        }
    }

    fun onCardTap(card: Card) {
        // Carta ya usada: si es la del hueco activo, la quita; si no, ignora.
        if (card in usedCards) {
            val slot = activeSlot ?: return
            if (hands[slot.first]!![slot.second] == card) setSlot(slot, null)
            return
        }
        val slot = activeSlot ?: firstEmptySlot() ?: return
        setSlot(slot, card)
        activeSlot = firstEmptySlot()
    }

    val complete = hands.values.all { row -> row.all { it != null } }
    val canSave = name.isNotBlank() && complete

    fun build(): DebugScenario = DebugScenario(
        name = name.trim(),
        hands = hands.mapValues { (_, row) -> row.filterNotNull() },
        manoId = manoId,
        startAtMus = startAtMus,
        teamAScore = teamAScore,
        teamBScore = teamBScore,
        chicosWonA = chicosA,
        chicosWonB = chicosB
    )

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101418))
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (initial == null) "Nuevo escenario" else "Editar escenario",
                    color = Color(0xFFFFD24A),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                // --- Nombre ---
                Text("Nombre", color = Color.Gray, fontSize = 11.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E2630), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFFD24A)),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (name.isEmpty()) {
                                Text("p. ej. Duples reyes vs cincos", color = Color(0xFF5A6470), fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                }

                // --- Mano + arranque ---
                Text("Mano (reparte y abre)", color = Color.Gray, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PLAYER_SLOTS.forEach { (id, label) ->
                        Chip(
                            text = label,
                            selected = manoId == id,
                            onClick = { manoId = id },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { startAtMus = !startAtMus }
                        .padding(vertical = 2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(
                                if (startAtMus) Color(0xFF43A047) else Color(0xFF424242),
                                RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (startAtMus) Text("✓", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Arrancar en MUS (probar corte/descarte). Si no, aterriza en GRANDE.",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }

                // --- Marcador inicial (#29 vacas) ---
                Text("Marcador inicial (vacas)", color = Color.Gray, fontSize = 11.sp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF18202A), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NumberStepper("Tantos Nosotros", teamAScore, 0, 40) { teamAScore = it }
                    NumberStepper("Tantos Ellos", teamBScore, 0, 40) { teamBScore = it }
                    NumberStepper("Chicos Nosotros", chicosA, 0, 4) { chicosA = it }
                    NumberStepper("Chicos Ellos", chicosB, 0, 4) { chicosB = it }
                }

                // --- Manos ---
                PLAYER_SLOTS.forEach { (id, label) ->
                    val row = hands[id]!!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF18202A), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$label ($id)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "Vaciar",
                                color = Color(0xFFEF9A9A),
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .clickable {
                                        hands = hands.toMutableMap()
                                            .apply { this[id] = List(4) { null } }
                                        activeSlot = id to 0
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEachIndexed { idx, card ->
                                val isActive = activeSlot == (id to idx)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .background(
                                            Color(0xFF0E1318),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .border(
                                            width = if (isActive) 2.dp else 1.dp,
                                            color = if (isActive) Color(0xFFFFD24A) else Color(0xFF33404D),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable { activeSlot = id to idx },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (card != null) {
                                        Text(
                                            rankLabel(card.rank),
                                            color = suitColor(card.suit),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    } else {
                                        Text("·", color = Color(0xFF44505C), fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // --- Baraja ---
                Text(
                    "Baraja — toca para asignar al hueco activo",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF18202A), RoundedCornerShape(8.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Suit.values().forEach { suit ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Rank.values().forEach { rank ->
                                val card = Card(suit, rank)
                                val used = card in usedCards
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                        .background(
                                            if (used) Color(0xFF0C0F12) else Color(0xFF222C36),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable { onCardTap(card) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        rankLabel(rank),
                                        color = if (used) Color(0xFF3A4450) else suitColor(suit),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                if (!canSave) {
                    Text(
                        text = when {
                            name.isBlank() -> "Falta el nombre."
                            !complete -> "Cada jugador necesita 4 cartas."
                            else -> ""
                        },
                        color = Color(0xFFEF9A9A),
                        fontSize = 11.sp
                    )
                }

                // --- Acciones ---
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                ) {
                    ActionButton("Cancelar", Color(0xFF424242), Modifier.weight(1f)) { onCancel() }
                    ActionButton(
                        "Guardar",
                        if (canSave) Color(0xFF1565C0) else Color(0xFF2A3640),
                        Modifier.weight(1f),
                        enabled = canSave
                    ) { onSave(build()) }
                    ActionButton(
                        "Jugar",
                        if (complete) Color(0xFF43A047) else Color(0xFF2A3640),
                        Modifier.weight(1f),
                        enabled = complete
                    ) { onPlay(build()) }
                }
            }
        }
    }
}

@Composable
private fun NumberStepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        StepBtn("−") { if (value > min) onChange(value - 1) }
        Text(
            "$value",
            color = Color(0xFFFFD24A),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(36.dp)
        )
        StepBtn("+") { if (value < max) onChange(value + 1) }
    }
}

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Color(0xFF2A3640), RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                if (selected) Color(0xFFFFD24A) else Color(0xFF2A3640),
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Color(0xFF101418) else Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(color, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

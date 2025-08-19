package com.doselfurioso.musvisto.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.doselfurioso.musvisto.R
import com.doselfurioso.musvisto.model.Card
import com.doselfurioso.musvisto.model.Rank
import com.doselfurioso.musvisto.model.Suit

@Composable
fun cardToPainter(card: Card): Painter {
    val resourceId = when (card.suit) {
        Suit.OROS -> when (card.rank) {
            Rank.AS -> R.drawable.oros_as
            Rank.DOS -> R.drawable.oros_dos
            Rank.TRES -> R.drawable.oros_tres
            Rank.CUATRO -> R.drawable.oros_cuatro
            Rank.CINCO -> R.drawable.oros_cinco
            Rank.SEIS -> R.drawable.oros_seis
            Rank.SIETE -> R.drawable.oros_siete
            Rank.SOTA -> R.drawable.oros_sota
            Rank.CABALLO -> R.drawable.oros_caballo
            Rank.REY -> R.drawable.oros_rey
        }
        Suit.COPAS -> when (card.rank) {
            Rank.AS -> R.drawable.copas_as
            Rank.DOS -> R.drawable.copas_dos
            Rank.TRES -> R.drawable.copas_tres
            Rank.CUATRO -> R.drawable.copas_cuatro
            Rank.CINCO -> R.drawable.copas_cinco
            Rank.SEIS -> R.drawable.copas_seis
            Rank.SIETE -> R.drawable.copas_siete
            Rank.SOTA -> R.drawable.copas_sota
            Rank.CABALLO -> R.drawable.copas_caballo
            Rank.REY -> R.drawable.copas_rey
        }
        Suit.ESPADAS -> when (card.rank) {
            Rank.AS -> R.drawable.espadas_as
            Rank.DOS -> R.drawable.espadas_dos
            Rank.TRES -> R.drawable.espadas_tres
            Rank.CUATRO -> R.drawable.espadas_cuatro
            Rank.CINCO -> R.drawable.espadas_cinco
            Rank.SEIS -> R.drawable.espadas_seis
            Rank.SIETE -> R.drawable.espadas_siete
            Rank.SOTA -> R.drawable.espadas_sota
            Rank.CABALLO -> R.drawable.espadas_caballo
            Rank.REY -> R.drawable.espadas_rey
        }
        Suit.BASTOS -> when (card.rank) {
            Rank.AS -> R.drawable.bastos_as
            Rank.DOS -> R.drawable.bastos_dos
            Rank.TRES -> R.drawable.bastos_tres
            Rank.CUATRO -> R.drawable.bastos_cuatro
            Rank.CINCO -> R.drawable.bastos_cinco
            Rank.SEIS -> R.drawable.bastos_seis
            Rank.SIETE -> R.drawable.bastos_siete
            Rank.SOTA -> R.drawable.bastos_sota
            Rank.CABALLO -> R.drawable.bastos_caballo
            Rank.REY -> R.drawable.bastos_rey
        }
    }
    return painterResource(id = resourceId)
}
package com.mauro.wgbg

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * The cameos seat [i] can show (0.3.5): the theme's first, then the ones for anyone, then the other themes'. A cameo
 * another seat shows is dimmed with that seat's name; picking it swaps the two. A bot can go back to its name's cameo;
 * you to your initial or a photo from this phone ([onPhoto] opens the gallery).
 */
@Composable
internal fun CameoPicker(i: Int, setup: GameSetup, rings: List<Color>, onPick: (String?) -> Unit, onInitial: () -> Unit,
                         onPhoto: () -> Unit, onDismiss: () -> Unit) {
    val now = setup.seatCameos
    val picked = setup.seats[i].cameo
    val order = Cameos.all.sortedBy { c -> if (c.theme == setup.theme) 0 else if (c.theme == null) 1 else 2 }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).brassEdgedWood().padding(horizontal = 14.dp, vertical = 12.dp).testTag("cameo-picker"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (i == 0) "Your picture" else "Seat ${i + 1}'s picture", style = Wood.title(18.sp))
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tile = Modifier.height(32.dp)
                    if (i == 0) {
                        Choice("Your initial", picked == null && setup.photo == null, "cameo-initial", tile, radius = 8.dp, size = 13.sp, onClick = onInitial)
                        Choice("Your photo…", picked == null && setup.photo != null, "cameo-photo", tile, radius = 8.dp, size = 13.sp, onClick = onPhoto)
                    } else Choice("Match the name", picked == null, "cameo-match", tile, radius = 8.dp, size = 13.sp) { onPick(null) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in order) {
                        val holder = now.indexOf(c.id).takeIf { it >= 0 && it != i }
                        Column(
                            Modifier.width(72.dp)
                                .background(if (now[i] == c.id && picked != null) Wood.BRASS.copy(alpha = 0.35f) else Color(0x47000000), RoundedCornerShape(8.dp))
                                .clickable(role = Role.RadioButton) { onPick(c.id) }.padding(vertical = 5.dp).testTag("cameo-${c.id}"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            PhotoToken(null, c.label, holder?.let(rings::get) ?: rings[i], 52.dp, c.id, tag = "cameo-${c.id}-token",
                                modifier = if (holder != null) Modifier.alpha(0.5f) else Modifier)
                            Text(c.label, style = Wood.sans(11.sp, FontWeight.SemiBold), maxLines = 1, textAlign = TextAlign.Center)
                            if (holder != null) Text("${if (holder == 0) "You" else "Seat ${holder + 1}"} · swaps",
                                style = Wood.sans(10.sp, color = Wood.INK_SOFT), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** A player's cameo in a ring of their [colour] (0.3.5): the player list, the end screen. [cameo] must have art. */
@Composable
internal fun CameoDisc(cameo: String, colour: Color, size: Dp, modifier: Modifier = Modifier, ring: Dp = 2.dp) {
    Image(painterResource(Cameos.ART.getValue(cameo)), null,
        modifier.size(size).clip(CircleShape).background(Wood.WOOD_DARK).border(ring, colour, CircleShape), contentScale = ContentScale.Crop)
}

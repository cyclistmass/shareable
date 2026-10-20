package com.mauro.wgbg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import wgbg.net.Friend

/** At most this many friends in one quick game: the room has three seats besides yours. */
internal const val QUICK_PICKS = 3

/**
 * Quick game with friends (#72): the people you played online with, starred first. Pick up to three and Start opens a
 * room with your last game's settings that invites them (they see it on their start screen). The star keeps someone
 * on the list for good; Forget takes them off.
 */
@Composable
fun FriendsScreen(
    friends: List<Friend>, busy: Boolean,
    onStart: (List<Friend>) -> Unit, onStar: (Friend) -> Unit, onForget: (Friend) -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf(emptySet<String>()) }
    Box(modifier.fillMaxSize().tableTop().testTag("friends"), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).padding(vertical = 10.dp).brassEdgedWood().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Quick game with friends", style = Wood.title(24.sp, Wood.BRASS_INK))
            Text(if (friends.isEmpty()) "Nobody here yet: people you play online with while signed in show up here."
                else "Pick up to $QUICK_PICKS. They see your invite on their start screen.",
                style = Wood.sans(13.sp, color = Wood.INK_MUTED))
            for (f in friends) {
                val on = f.uid in picked
                Row(Modifier.fillMaxWidth()
                    .background(if (on) Color(0x33C9A04A) else Color(0x22000000), RoundedCornerShape(8.dp))
                    .clickable(role = Role.Checkbox) {
                        picked = if (on) picked - f.uid else if (picked.size < QUICK_PICKS) picked + f.uid else picked
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp).testTag("friend-${f.name}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (on) "☑" else "☐", style = Wood.sans(18.sp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name.ifBlank { "Player" }, style = Wood.sans(15.sp, FontWeight.Bold), maxLines = 1)
                        Text("${f.games} game${if (f.games == 1) "" else "s"} together", style = Wood.sans(12.sp, color = Wood.INK_MUTED))
                    }
                    Text(if (f.starred) "★" else "☆", style = Wood.sans(20.sp, color = Wood.BRASS_LIGHT),
                        modifier = Modifier.clickable(role = Role.Button) { onStar(f) }.padding(6.dp).testTag("star-${f.name}"))
                    WoodButton("Forget", "forget-${f.name}") { picked = picked - f.uid; onForget(f) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrassButton("Back", "friends-back", Modifier.height(44.dp)) { onBack() }
                GoButton(if (busy) "Opening…" else "Start", "friends-start", picked.isNotEmpty() && !busy, Modifier.weight(1f).height(44.dp)) {
                    onStart(friends.filter { it.uid in picked })
                }
            }
        }
    }
}

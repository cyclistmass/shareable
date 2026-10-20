package com.mauro.wgbg

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Optional Google sign-in (#83, 0.2.8). Anonymous stays the default: you play at once, no sign-in wall. Signing in
 * LINKS Google to the account you already have, so its uid, your seats in online rooms and your saved games carry on.
 * If that Google account already belongs to another player id (signed in on another phone), we switch to it and say
 * that this phone's guest games stay behind. Signing out gives a fresh guest account.
 */
object GoogleAccount {
    /** What the account row shows and offers. */
    data class Status(val signedIn: Boolean, val email: String?, val uid: String?)

    sealed interface Outcome {
        /** Linked to the guest account: same uid, nothing lost. */
        data class Linked(val email: String?) : Outcome
        /** The Google account was already someone's: now signed in as that one; this phone's guest games stay behind. */
        data class Switched(val email: String?, val oldUid: String, val newUid: String) : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** The auth calls the account flow needs; [FirebaseOps] in the app, a fake in tests. */
    interface Ops {
        val uid: String?
        val email: String?
        val isGoogle: Boolean
        /** Links the Google ID token to the current user; throws [AlreadyInUse] if another user has it. */
        suspend fun link(idToken: String)
        /** Signs in as the Google user owning [idToken] (replacing the current user). */
        suspend fun signInWith(idToken: String)
        /** Signs out and back in as a new anonymous user. */
        suspend fun freshGuest()
    }

    class AlreadyInUse : Exception()

    fun status(ops: Ops) = Status(ops.isGoogle, ops.email, ops.uid)

    /** Links [idToken] (from the account picker) to the current player, or switches to its owner. */
    suspend fun signIn(ops: Ops, idToken: String): Outcome {
        val before = ops.uid ?: return Outcome.Failed("Not connected yet; try again in a moment")
        return try {
            ops.link(idToken)
            Outcome.Linked(ops.email)
        } catch (_: AlreadyInUse) {
            try {
                ops.signInWith(idToken)
                Outcome.Switched(ops.email, before, ops.uid ?: before)
            } catch (e: Exception) { Outcome.Failed(e.message ?: "Couldn't sign in") }
        } catch (e: Exception) { Outcome.Failed(e.message ?: "Couldn't sign in (offline?)") }
    }

    suspend fun signOut(ops: Ops) = ops.freshGuest()

    /** The player-facing line for an [Outcome]. */
    fun message(o: Outcome): String = when (o) {
        is Outcome.Linked -> "Signed in${o.email?.let { " as $it" } ?: ""}. Your games and rooms are kept."
        is Outcome.Switched -> "Signed in${o.email?.let { " as $it" } ?: ""}. That account was already in use, so the " +
            "games from this phone's guest account stay with the guest."
        Outcome.Cancelled -> ""
        is Outcome.Failed -> o.message
    }

    /** The web client ID from google-services.json, or null when this build has none (no Google sign-in offered). */
    fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    /** Shows the Google account picker; returns the ID token, or an [Outcome] when there is none. */
    suspend fun pick(activity: Activity, clientId: String): Pair<String?, Outcome?> = try {
        val option = GetGoogleIdOption.Builder().setServerClientId(clientId).setFilterByAuthorizedAccounts(false).build()
        val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val c = result.credential
        if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            GoogleIdTokenCredential.createFrom(c.data).idToken to null
        else null to Outcome.Failed("That wasn't a Google account")
    } catch (_: GetCredentialCancellationException) { null to Outcome.Cancelled
    } catch (_: NoCredentialException) { null to Outcome.Failed("No Google account on this phone")
    } catch (e: GetCredentialException) { null to Outcome.Failed(e.message ?: "Couldn't open the account picker") }

    /** [Ops] on Firebase Auth. */
    class FirebaseOps(private val auth: FirebaseAuth) : Ops {
        override val uid get() = auth.currentUser?.uid
        override val email get() = auth.currentUser?.email
        override val isGoogle get() = auth.currentUser?.providerData?.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } == true
        override suspend fun link(idToken: String) {
            val user = auth.currentUser ?: error("Not signed in")
            try { user.linkWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await() }
            catch (_: FirebaseAuthUserCollisionException) { throw AlreadyInUse() }
        }
        override suspend fun signInWith(idToken: String) { auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await() }
        override suspend fun freshGuest() { auth.signOut(); auth.signInAnonymously().await() }
    }
}

/**
 * Settings' Account rows: who you are online, and Sign in with Google / Sign out (#83). Hidden without Firebase.
 * [compact]: one line for the start screen (Mauro: "sign in should be on landing page").
 */
@androidx.compose.runtime.Composable
fun AccountSection(compact: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Set up off the main thread: on the game-start screen this ran while the player typed a name, and a slow Firebase
    // start (cold phone, disk busy) held the screen up. The rows appear once it's ready.
    val transport = androidx.compose.runtime.produceState<FirestoreTransport?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { LobbyRepository.create(context) as? FirestoreTransport }.getOrNull()
        }
    }.value ?: return
    val clientId = androidx.compose.runtime.remember { GoogleAccount.webClientId(context) } ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by androidx.compose.runtime.remember(transport) { androidx.compose.runtime.mutableStateOf(GoogleAccount.status(transport.account)) }
    var note by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val gold = androidx.compose.ui.graphics.Color(0xFFD9B865)
    val signOut: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                note = runCatching { GoogleAccount.signOut(transport.account) }.fold({ "Signed out: playing as a new guest" }, { "Couldn't sign out (offline?)" })
                transport.accountChanged(); status = GoogleAccount.status(transport.account); busy = false
            }
        }
    }
    val signIn: () -> Unit = signIn@{
        val activity = context as? android.app.Activity ?: return@signIn
        if (busy) return@signIn
        busy = true; note = ""
        scope.launch {
            val (token, early) = GoogleAccount.pick(activity, clientId)
            val outcome = early ?: GoogleAccount.signIn(transport.account, token!!)
            transport.accountChanged(); status = GoogleAccount.status(transport.account)
            note = GoogleAccount.message(outcome); busy = false
        }
    }
    val ink = androidx.compose.ui.graphics.Color(0xFFEDE6D8)
    if (compact) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Text(if (status.signedIn) status.email ?: "Signed in" else "Playing as a guest",
                fontSize = 12.sp, color = ink, maxLines = 2, modifier = androidx.compose.ui.Modifier.weight(1f).testTag("account-status"))
            if (status.signedIn) WoodButton("Sign out", "account-sign-out", androidx.compose.ui.Modifier.height(36.dp)) { signOut() }
            else BrassButton("Sign in with Google", "account-sign-in", androidx.compose.ui.Modifier.height(36.dp)) { signIn() }
        }
        if (note.isNotEmpty()) androidx.compose.material3.Text(note, fontSize = 12.sp, color = gold, modifier = androidx.compose.ui.Modifier.testTag("account-note"))
        return
    }
    androidx.compose.material3.Text("Account", fontSize = 13.sp, color = gold)
    androidx.compose.material3.Text(if (status.signedIn) "Signed in as ${status.email ?: "your Google account"}" else "Guest on this phone (no sign-in needed)",
        fontSize = 13.sp, color = ink,
        modifier = androidx.compose.ui.Modifier.testTag("account-status"))
    if (status.signedIn) WoodButton("Sign out", "account-sign-out", androidx.compose.ui.Modifier.height(40.dp)) { signOut() }
    else BrassButton("Sign in with Google", "account-sign-in", androidx.compose.ui.Modifier.height(40.dp)) { signIn() }
    if (note.isNotEmpty()) androidx.compose.material3.Text(note, fontSize = 12.sp, color = gold, modifier = androidx.compose.ui.Modifier.testTag("account-note"))
}

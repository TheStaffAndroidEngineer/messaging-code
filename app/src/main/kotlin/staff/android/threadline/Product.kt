package staff.android.threadline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.UUID

fun validateView(data:JSONObject){
    require(
        data.get("room") is String &&
            data.get("active") is Boolean &&
            data.get("cached") is Boolean)
    require(strictLong(data.get("epoch")) >= 0 && strictLong(data.get("cursor")) >= 0)
    val messages = data.getJSONArray("messages")
    require(messages.length() <= 100)
    for (i in 0 until messages.length()) checkMessage(messages.getJSONObject(i))
}
@Composable fun Product(state: CaseState, model: CaseModel, draft: String, changeDraft: (String) -> Unit, sendDraft: () -> Unit) {
    val data = state.data
    val active = data?.optBoolean("active") == true
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Text("ST", fontFamily = HeadingFont, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                }
                Column(Modifier.weight(1f)) {
                    Text("Studio team", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (!active && data != null) "You left this room" else "You and Morgan · demo room", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { details = true }, modifier = Modifier.testTag("room-details")) { Text("Info") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.problem != null) InfoCard(if (state.storageBlocked) "Saved conversation needs attention" else "Connection interrupted", state.problem)
            state.pending?.let {
                InfoCard("Waiting for confirmation", "Your change is saved. Retry checks the same change; it will not send a second message.")
                Button(onClick = model::retry, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("retry")) { Text("Retry saved request") }
            }
            state.outcome?.takeIf { it.has("reason") }?.let { InfoCard("Change not accepted", it.getString("reason")) }
            if (data == null) {
                InfoCard(if (state.busy) "Opening Studio team" else "Conversation unavailable", "Connect to the local conversation service and tap Refresh. Your saved unanswered send is kept on this device.")
            } else if (!active) {
                Spacer(Modifier.height(28.dp))
                SectionTitle("The door is open.")
                Text("You left Studio team. Rejoin to read new messages and take part again.")
                Button(onClick = { model.send("rejoin", JSONObject().put("epoch", data.getLong("epoch"))) }, enabled = ready(state), modifier = Modifier.testTag("rejoin")) { Text("Rejoin studio team") }
            } else {
                Text(if (data.getBoolean("cached") || state.problem != null) "SAVED · LATEST STATE UNVERIFIED" else "LAST SYNCED CONVERSATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                val messages = data.getJSONArray("messages")
                if (messages.length() == 0) InfoCard("Start something together", "Send the first message to Studio team. It will appear here when the service accepts it.")
                for (i in 0 until messages.length()) {
                    val message = messages.getJSONObject(i)
                    val own = message.getString("author") == "You"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
                        Column(Modifier.fillMaxWidth(.88f), horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
                            Text(message.getString("author"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                            Surface(color = if (own) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, contentColor = if (own) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = if (own) 4.dp else 18.dp, bottomStart = if (own) 18.dp else 4.dp), modifier = Modifier.testTag("message-${message.getString("id")}")) {
                                Text(message.getString("text"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                            if (own) TextButton(onClick = { model.send("delete", JSONObject().put("epoch", data.getLong("epoch")).put("messageId", message.getString("id"))) }, enabled = ready(state), modifier = Modifier.testTag("delete-${message.getString("id")}")) { Text("Delete", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        }
        if (active) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = draft, onValueChange = { if (it.length <= 500) changeDraft(it) }, label = { Text("Message the team") }, placeholder = { Text("Write a message…") }, modifier = Modifier.fillMaxWidth().testTag("draft"), minLines = 1, maxLines = 3, shape = RoundedCornerShape(20.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${draft.length}/500", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = sendDraft, enabled = ready(state) && draft.isNotBlank(), modifier = Modifier.testTag("send")) { Text("Send message") }
                }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Studio team") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("A shared conversation for studio plans."); Text("You + Morgan", fontWeight = FontWeight.Bold); Text("Demo room. Morgan’s messages are seeded by companion tools. Leaving removes the saved conversation after the service confirms it.") } }, confirmButton = { TextButton(onClick = { details = false }) { Text("Done") } }, dismissButton = { if (active) TextButton(onClick = { details = false; model.send("leave", JSONObject().put("epoch", data!!.getLong("epoch"))) }, enabled = ready(state), modifier = Modifier.testTag("leave")) { Text("Leave room") } })
}

package staff.android.threadline

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity:ComponentActivity(){
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)ViewModelProvider(this)[CaseModel::class.java].refresh()
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        if(Build.VERSION.SDK_INT>=37&&checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        val model=ViewModelProvider(this)[CaseModel::class.java]
        setContent{ProductTheme{val state by model.state.collectAsStateWithLifecycle();CaseScreen(state,model)}}
    }
}
val BodyFont=FontFamily(Font(R.font.google_sans_regular),Font(R.font.google_sans_bold,FontWeight.Bold))
val HeadingFont=FontFamily(Font(R.font.sora_bold,FontWeight.Bold))
@Composable fun ProductTheme(content:@Composable ()->Unit){
    val scheme=if(isSystemInDarkTheme())darkColorScheme(
        primary=Color(0xFFC6BFFF),onPrimary=Color(0xFF332769),background=Color(0xFF191725),onBackground=Color(0xFFF0ECFA),
        surface=Color(0xFF252236),onSurface=Color(0xFFF0ECFA),surfaceVariant=Color(0xFF373248),onSurfaceVariant=Color(0xFFD0C9E1),
        secondaryContainer=Color(0xFF443A70),onSecondaryContainer=Color(0xFFE9E3FF),outline=Color(0xFFACA3C2),outlineVariant=Color(0xFF504961))
    else lightColorScheme(
        primary=Color(0xFF6352B8),onPrimary=Color(0xFFFFFFFF),background=Color(0xFFF7F7FC),onBackground=Color(0xFF25243B),
        surface=Color(0xFFFFFFFF),onSurface=Color(0xFF25243B),surfaceVariant=Color(0xFFEEEBF5),onSurfaceVariant=Color(0xFF625C75),
        secondaryContainer=Color(0xFFE9E3FF),onSecondaryContainer=Color(0xFF392B70),outline=Color(0xFF77708B),outlineVariant=Color(0xFFDAD5E6))
    val defaults=Typography()
    MaterialTheme(scheme,typography=defaults.copy(bodyLarge=defaults.bodyLarge.copy(fontFamily=BodyFont),
        bodyMedium=defaults.bodyMedium.copy(fontFamily=BodyFont),titleMedium=defaults.titleMedium.copy(fontFamily=BodyFont),
        labelLarge=defaults.labelLarge.copy(fontFamily=BodyFont)),content=content)
}
@Composable fun CaseScreen(state: CaseState, model: CaseModel) {
    var lab by rememberSaveable { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    BackHandler(enabled = lab) { lab = false }
    var draft by rememberSaveable { mutableStateOf("") }
    var sentId by rememberSaveable { mutableStateOf("") }
    var sentText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.outcome) {
        val result = state.outcome
        if (result?.optBoolean("accepted") == true && result.optString("messageId") == sentId) {
            if (draft == sentText) draft = ""
            sentId = ""; sentText = ""
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AppChrome("Threadline") {
                TextButton(onClick = model::refresh, enabled = !state.busy && !state.storageBlocked, modifier = Modifier.testTag("refresh")) { Text("Refresh") }
                TextButton(onClick = { lab = !lab }, modifier = Modifier.testTag("lab-tab")) { Text(if (lab) "Chat" else "Lab") }
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 800.dp).fillMaxSize()) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("busy"))
                if (lab) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionTitle("Conversation lab")
                    Text("Chapter 35 · local service")
                    InfoCard("Simulation boundary", "Morgan is a seeded participant, not a live person or AI. No hosted account, push notifications or encryption are configured. Use the companion tools to seed incoming messages or interrupt replies.")
                    state.data?.let { Text("Room: ${it.getString("room")}\nMembership epoch: ${it.getLong("epoch")}\nCommitted cursor: ${it.getLong("cursor")}\nCached: ${it.getBoolean("cached")}") }
                    state.pending?.let { Text("Saved ${it.getString("action")}\n${it.getString("id")}") }
                    state.outcome?.let { Text("Last receipt: $it", style = MaterialTheme.typography.bodySmall) }
                    state.problem?.let { Text(it) }
                    Text("The committed conversation is ordered by service events. The normal conversation deliberately does not invent timestamps, delivery-to-recipient or read receipts.")
                    TextButton(onClick = { about = true }) { Text("About Threadline") }
                } else Product(state, model, draft, { draft = it }, {
                    sentId = java.util.UUID.randomUUID().toString()
                    sentText = draft
                    model.send("send", org.json.JSONObject().put("epoch", state.data!!.getLong("epoch")).put("messageId", sentId).put("text", draft))
                })
            }
        }
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("About Threadline") }, text = { Text("An independently runnable Chapter 35 conversation. Durable sends, ordered changes, deletion records and explicit resync are backed by the local simulator. See the companion README for setup and fault controls.") }, confirmButton = { TextButton(onClick = { about = false }) { Text("Close") } })
}
@Composable fun InfoCard(title:String,body:String){
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(title,fontWeight=FontWeight.Bold);Text(body,style=MaterialTheme.typography.bodyLarge)
        }
    }
}
@Composable fun SectionTitle(title:String){Text(title,fontFamily=HeadingFont,fontSize=27.sp,lineHeight=34.sp)}
fun ready(state:CaseState)=!state.busy&&!state.storageBlocked&&state.pending==null

@Composable
private fun AppChrome(title: String, statusInsets: Boolean = true, actions: @Composable () -> Unit) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints((if (statusInsets) Modifier.statusBarsPadding() else Modifier).fillMaxWidth().padding(horizontal = 16.dp)) {
        val needed = (title.length * 13 + if (title == "Fieldnotes") 290 else 190).dp * fontScale
        if (maxWidth < needed) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.padding(top = 10.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontFamily = HeadingFont, fontSize = 22.sp, lineHeight = 28.sp, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                actions()
            }
        }
    }
}

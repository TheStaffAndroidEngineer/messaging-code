package staff.android.threadline

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ConversationJourneyTest {
    @get:Rule(order=0)val permission=GrantPermissionRule.grant(Manifest.permission.ACCESS_LOCAL_NETWORK)
    @get:Rule(order=1)val compose=createAndroidComposeRule<MainActivity>()
    @Test fun oneSendSurvivesLostReplyAndExpiredCursorResyncs(){
        try {
            waitTag("draft");compose.onNodeWithTag("draft").performTextInput("Meet at noon")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            tap("lab-tab");tap("lab-tab")
            compose.onNodeWithTag("draft").assertTextContains("Meet at noon")
            request("/__control","{\"drop_reply\":true}");tap("send");waitTag("retry")
            val messages=request("/__control").getJSONObject("state").getJSONArray("messages")
            assertEquals(3,messages.length());val sentId=messages.getJSONObject(2).getString("id")
            compose.onNodeWithTag("draft").performTextReplacement("A newer unsent thought")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            tap("lab-tab");tap("lab-tab")
            compose.onNodeWithTag("draft").assertTextContains("A newer unsent thought")
            request("/__control","{}");tap("retry");waitReady()
            compose.onNodeWithTag("draft").assertTextContains("A newer unsent thought")
            assertEquals(3,request("/__control").getJSONObject("state").getJSONArray("messages").length())
            tap("delete-$sentId");waitReady();assertEquals(2,cache().getJSONArray("messages").length())
            for(i in 1..10)command("incoming",JSONObject().put("epoch",1).put("messageId","morgan-$i").put("text","Studio update $i"))
            tap("refresh");waitReady()
            assertEquals(14,cache().getInt("cursor"));assertEquals(12,cache().getJSONArray("messages").length())
            tap("room-details");tap("leave");waitTag("rejoin");assertEquals(0,cache().getJSONArray("messages").length())
        }finally{request("/__control","{}")}
    }
    private fun cache()=JSONObject(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"conversation.json").readText())
    private fun command(action:String,args:JSONObject)=request("/commands",JSONObject().put("id",UUID.randomUUID().toString()).put("action",action).put("args",args).toString())
    private fun tap(tag:String){
        val node = compose.onNodeWithTag(tag)
        if (tag in setOf("send", "refresh", "room-details", "leave", "lab-tab")) node.performClick()
        else node.performScrollTo().performClick()
    }
    private fun waitTag(tag:String){compose.waitUntil(30000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};waitReady()}
    private fun waitReady(){compose.waitUntil(30000){compose.onAllNodesWithTag("busy").fetchSemanticsNodes().isEmpty()}}
    private fun request(path:String,body:String?=null):JSONObject{
        val connection=URL("http://10.0.2.2:8915$path").openConnection() as HttpURLConnection
        connection.connectTimeout=5000;connection.readTimeout=5000
        if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toByteArray())}}
        return try{JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
    }
}

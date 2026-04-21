package com.example.tavernapp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

// =======================
// 数据模型 (全面升级)
// =======================
data class ApiConfig(val id: String = java.util.UUID.randomUUID().toString(), var name: String, var category: String, var baseUrl: String, var apiKey: String, var modelName: String)
data class LoreEntry(val id: String = java.util.UUID.randomUUID().toString(), var keys: List<String> = emptyList(), var content: String = "")
data class CharacterBook(val name: String = "", val entries: List<LoreEntry> = emptyList())

// 新增 bgUri，支持角色专属背景
data class TavernCharacter(
    val name: String = "未知", val description: String = "", val personality: String = "", val scenario: String = "", 
    @SerializedName("first_mes") val firstMes: String = "你好", var category: String = "未分类", 
    var avatarUri: String? = null, var bgUri: String? = null, 
    @SerializedName("character_book") val characterBook: CharacterBook? = null
)
data class CharacterCardData(val data: TavernCharacter?)

// 核心改动：状态放入单条消息中
data class CharacterState(var affection: Int = 50, var thoughts: String = "", var appearance: String = "")
data class ChatMessage(val id: String = java.util.UUID.randomUUID().toString(), val role: String, var displayContent: String, var rawContent: String = displayContent, var state: CharacterState? = null)

data class RegexRule(val id: String = java.util.UUID.randomUUID().toString(), var pattern: String, var replacement: String, var isEnabled: Boolean = true)
data class PromptPreset(val id: String = java.util.UUID.randomUUID().toString(), var name: String, var content: String, var isEnabled: Boolean = true)
data class GenSettings(var temperature: Float = 0.8f, var topP: Float = 0.9f, var maxTokens: Int = 1024)
data class UserPersona(var name: String = "旅行者", var description: String = "一个神秘的过客。")

// 全局状态缓存
val globalApiConfigs = mutableStateListOf(ApiConfig(name = "DeepSeek", category = "默认", baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "替换APIKEY", modelName = "deepseek-chat"))
var currentActiveApi by mutableStateOf(globalApiConfigs[0])
val globalCharacterList = mutableStateListOf(TavernCharacter("艾琳", "酒馆老板娘，热情好客", "泼辣、开朗", "红龙酒馆里", "呦，客人，想喝点什么？", "奇幻"))
var globalHomeBgUri by mutableStateOf<Uri?>(null)
val globalWorldBook = mutableStateListOf<LoreEntry>()
val globalRegexRules = mutableStateListOf<RegexRule>()
val globalPresets = mutableStateListOf(PromptPreset(name = "动作强化", content = "请在回复中加入大量细腻的神态、动作描写，以增强画面感。", isEnabled = true))
var globalEnableRendering by mutableStateOf(true)
var globalGenSettings by mutableStateOf(GenSettings())
var globalUserPersona by mutableStateOf(UserPersona())

// =======================
// 存储引擎 & 解析引擎
// =======================
object LocalStorage {
    private const val PREFS_NAME = "TavernData"
    fun saveChats(context: Context, charName: String, messages: List<ChatMessage>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("chat_$charName", Gson().toJson(messages)).apply()
    }
    fun loadChats(context: Context, charName: String): List<ChatMessage>? {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("chat_$charName", null)
        return if (json != null) Gson().fromJson(json, object : TypeToken<List<ChatMessage>>() {}.type) else null
    }
}

fun parsePngTavernCard(context: Context, uri: Uri): TavernCharacter? {
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val bytes = inputStream.readBytes()
            val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            if (!bytes.take(8).toByteArray().contentEquals(pngSignature)) return null
            var offset = 8
            while (offset < bytes.size - 8) {
                val length = (bytes[offset].toInt() and 0xFF shl 24) or (bytes[offset+1].toInt() and 0xFF shl 16) or (bytes[offset+2].toInt() and 0xFF shl 8) or (bytes[offset+3].toInt() and 0xFF)
                val type = String(bytes, offset + 4, 4, StandardCharsets.UTF_8)
                if (type == "tEXt") {
                    val textString = String(bytes.copyOfRange(offset + 8, offset + 8 + length), StandardCharsets.UTF_8)
                    if (textString.startsWith("chara\u0000")) {
                        val jsonString = String(Base64.decode(textString.substring(6), Base64.DEFAULT), StandardCharsets.UTF_8)
                        return Gson().fromJson(jsonString, TavernCharacter::class.java)?.apply { this.avatarUri = uri.toString() }
                    }
                }
                offset += 12 + length
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

fun extractAndAddLorebook(character: TavernCharacter, context: Context) {
    if (character.characterBook != null && character.characterBook.entries.isNotEmpty()) {
        val count = character.characterBook.entries.size
        globalWorldBook.addAll(character.characterBook.entries)
        Toast.makeText(context, "自动解包: 导入了 ${count} 条世界书设定！", Toast.LENGTH_LONG).show()
    }
}

suspend fun pullAvailableModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()
        val modelsUrl = if (baseUrl.endsWith("/chat/completions")) baseUrl.replace("/chat/completions", "/models")
        else if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")) baseUrl.trimEnd('/') + "/models" else "$baseUrl/models"
        val request = Request.Builder().url(modelsUrl).addHeader("Authorization", "Bearer $apiKey").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val data = JSONObject(response.body?.string() ?: return@withContext emptyList()).optJSONArray("data") ?: return@withContext emptyList()
            val models = mutableListOf<String>()
            for (i in 0 until data.length()) models.add(data.getJSONObject(i).getString("id"))
            return@withContext models
        }
    } catch (e: Exception) { emptyList() }
}

// =======================
// 主入口与路由
// =======================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TavernAppNavigation() } }
    }
}

@Composable
fun TavernAppNavigation() {
    val navController = rememberNavController()
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D12))) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") { HomeScreen(navController) }
            composable("settings") { ApiSettingsScreen(navController) }
            composable("lorebook") { LorebookScreen(navController) } 
            composable("regex") { RegexScreen(navController) }       
            composable("presets") { PresetScreen(navController) } 
            composable("persona") { PersonaAndGenScreen(navController) }
            composable("edit_char/{charName}") { backStackEntry ->
                val charName = backStackEntry.arguments?.getString("charName")
                val character = globalCharacterList.find { it.name == charName } ?: globalCharacterList[0]
                CharacterEditScreen(character, navController)
            }
            composable("chat/{charName}") { backStackEntry ->
                val charName = backStackEntry.arguments?.getString("charName")
                val character = globalCharacterList.find { it.name == charName } ?: globalCharacterList[0]
                TavernChatScreen(character, navController)
            }
        }
        FloatingAssistant(navController) 
    }
}

// =======================
// 全局悬浮球助手
// =======================
@Composable
fun FloatingAssistant(navController: NavController) {
    val context = LocalContext.current
    var offsetX by remember { mutableStateOf(30f) }
    var offsetY by remember { mutableStateOf(500f) }
    var isExpanded by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val type = context.contentResolver.getType(uri)
                val chara: TavernCharacter? = if (type?.contains("image/png") == true) parsePngTavernCard(context, uri)
                else context.contentResolver.openInputStream(uri)?.use { Gson().fromJson(InputStreamReader(it), CharacterCardData::class.java)?.data }
                if (chara != null && chara.name.isNotBlank()) {
                    chara.category = if (type?.contains("image/png") == true) "PNG导入" else "JSON导入"
                    globalCharacterList.add(chara)
                    extractAndAddLorebook(chara, context)
                    Toast.makeText(context, "角色 ${chara.name} 接入完毕！", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "角色卡损坏", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "读取异常", Toast.LENGTH_SHORT).show() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }) {
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.heightIn(max = 400.dp).background(Color(0xFF101018).copy(alpha = 0.95f), RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(8.dp).verticalScroll(rememberScrollState())) {
                    AssistantButton("👤 导入角色卡") { importLauncher.launch("*/*"); isExpanded = false }
                    AssistantButton("🎭 玩家与模型参数") { navController.navigate("persona"); isExpanded = false }
                    AssistantButton("💡 预设与破壁") { navController.navigate("presets"); isExpanded = false }
                    AssistantButton("📖 世界书面板") { navController.navigate("lorebook"); isExpanded = false }
                    AssistantButton("📜 正则清洗器") { navController.navigate("regex"); isExpanded = false }
                    AssistantButton("⚙️ API 管理") { navController.navigate("settings"); isExpanded = false }
                    AssistantButton(if (globalEnableRendering) "🖥️ 关富文本" else "🖥️ 开富文本", Color(0xFFFF007F)) { globalEnableRendering = !globalEnableRendering; isExpanded = false }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp).shadow(16.dp, CircleShape, spotColor = Color(0xFF00E5FF)).background(Color(0xFF0D47A1).copy(alpha = 0.8f), CircleShape).border(2.dp, Color(0xFF00E5FF), CircleShape).pointerInput(Unit) { detectDragGestures { change, dragAmount -> change.consume(); offsetX += dragAmount.x; offsetY += dragAmount.y } }.clickable { isExpanded = !isExpanded }) {
                Icon(Icons.Filled.Settings, contentDescription = "Terminal", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}
@Composable fun AssistantButton(text: String, textColor: Color = Color(0xFFE0E0E0), onClick: () -> Unit) { Text(text = text, color = textColor, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp, horizontal = 24.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace) }

// =======================
// 新增：角色设定编辑器 (Character Editor)
// =======================
@Composable
fun CharacterEditScreen(character: TavernCharacter, navController: NavController) {
    var editName by remember { mutableStateOf(character.name) }
    var editDesc by remember { mutableStateOf(character.description) }
    var editPersonality by remember { mutableStateOf(character.personality) }
    var editScenario by remember { mutableStateOf(character.scenario) }
    var editFirstMes by remember { mutableStateOf(character.firstMes) }
    var editAvatar by remember { mutableStateOf(character.avatarUri) }
    
    val avatarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri != null) editAvatar = uri.toString() }

    Scaffold(containerColor = Color(0xFF121212)) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("← 返回", color = Color(0xFF00E5FF)) }
                Button(onClick = {
                    val index = globalCharacterList.indexOfFirst { it.name == character.name }
                    if (index != -1) {
                        globalCharacterList[index] = character.copy(name = editName, description = editDesc, personality = editPersonality, scenario = editScenario, firstMes = editFirstMes, avatarUri = editAvatar)
                    }
                    navController.popBackStack()
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) { Text("保存修改", color = Color.Black) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(modifier = Modifier.align(Alignment.CenterHorizontally).clickable { avatarLauncher.launch("image/*") }) {
                if (editAvatar != null) AsyncImage(model = Uri.parse(editAvatar), contentDescription = null, modifier = Modifier.size(100.dp).clip(CircleShape).border(2.dp, Color(0xFF00E5FF), CircleShape), contentScale = ContentScale.Crop)
                else Box(modifier = Modifier.size(100.dp).background(Color.Gray, CircleShape), contentAlignment = Alignment.Center) { Text("换头像", color = Color.White) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("角色姓名") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            OutlinedTextField(value = editDesc, onValueChange = { editDesc = it }, label = { Text("背景设定 (Description)") }, modifier = Modifier.fillMaxWidth().height(120.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            OutlinedTextField(value = editPersonality, onValueChange = { editPersonality = it }, label = { Text("性格 (Personality)") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            OutlinedTextField(value = editScenario, onValueChange = { editScenario = it }, label = { Text("场景 (Scenario)") }, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            OutlinedTextField(value = editFirstMes, onValueChange = { editFirstMes = it }, label = { Text("开场白 (First Message)") }, modifier = Modifier.fillMaxWidth().height(100.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
        }
    }
}

// =======================
// 主页与设置等基础面板
// =======================
@Composable
fun PersonaAndGenScreen(navController: NavController) { /* 保持上个版本，用于控制人设与温度 */
    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFF00E5FF), fontSize = 20.sp) }; Text("🎭 档案与参数", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold) }
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.8f)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF007F).copy(alpha = 0.4f))) { Column(modifier = Modifier.padding(16.dp)) { Text("👤 玩家人设", color = Color(0xFFFF007F), fontWeight = FontWeight.Bold); OutlinedTextField(value = globalUserPersona.name, onValueChange = { globalUserPersona = globalUserPersona.copy(name = it) }, label = { Text("名字", color=Color.Gray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)); OutlinedTextField(value = globalUserPersona.description, onValueChange = { globalUserPersona = globalUserPersona.copy(description = it) }, label = { Text("背景描述", color=Color.Gray) }, modifier = Modifier.fillMaxWidth().height(100.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)) } }
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.8f)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f))) { Column(modifier = Modifier.padding(16.dp)) { Text("⚙️ 采样参数", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(16.dp)); Text("Temperature: ${globalGenSettings.temperature}", color = Color.White, fontSize = 12.sp); Slider(value = globalGenSettings.temperature, onValueChange = { globalGenSettings = globalGenSettings.copy(temperature = it) }, valueRange = 0f..2f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF), activeTrackColor = Color(0xFF00E5FF))); Text("Top P: ${globalGenSettings.topP}", color = Color.White, fontSize = 12.sp); Slider(value = globalGenSettings.topP, onValueChange = { globalGenSettings = globalGenSettings.copy(topP = it) }, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF), activeTrackColor = Color(0xFF00E5FF))); Text("Max Tokens: ${globalGenSettings.maxTokens}", color = Color.White, fontSize = 12.sp); Slider(value = globalGenSettings.maxTokens.toFloat(), onValueChange = { globalGenSettings = globalGenSettings.copy(maxTokens = it.toInt()) }, valueRange = 100f..4096f, colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF), activeTrackColor = Color(0xFF00E5FF))) } }
        }
    }
}

@Composable
fun PresetScreen(navController: NavController) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }
    
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val jsonStr = inputStream.readBytes().toString(StandardCharsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    val name = json.optString("name", "导入预设")
                    val content = json.optString("content", json.optString("prompt", ""))
                    if (content.isNotBlank()) {
                        globalPresets.add(PromptPreset(name = name, content = content))
                        Toast.makeText(context, "预设导入成功", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Toast.makeText(context, "解析失败", Toast.LENGTH_SHORT).show() }
        }
    }

    if (showAddDialog) { AlertDialog(onDismissRequest = { showAddDialog = false }, containerColor = Color(0xFF1E1E24), title = { Text("录入预设", color = Color.White) }, text = { Column { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名称", color=Color.Gray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White, unfocusedTextColor=Color.White)); OutlinedTextField(value = newContent, onValueChange = { newContent = it }, label = { Text("提示词内容", color=Color.Gray) }, modifier = Modifier.height(120.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White, unfocusedTextColor=Color.White)) } }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)), onClick = { if (newName.isNotBlank() && newContent.isNotBlank()) { globalPresets.add(PromptPreset(name = newName, content = newContent)); showAddDialog = false } }) { Text("保存", color = Color.Black) } }, dismissButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), onClick = { showAddDialog = false }) { Text("取消", color = Color.White) } }) }
    Scaffold(containerColor = Color.Transparent, floatingActionButton = { Column { FloatingActionButton(containerColor=Color(0xFFFF007F), contentColor=Color.White, onClick={importLauncher.launch("*/*")}, modifier=Modifier.padding(bottom=8.dp)){Icon(Icons.Filled.List,"导入文件")}; FloatingActionButton(containerColor = Color(0xFF00E5FF), contentColor = Color.Black, onClick = { newName=""; newContent=""; showAddDialog = true }) { Icon(Icons.Filled.Add, "新增预设") } } }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFF00E5FF), fontSize = 20.sp) }; Text("💡 预设提示词 (Prompts)", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold) }
            LazyColumn { items(globalPresets) { preset -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.8f))) { Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("📌 ${preset.name}", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(4.dp)); Text(preset.content, color = Color(0xFFE0E0E0), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }; Switch(checked = preset.isEnabled, onCheckedChange = { preset.isEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF00E5FF), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color(0xFF424242))); IconButton(onClick = { globalPresets.remove(preset) }) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFFF007F)) } } } } }
        }
    }
}

@Composable
fun HomeScreen(navController: NavController) {
    var selectedCategory by remember { mutableStateOf("全部") }
    val categories = listOf("全部") + globalCharacterList.map { it.category }.distinct()
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri != null) globalHomeBgUri = uri }
    Box(modifier = Modifier.fillMaxSize()) {
        if (globalHomeBgUri != null) AsyncImage(model = globalHomeBgUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)))
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Nexus 选角大厅", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Black); Button(onClick = { bgLauncher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.2f)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))) { Text("🎨 大厅壁纸", color = Color(0xFF00E5FF)) } }
                LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { items(categories) { cat -> val isSelected = cat == selectedCategory; Surface(modifier = Modifier.padding(end = 8.dp).clickable { selectedCategory = cat }, shape = RoundedCornerShape(16.dp), color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color(0xFF1E1E24).copy(alpha = 0.6f)) { Text(cat, color = if(isSelected) Color.Black else Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold) } } }
                val filteredList = if (selectedCategory == "全部") globalCharacterList else globalCharacterList.filter { it.category == selectedCategory }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) { items(filteredList) { chara -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { navController.navigate("chat/${chara.name}") }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24).copy(alpha = 0.65f)), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) { Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { if (chara.avatarUri != null) AsyncImage(model = Uri.parse(chara.avatarUri), contentDescription = null, modifier = Modifier.size(70.dp).clip(RoundedCornerShape(20.dp)).border(2.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop) else Box(modifier = Modifier.size(70.dp).background(Color(0xFF673AB7), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text(chara.name.take(1), color = Color.White, fontSize = 28.sp) }; Spacer(modifier = Modifier.width(16.dp)); Column { Text(chara.name, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("分类: ${chara.category}", color = Color(0xFF00E5FF), style = MaterialTheme.typography.bodySmall); Spacer(modifier = Modifier.height(4.dp)); Text(chara.description, color = Color(0xFFCCCCCC), maxLines = 1, overflow = TextOverflow.Ellipsis) } } } } }
            }
        }
    }
}
@Composable fun ApiSettingsScreen(navController: NavController) { /* 折叠API界面以省空间，保持原有不变 */ Column(modifier = Modifier.fillMaxSize().padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("← 返回", color = Color(0xFF00E5FF)) }; Text("API 核心网关", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold) }; Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.2f)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))) { Column(modifier = Modifier.padding(16.dp)) { Text("📡 当前活动", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold); Text("${currentActiveApi.name} - ${currentActiveApi.modelName}\n${currentActiveApi.baseUrl}", color = Color.White) } }; LazyColumn { items(globalApiConfigs) { api -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { currentActiveApi = api }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24).copy(alpha = 0.8f))) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(api.name, color = Color.White); Text(api.baseUrl, color = Color.Gray, maxLines = 1) }; if (currentActiveApi.id == api.id) Icon(Icons.Filled.Settings, "已选中", tint = Color(0xFF00E5FF)) } } } } } }
@Composable fun LorebookScreen(navController: NavController) { /* 折叠保持不变 */ Column(modifier = Modifier.fillMaxSize().padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFF00E5FF), fontSize = 20.sp) }; Text("📖 世界书", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold) }; LazyColumn { items(globalWorldBook) { entry -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.8f))) { Row(modifier = Modifier.padding(16.dp)) { Column(modifier = Modifier.weight(1f)) { Text("🔑 ${entry.keys.joinToString(", ")}", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold); Text(entry.content, color = Color(0xFFE0E0E0)) }; IconButton(onClick = { globalWorldBook.remove(entry) }) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFFF007F)) } } } } } } }
@Composable fun RegexScreen(navController: NavController) { /* 折叠保持不变 */ Column(modifier = Modifier.fillMaxSize().padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFFFF007F), fontSize = 20.sp) }; Text("📜 正则洗稿", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }; LazyColumn { items(globalRegexRules) { rule -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.8f))) { Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("🎯 ${rule.pattern}", color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace); Text("🔄 ${rule.replacement}", color = Color(0xFFA5D6A7), fontFamily = FontFamily.Monospace) }; Switch(checked = rule.isEnabled, onCheckedChange = { rule.isEnabled = it }); IconButton(onClick = { globalRegexRules.remove(rule) }) { Icon(Icons.Filled.Delete, "删除", tint = Color.Gray) } } } } } } }

// =======================
// 终极核心：内嵌式动态渲染栏聊天界面 (Dynamic Inline HUD)
// =======================
@Composable
fun TavernChatScreen(character: TavernCharacter, navController: NavController) {
    val context = LocalContext.current
    var messages by remember { mutableStateOf(LocalStorage.loadChats(context, character.name) ?: listOf(ChatMessage(role = "assistant", displayContent = character.firstMes))) }
    LaunchedEffect(messages) { LocalStorage.saveChats(context, character.name, messages) }

    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    // 角色专属背景导入
    val charBgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> 
        if(uri != null) {
            val index = globalCharacterList.indexOfFirst { it.name == character.name }
            if (index != -1) globalCharacterList[index] = character.copy(bgUri = uri.toString())
        }
    }

    val executeAIRequest = {
        isLoading = true
        coroutineScope.launch {
            val rawAiReply = fetchAIReply(messages, character, currentActiveApi)
            var cleanedReply = rawAiReply
            globalRegexRules.filter { it.isEnabled }.forEach { rule -> try { cleanedReply = cleanedReply.replace(Regex(rule.pattern), rule.replacement) } catch (e: Exception) {} }
            
            // 解析出 JSON 状态和干净文本，将状态塞入这条单独的 Message 里
            val (finalReply, newState) = parseAiState(cleanedReply)
            messages = messages + ChatMessage(role = "assistant", displayContent = finalReply, rawContent = rawAiReply, state = newState)
            isLoading = false
        }
    }

    // 优先使用角色独立背景，否则纯黑
    Box(modifier = Modifier.fillMaxSize()) {
        if (character.bgUri != null) {
            AsyncImage(model = Uri.parse(character.bgUri), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)))
        }

        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp)) {
            // 顶部栏
            Surface(color = Color(0xFF101018).copy(alpha = 0.8f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFF00E5FF), fontSize = 20.sp) }
                        Column { Text(character.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(currentActiveApi.name, color = Color(0xFF00E5FF), style = MaterialTheme.typography.bodySmall) }
                    }
                    Row {
                        IconButton(onClick = { navController.navigate("edit_char/${character.name}") }) { Icon(Icons.Filled.Edit, "编辑角色", tint = Color.White) }
                        IconButton(onClick = { charBgLauncher.launch("image/*") }) { Icon(Icons.Filled.Star, "专属壁纸", tint = Color(0xFF00E5FF)) }
                    }
                }
            }
            
            // 注意：顶部的固定 HUD 已经被移除！

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(messages) { msg -> 
                    ChatBubble(
                        message = msg, character = character, 
                        onEdit = { newText -> messages = messages.map { if (it.id == msg.id) it.copy(displayContent = newText, rawContent = newText) else it } }, 
                        onDelete = { messages = messages.filter { it.id != msg.id } }
                    )
                    Spacer(modifier = Modifier.height(16.dp)) 
                }
            }

            Surface(color = Color(0xFF101018).copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth()) {
                Column {
                    if (messages.isNotEmpty() && messages.last().role == "assistant" && !isLoading) {
                        Text(text = "🔄 重刷 (Regenerate)", color = Color(0xFFFF007F), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable { messages = messages.dropLast(1); executeAIRequest() }.padding(vertical = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold)
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), placeholder = { Text("发送消息...", color = Color.Gray) }, shape = RoundedCornerShape(24.dp), enabled = !isLoading, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF1E1E24), unfocusedContainerColor = Color(0xFF1E1E24), focusedBorderColor = Color(0xFF00E5FF), unfocusedBorderColor = Color.Transparent))
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(onClick = { if (inputText.isNotBlank() && !isLoading) { messages = messages + ChatMessage(role = "user", displayContent = inputText); inputText = ""; executeAIRequest() } }, enabled = !isLoading, shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))) { Text(if (isLoading) "..." else "Send", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

// 核心内嵌状态框UI
@Composable
fun InlineStatusHud(state: CharacterState) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).background(Color(0xFF101018).copy(alpha = 0.6f), RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SYNCH: ${state.affection}%", color = Color(0xFFFF007F), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(8.dp))
            val progress by animateFloatAsState(targetValue = state.affection / 100f)
            LinearProgressIndicator(progress = progress, modifier = Modifier.height(2.dp).weight(1f).clip(RoundedCornerShape(1.dp)), color = Color(0xFFFF007F), trackColor = Color(0xFF333333))
        }
        if (state.thoughts.isNotBlank()) { Spacer(modifier = Modifier.height(4.dp)); Text("💭 ${state.thoughts}", color = Color(0xFF00E5FF), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic, fontSize = 11.sp)) }
        if (state.appearance.isNotBlank()) { Spacer(modifier = Modifier.height(2.dp)); Text("👀 ${state.appearance}", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp)) }
    }
}

// 动态气泡：现在包含了内嵌渲染栏
@Composable
fun ChatBubble(message: ChatMessage, character: TavernCharacter, onEdit: (String) -> Unit, onDelete: () -> Unit) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val markwon = remember { Markwon.builder(context).usePlugin(HtmlPlugin.create()).build() }
    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(message.displayContent) }

    if (showEditDialog) {
        AlertDialog(onDismissRequest = { showEditDialog = false }, containerColor = Color(0xFF1E1E24), title = { Text("修改 / 删除", color = Color.White) }, text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, modifier = Modifier.fillMaxWidth().height(200.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)) }, confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)), onClick = { onEdit(editText); showEditDialog = false }) { Text("修改", color = Color.Black) } }, dismissButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF007F)), onClick = { onDelete(); showEditDialog = false }) { Text("删除", color = Color.White) } })
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            if (character.avatarUri != null) AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(40.dp).background(Color(0xFF9C27B0), CircleShape), contentAlignment = Alignment.Center) { Text(character.name.take(1), color = Color.White) }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(if (isUser) globalUserPersona.name.uppercase() else character.name.uppercase(), color = if (isUser) Color(0xFF00E5FF) else Color(0xFFFF007F), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp))
            Box(modifier = Modifier.background(color = if (isUser) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E1E24).copy(alpha = 0.8f), shape = RoundedCornerShape(16.dp, 16.dp, if (isUser) 16.dp else 4.dp, if (isUser) 4.dp else 16.dp)).border(1.dp, if (isUser) Color(0xFF00E5FF).copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(16.dp, 16.dp, if (isUser) 16.dp else 4.dp, if (isUser) 4.dp else 16.dp)).padding(16.dp).widthIn(max = 280.dp).clickable { showEditDialog = true }) {
                Column {
                    // 【核心机制：由大模型分析后生成的内嵌状态栏】
                    if (message.state != null) { InlineStatusHud(message.state!!) }
                    
                    // 富文本内容
                    if (globalEnableRendering) AndroidView(modifier = Modifier.wrapContentSize(), factory = { ctx -> TextView(ctx).apply { setTextColor(android.graphics.Color.parseColor("#EFEFEF")); textSize = 15f; movementMethod = android.text.method.LinkMovementMethod.getInstance() } }, update = { textView -> markwon.setMarkdown(textView, message.displayContent) })
                    else Text(message.displayContent, color = Color(0xFFEFEFEF), fontSize = 15.sp)
                }
            }
        }
        if (isUser) { Spacer(modifier = Modifier.width(8.dp)); Box(modifier = Modifier.size(40.dp).background(Color(0xFF1976D2), CircleShape), contentAlignment = Alignment.Center) { Text("我", color = Color.White) } }
    }
}

// 核心网络与解析
fun parseAiState(rawContent: String): Pair<String, CharacterState?> {
    val regex = "(?s)```json\\s*(\\{.*?\\})\\s*```(.*)".toRegex()
    val match = regex.find(rawContent)
    if (match != null) { try { val json = JSONObject(match.groupValues[1]); return Pair(match.groupValues[2].trim(), CharacterState(json.optInt("affection", 50), json.optString("thoughts", ""), json.optString("appearance", ""))) } catch (e: Exception) {} }
    return Pair(rawContent, null)
}

suspend fun fetchAIReply(history: List<ChatMessage>, character: TavernCharacter, apiConfig: ApiConfig): String = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val activePresetsText = globalPresets.filter { it.isEnabled }.joinToString("\n\n") { "【预设: ${it.name}】：\n${it.content}" }
        val recentText = history.takeLast(3).joinToString(" ") { it.displayContent }
        val triggeredLore = StringBuilder()
        globalWorldBook.forEach { entry -> if (entry.keys.any { key -> recentText.contains(key, ignoreCase = true) }) triggeredLore.append("[世界设定补充]: ").append(entry.content).append("\n") }
        
        val finalSystemPrompt = """
            你是${character.name}。
            【你的设定】：${character.description}
            【你的性格】：${character.personality}
            【当前场景】：${character.scenario}
            
            【玩家设定】：姓名：${globalUserPersona.name}，描述：${globalUserPersona.description}
            
            ${triggeredLore.toString()}
            $activePresetsText
            
            【格式要求】：每次回复请附带一段JSON代码块分析当前状态（只输出一次），并且动作描写用 *星号* 包裹。
            ```json
            { "affection": 55, "thoughts": "这人真有意思。", "appearance": "微微一笑" }
            ```
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("model", apiConfig.modelName)
            put("temperature", globalGenSettings.temperature)
            put("top_p", globalGenSettings.topP)
            put("max_tokens", globalGenSettings.maxTokens)
            val messagesArray = JSONArray().apply { put(JSONObject().apply { put("role", "system"); put("content", finalSystemPrompt) }) }
            history.forEach { msg -> messagesArray.put(JSONObject().apply { put("role", msg.role); put("content", msg.rawContent) }) }
            put("messages", messagesArray)
        }
        val request = Request.Builder().url(apiConfig.baseUrl).addHeader("Authorization", "Bearer ${apiConfig.apiKey}").post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext "API请求失败: ${response.code}"
            return@withContext JSONObject(response.body?.string() ?: return@withContext "无数据").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    } catch (e: Exception) { return@withContext "请求异常: ${e.message}" }
}

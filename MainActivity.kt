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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
// 核心数据模型 (全面升级版)
// =======================
data class ApiConfig(val id: String = java.util.UUID.randomUUID().toString(), var name: String, var category: String, var baseUrl: String, var apiKey: String, var modelName: String)

// 世界书条目模型
data class LoreEntry(val keys: List<String> = emptyList(), val content: String = "")
data class CharacterBook(val name: String = "", val entries: List<LoreEntry> = emptyList())

data class TavernCharacter(
    val name: String = "未知角色", val description: String = "", val personality: String = "",
    val scenario: String = "", @SerializedName("first_mes") val firstMes: String = "你好...",
    var category: String = "未分类", var avatarUri: String? = null,
    @SerializedName("character_book") val characterBook: CharacterBook? = null // 自动解析角色自带的世界书
)
data class CharacterCardData(val data: TavernCharacter?)
data class ChatMessage(val role: String, val displayContent: String, val rawContent: String = displayContent)
data class CharacterState(var affection: Int = 50, var thoughts: String = "观察中", var appearance: String = "平静")

// 正则替换规则
data class RegexRule(val pattern: String, val replacement: String, var isEnabled: Boolean = true)

// 全局系统状态
val globalApiConfigs = mutableStateListOf(ApiConfig(name = "DeepSeek", category = "默认", baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "填写API", modelName = "deepseek-chat"))
var currentActiveApi by mutableStateOf(globalApiConfigs[0])
val globalCharacterList = mutableStateListOf(TavernCharacter("艾琳", "酒馆老板娘", "泼辣", "酒馆里", "想喝点什么？", "奇幻"))
var globalHomeBgUri by mutableStateOf<Uri?>(null)

// 世界书与高级设定全局变量
val globalWorldBook = mutableStateListOf<LoreEntry>()
val globalRegexRules = mutableStateListOf<RegexRule>()
var globalEnableRendering by mutableStateOf(true) // 是否开启富文本渲染

// =======================
// 解析引擎 (支持PNG与JSON)
// =======================
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
                        return Gson().fromJson(jsonString, TavernCharacter::class.java).apply { this.avatarUri = uri.toString() }
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
        Toast.makeText(context, "自动提取了 ${count} 条世界书设定！", Toast.LENGTH_SHORT).show()
    }
}

// =======================
// 主入口
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
    // 用 Box 包裹，让悬浮窗在最顶层
    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") { HomeScreen(navController) }
            composable("settings") { ApiSettingsScreen(navController) }
            composable("chat/{charName}") { backStackEntry ->
                val charName = backStackEntry.arguments?.getString("charName")
                val character = globalCharacterList.find { it.name == charName } ?: globalCharacterList[0]
                TavernChatScreen(character, navController)
            }
        }
        
        // 全局悬浮窗系统助手
        FloatingAssistant(navController)
    }
}

// =======================
// 悬浮助手 (Draggable Floating Assistant)
// =======================
@Composable
fun FloatingAssistant(navController: NavController) {
    val context = LocalContext.current
    var offsetX by remember { mutableStateOf(50f) }
    var offsetY by remember { mutableStateOf(300f) }
    var isExpanded by remember { mutableStateOf(false) }

    // 文件导入器
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val type = context.contentResolver.getType(uri)
            try {
                val chara: TavernCharacter? = if (type?.contains("image/png") == true) {
                    parsePngTavernCard(context, uri)
                } else {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        Gson().fromJson(InputStreamReader(inputStream), CharacterCardData::class.java)?.data
                    }
                }
                if (chara != null && chara.name.isNotBlank()) {
                    chara.category = "导入角色"
                    globalCharacterList.add(chara)
                    extractAndAddLorebook(chara, context) // 自动提取世界书
                    Toast.makeText(context, "角色 ${chara.name} 导入成功！", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "解析角色卡失败", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "导入异常", Toast.LENGTH_SHORT).show() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
        ) {
            // 展开的 5 个按钮
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF2C2C2C).copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                        .padding(8.dp)
                        .border(1.dp, Color(0xFF64B5F6), RoundedCornerShape(16.dp))
                ) {
                    AssistantButton("👤 导入角色") { importLauncher.launch("*/*"); isExpanded = false }
                    AssistantButton("📖 世界书") { 
                        globalWorldBook.add(LoreEntry(listOf("示例关键词"), "这是世界观设定补充"))
                        Toast.makeText(context, "添加了一条示例世界书设定！", Toast.LENGTH_SHORT).show()
                        isExpanded = false 
                    }
                    AssistantButton("⚙️ API控制") { navController.navigate("settings"); isExpanded = false }
                    AssistantButton("📜 脚本正则") { 
                        globalRegexRules.add(RegexRule("AI的废话", "替换内容"))
                        Toast.makeText(context, "添加了一条示例正则规则！", Toast.LENGTH_SHORT).show()
                        isExpanded = false 
                    }
                    AssistantButton(if (globalEnableRendering) "🖥️ 关闭代码渲染" else "🖥️ 开启代码渲染") { 
                        globalEnableRendering = !globalEnableRendering
                        Toast.makeText(context, "渲染已" + if(globalEnableRendering) "开启" else "关闭", Toast.LENGTH_SHORT).show()
                        isExpanded = false 
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 悬浮球本体 (带拖拽事件)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFF1976D2).copy(alpha = 0.85f), CircleShape)
                    .border(2.dp, Color.White, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .clickable { isExpanded = !isExpanded }
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "助手", tint = Color.White)
            }
        }
    }
}

@Composable
fun AssistantButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    )
}

// =======================
// 主页与设置页 (沿用旧版结构)
// =======================
@Composable fun HomeScreen(navController: NavController) { /* 折叠，同上个版本，核心导入逻辑已移至悬浮窗以全局可用 */
    val categories = listOf("全部") + globalCharacterList.map { it.category }.distinct()
    var selectedCategory by remember { mutableStateOf("全部") }
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (globalHomeBgUri != null) AsyncImage(model = globalHomeBgUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Text("选角大厅 (通过悬浮窗导入)", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(16.dp))
                LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { items(categories) { cat -> Surface(modifier = Modifier.padding(end = 8.dp).clickable { selectedCategory = cat }, shape = RoundedCornerShape(16.dp), color = if (cat == selectedCategory) Color(0xFF8B4513) else Color(0xFF2C2C2C)) { Text(cat, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } } }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    val filteredList = if (selectedCategory == "全部") globalCharacterList else globalCharacterList.filter { it.category == selectedCategory }
                    items(filteredList) { chara ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { navController.navigate("chat/${chara.name}") }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.85f))) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (chara.avatarUri != null) AsyncImage(model = Uri.parse(chara.avatarUri), contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)), contentScale = ContentScale.Crop)
                                else Box(modifier = Modifier.size(60.dp).background(Color(0xFF673AB7), shape = RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) { Text(chara.name.take(1), color = Color.White, fontSize = 24.sp) }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column { Text(chara.name, color = Color.White, style = MaterialTheme.typography.titleLarge); Text(chara.description, color = Color(0xFFCCCCCC), maxLines = 1) }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable fun ApiSettingsScreen(navController: NavController) { /* 折叠，同上个版本API设置页 */ 
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("← 返回", color = Color.White) }; Text("API 管理", style = MaterialTheme.typography.titleLarge, color = Color.White) }
        LazyColumn { items(globalApiConfigs) { api -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { currentActiveApi = api }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(api.name, color = Color.White); Text(api.baseUrl, color = Color.Gray, maxLines = 1) }; if (currentActiveApi.id == api.id) Icon(Icons.Filled.Settings, "已选中", tint = Color(0xFF4CAF50)) } } } }
    }
}

// =======================
// 智能聊天界面与请求
// =======================
@Composable
fun TavernChatScreen(character: TavernCharacter, navController: NavController) {
    // 动态拼接 System Prompt（含预设指令）
    val systemPrompt = """
        你是${character.name}。
        【设定】：${character.description}
        【性格】：${character.personality}
        【当前场景】：${character.scenario}
        
        【预设提示词/格式要求】：
        你每次回复必须严格遵守以下JSON加文本格式，并且所有动作神态用 *星号* 包裹：
        ```json
        { "affection": 55, "thoughts": "这人真有意思。", "appearance": "微微一笑" }
        ```
    """.trimIndent()

    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", character.firstMes))) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var characterState by remember { mutableStateOf(CharacterState()) }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp)) {
            CharacterStatusHud(characterState, character)
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(messages) { msg -> ChatBubble(message = msg, character = character); Spacer(modifier = Modifier.height(16.dp)) }
            }
            Surface(color = Color(0xFF1E1E1E).copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("发送消息...", color = Color.Gray) }, shape = RoundedCornerShape(24.dp), enabled = !isLoading,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF2C2C2C), unfocusedContainerColor = Color(0xFF2C2C2C), focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (inputText.isNotBlank() && !isLoading) {
                                val userText = inputText
                                messages = messages + ChatMessage("user", userText)
                                inputText = ""
                                isLoading = true
                                coroutineScope.launch {
                                    val rawAiReply = fetchAIReply(messages, systemPrompt, currentActiveApi)
                                    // 【正则脚本处理引擎】：对AI的原始回复进行清洗
                                    var cleanedReply = rawAiReply
                                    globalRegexRules.filter { it.isEnabled }.forEach { rule ->
                                        try {
                                            cleanedReply = cleanedReply.replace(Regex(rule.pattern), rule.replacement)
                                        } catch (e: Exception) {}
                                    }
                                    
                                    val (finalReply, newState) = parseAiState(cleanedReply)
                                    if (newState != null) characterState = newState
                                    messages = messages + ChatMessage("assistant", displayContent = finalReply, rawContent = rawAiReply)
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading, shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B4513))
                    ) { Text(if (isLoading) "..." else "发送", color = Color.White) }
                }
            }
        }
    }
}

// 世界书扫描引擎：根据聊天记录扫描关键词，动态插入提示词
fun scanWorldBook(history: List<ChatMessage>): String {
    val recentText = history.takeLast(3).joinToString(" ") { it.displayContent }
    val triggeredLore = StringBuilder()
    globalWorldBook.forEach { entry ->
        val trigger = entry.keys.any { key -> recentText.contains(key, ignoreCase = true) }
        if (trigger) {
            triggeredLore.append("[世界书设定附加]: ").append(entry.content).append("\n")
        }
    }
    return triggeredLore.toString()
}

suspend fun fetchAIReply(history: List<ChatMessage>, systemPrompt: String, apiConfig: ApiConfig): String {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            
            // 将自动触发的世界书设定，拼接到 System Prompt 中
            val injectedLore = scanWorldBook(history)
            val finalSystemPrompt = if (injectedLore.isNotEmpty()) "$systemPrompt\n\n$injectedLore" else systemPrompt
            
            val jsonBody = JSONObject().apply {
                put("model", apiConfig.modelName)
                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().apply { put("role", "system"); put("content", finalSystemPrompt) })
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
}

@Composable
fun CharacterStatusHud(state: CharacterState, character: TavernCharacter) { /* HUD保持不变 */ 
    Surface(modifier = Modifier.fillMaxWidth().padding(12.dp).border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f), RoundedCornerShape(16.dp)), color = Color(0xFF1E1E1E).copy(alpha = 0.95f), shape = RoundedCornerShape(16.dp), shadowElevation = 12.dp) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (character.avatarUri != null) AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)).border(2.dp, Color(0xFFE0E0E0).copy(alpha = 0.5f), RoundedCornerShape(30.dp)), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(60.dp).background(Color(0xFF673AB7), shape = RoundedCornerShape(30.dp)).border(2.dp, Color(0xFFE0E0E0).copy(alpha = 0.5f), RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) { Text(character.name.take(1), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("❤ 好感: ${state.affection}", color = Color(0xFFFF4081), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace))
                Spacer(modifier = Modifier.height(4.dp))
                Text("💭 [内心]：\"${state.thoughts}\"", color = Color(0xFF90CAF9), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, lineHeight = 18.sp))
            }
        }
    }
}

fun parseAiState(rawContent: String): Pair<String, CharacterState?> {
    val regex = "(?s)```json\\s*(\\{.*?\\})\\s*```(.*)".toRegex()
    val match = regex.find(rawContent)
    if (match != null) {
        try {
            val json = JSONObject(match.groupValues[1])
            return Pair(match.groupValues[2].trim(), CharacterState(json.optInt("affection", 50), json.optString("thoughts", "无"), json.optString("appearance", "无")))
        } catch (e: Exception) {}
    }
    return Pair(rawContent, null)
}

@Composable
fun ChatBubble(message: ChatMessage, character: TavernCharacter) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val markwon = remember { Markwon.builder(context).usePlugin(HtmlPlugin.create()).build() }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            if (character.avatarUri != null) AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(40.dp).background(Color(0xFF9C27B0), shape = RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text(character.name.take(1), color = Color.White) }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(if (isUser) "你" else character.name, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp))
            Box(modifier = Modifier.background(color = if (isUser) Color(0xFF2B5278).copy(alpha = 0.9f) else Color(0xFF2C2C2C).copy(alpha = 0.9f), shape = RoundedCornerShape(16.dp, 16.dp, if (isUser) 16.dp else 4.dp, if (isUser) 4.dp else 16.dp)).padding(16.dp).widthIn(max = 280.dp)) {
                // 【核心逻辑】根据全局开关控制是否进行代码渲染
                if (globalEnableRendering) {
                    AndroidView(modifier = Modifier.wrapContentSize(), factory = { ctx -> TextView(ctx).apply { setTextColor(android.graphics.Color.parseColor("#EFEFEF")); textSize = 16f; movementMethod = android.text.method.LinkMovementMethod.getInstance() } }, update = { textView -> markwon.setMarkdown(textView, message.displayContent) })
                } else {
                    Text(message.displayContent, color = Color(0xFFEFEFEF), fontSize = 16.sp)
                }
            }
        }
        if (isUser) { Spacer(modifier = Modifier.width(8.dp)); Box(modifier = Modifier.size(40.dp).background(Color(0xFF1976D2), shape = RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("我", color = Color.White) } }
    }
}

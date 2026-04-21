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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
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
// 数据模型
// =======================
data class ApiConfig(val id: String = java.util.UUID.randomUUID().toString(), var name: String, var category: String, var baseUrl: String, var apiKey: String, var modelName: String)
data class LoreEntry(val keys: List<String> = emptyList(), val content: String = "")
data class CharacterBook(val name: String = "", val entries: List<LoreEntry> = emptyList())
data class TavernCharacter(
    val name: String = "未知角色", val description: String = "", val personality: String = "", val scenario: String = "",
    @SerializedName("first_mes") val firstMes: String = "你好...", var category: String = "未分类",
    var avatarUri: String? = null, @SerializedName("character_book") val characterBook: CharacterBook? = null
)
data class CharacterCardData(val data: TavernCharacter?)
data class ChatMessage(val role: String, val displayContent: String, val rawContent: String = displayContent)
data class CharacterState(var affection: Int = 50, var thoughts: String = "正在观察你...", var appearance: String = "神态平静")
data class RegexRule(val pattern: String, val replacement: String, var isEnabled: Boolean = true)

// 全局状态管理
val globalApiConfigs = mutableStateListOf(
    ApiConfig(name = "DeepSeek官方", category = "默认", baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "替换为你的API_KEY", modelName = "deepseek-chat"),
    ApiConfig(name = "本地大模型", category = "本地部署", baseUrl = "http://192.168.1.100:1234/v1/chat/completions", apiKey = "lm-studio", modelName = "local-model")
)
var currentActiveApi by mutableStateOf(globalApiConfigs[0])
val globalCharacterList = mutableStateListOf(TavernCharacter("艾琳", "酒馆老板娘，热情好客", "泼辣、开朗", "红龙酒馆里", "呦，客人，想喝点什么？", "奇幻"))
var globalHomeBgUri by mutableStateOf<Uri?>(null)
val globalWorldBook = mutableStateListOf<LoreEntry>()
val globalRegexRules = mutableStateListOf<RegexRule>()
var globalEnableRendering by mutableStateOf(true)

// =======================
// PNG 解析引擎与模型拉取
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
                        val cardData = Gson().fromJson(jsonString, TavernCharacter::class.java)
                        return cardData?.apply { this.avatarUri = uri.toString() }
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
        Toast.makeText(context, "自动提取了 $count 条世界书设定！", Toast.LENGTH_SHORT).show()
    }
}

suspend fun pullAvailableModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val client = OkHttpClient.Builder().readTimeout(15, TimeUnit.SECONDS).build()
        val modelsUrl = if (baseUrl.endsWith("/chat/completions")) baseUrl.replace("/chat/completions", "/models")
                        else if (baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1/")) baseUrl.trimEnd('/') + "/models"
                        else "$baseUrl/models"
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
// 主入口与页面导航
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
        // 始终在最顶层的全局悬浮助手
        FloatingAssistant(navController)
    }
}

// =======================
// 悬浮助手 (五大核心控制)
// =======================
@Composable
fun FloatingAssistant(navController: NavController) {
    val context = LocalContext.current
    var offsetX by remember { mutableStateOf(50f) }
    var offsetY by remember { mutableStateOf(400f) }
    var isExpanded by remember { mutableStateOf(false) }

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
                    chara.category = if (type?.contains("image/png") == true) "PNG导入" else "JSON导入"
                    globalCharacterList.add(chara)
                    extractAndAddLorebook(chara, context)
                    Toast.makeText(context, "导入成功: ${chara.name}", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "未找到有效角色数据", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "导入异常", Toast.LENGTH_SHORT).show() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }) {
            AnimatedVisibility(visible = isExpanded) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(Color(0xFF2C2C2C).copy(alpha = 0.95f), RoundedCornerShape(16.dp)).padding(8.dp).border(1.dp, Color(0xFF64B5F6), RoundedCornerShape(16.dp))) {
                    AssistantButton("👤 导入角色") { importLauncher.launch("*/*"); isExpanded = false }
                    AssistantButton("📖 添加世界书示例") { globalWorldBook.add(LoreEntry(listOf("魔法", "龙"), "这是一个充满魔法与巨龙的奇幻世界。")); Toast.makeText(context, "已添加测试世界书", Toast.LENGTH_SHORT).show(); isExpanded = false }
                    AssistantButton("⚙️ API 管理") { navController.navigate("settings"); isExpanded = false }
                    AssistantButton("📜 添加正则脚本") { globalRegexRules.add(RegexRule("AI作为", "身为")); Toast.makeText(context, "已添加测试正则规则", Toast.LENGTH_SHORT).show(); isExpanded = false }
                    AssistantButton(if (globalEnableRendering) "🖥️ 关闭代码渲染" else "🖥️ 开启代码渲染") { globalEnableRendering = !globalEnableRendering; Toast.makeText(context, "代码渲染: $globalEnableRendering", Toast.LENGTH_SHORT).show(); isExpanded = false }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp).background(Color(0xFF1976D2).copy(alpha = 0.9f), CircleShape).border(2.dp, Color.White, CircleShape).pointerInput(Unit) {
                    detectDragGestures { change, dragAmount -> change.consume(); offsetX += dragAmount.x; offsetY += dragAmount.y }
                }.clickable { isExpanded = !isExpanded }
            ) { Icon(Icons.Filled.Settings, contentDescription = "助手", tint = Color.White) }
        }
    }
}

@Composable
fun AssistantButton(text: String, onClick: () -> Unit) {
    Text(text = text, color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp, horizontal = 16.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

// =======================
// 主页大厅
// =======================
@Composable
fun HomeScreen(navController: NavController) {
    var selectedCategory by remember { mutableStateOf("全部") }
    val categories = listOf("全部") + globalCharacterList.map { it.category }.distinct()
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri != null) globalHomeBgUri = uri }
    
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (globalHomeBgUri != null) {
            AsyncImage(model = globalHomeBgUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        }

        Scaffold(containerColor = Color.Transparent) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("我的酒馆", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Button(onClick = { bgLauncher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("换背景") }
                }
                LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    items(categories) { cat ->
                        Surface(modifier = Modifier.padding(end = 8.dp).clickable { selectedCategory = cat }, shape = RoundedCornerShape(16.dp), color = if (cat == selectedCategory) Color(0xFF8B4513) else Color(0xFF2C2C2C).copy(alpha = 0.8f)) {
                            Text(cat, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                val filteredList = if (selectedCategory == "全部") globalCharacterList else globalCharacterList.filter { it.category == selectedCategory }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    items(filteredList) { chara ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { navController.navigate("chat/${chara.name}") }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.85f))) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (chara.avatarUri != null) AsyncImage(model = Uri.parse(chara.avatarUri), contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)), contentScale = ContentScale.Crop)
                                else Box(modifier = Modifier.size(60.dp).background(Color(0xFF673AB7), shape = RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) { Text(chara.name.take(1), color = Color.White, fontSize = 24.sp) }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(chara.name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                                    Text("分类: ${chara.category}", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(chara.description, color = Color(0xFFCCCCCC), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =======================
// 设置页：API 管理 (支持拉取、删除)
// =======================
@Composable
fun ApiSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newBaseUrl by remember { mutableStateOf("https://api.openai.com/v1/chat/completions") }
    var newKey by remember { mutableStateOf("") }
    var newModel by remember { mutableStateOf("gpt-3.5-turbo") }

    val coroutineScope = rememberCoroutineScope()
    var isPulling by remember { mutableStateOf(false) }
    var pulledModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加 API 接口") },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("接口名称") })
                    OutlinedTextField(value = newBaseUrl, onValueChange = { newBaseUrl = it }, label = { Text("接口地址") })
                    OutlinedTextField(value = newKey, onValueChange = { newKey = it }, label = { Text("API Key") })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(value = newModel, onValueChange = { newModel = it }, label = { Text("模型名") }, modifier = Modifier.weight(1f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newBaseUrl.isBlank()) return@Button
                                coroutineScope.launch {
                                    isPulling = true
                                    Toast.makeText(context, "正在获取...", Toast.LENGTH_SHORT).show()
                                    val models = pullAvailableModels(newBaseUrl, newKey)
                                    if (models.isNotEmpty()) { pulledModels = models; newModel = models.first(); Toast.makeText(context, "成功获取", Toast.LENGTH_SHORT).show() }
                                    else Toast.makeText(context, "获取失败", Toast.LENGTH_SHORT).show()
                                    isPulling = false
                                }
                            }, enabled = !isPulling
                        ) { Text(if (isPulling) "..." else "拉取") }
                    }
                    if (pulledModels.isNotEmpty()) {
                        Box {
                            Button(onClick = { modelDropdownExpanded = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242))) { Text("选择模型 (${pulledModels.size})"); Icon(Icons.Filled.ArrowDropDown, null) }
                            DropdownMenu(expanded = modelDropdownExpanded, onDismissRequest = { modelDropdownExpanded = false }) {
                                pulledModels.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { newModel = m; modelDropdownExpanded = false }) }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { if (newName.isNotBlank() && newModel.isNotBlank()) { val newApi = ApiConfig(name = newName, category = "自定义", baseUrl = newBaseUrl, apiKey = newKey, modelName = newModel); globalApiConfigs.add(newApi); currentActiveApi = newApi; showAddDialog = false } }) { Text("保存") } },
            dismissButton = { Button(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("← 返回", color = Color.White) }; Text("API 管理", style = MaterialTheme.typography.titleLarge, color = Color.White) }
            Button(onClick = { newName=""; newBaseUrl=""; newKey=""; newModel=""; pulledModels=emptyList(); showAddDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) { Text("添加") }
        }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32))) { Column(modifier = Modifier.padding(16.dp)) { Text("✅ 当前生效", color = Color.White, fontWeight = FontWeight.Bold); Text("${currentActiveApi.name} - ${currentActiveApi.modelName}\n${currentActiveApi.baseUrl}", color = Color.White) } }
        LazyColumn {
            items(globalApiConfigs) { api ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { currentActiveApi = api }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) { Text(api.name, color = Color.White); Text(api.modelName, color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall); Text(api.baseUrl, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentActiveApi.id == api.id) Icon(Icons.Filled.Settings, "已选中", tint = Color(0xFF4CAF50))
                            Spacer(modifier = Modifier.width(16.dp))
                            IconButton(onClick = { if (globalApiConfigs.size > 1) { globalApiConfigs.remove(api); if (currentActiveApi.id == api.id) currentActiveApi = globalApiConfigs[0] } else Toast.makeText(context, "保留至少一个", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFE53935)) }
                        }
                    }
                }
            }
        }
    }
}

// =======================
// 聊天界面与 HUD
// =======================
@Composable
fun TavernChatScreen(character: TavernCharacter, navController: NavController) {
    val systemPrompt = """
        你是${character.name}。
        【设定】：${character.description}
        【性格】：${character.personality}
        【当前场景】：${character.scenario}
        
        【系统强制指令】：你每次回复必须严格遵守以下JSON加文本格式，且所有动作神态用 *星号* 包裹：
        ```json
        { "affection": 55, "thoughts": "这人真有意思。", "appearance": "微微一笑" }
        ```
    """.trimIndent()

    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", character.firstMes))) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var characterState by remember { mutableStateOf(CharacterState()) }
    var bgUri by remember { mutableStateOf<Uri?>(null) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> bgUri = uri }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (bgUri != null) { AsyncImage(model = bgUri, contentDescription = "背景", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop); Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) }

        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp)) {
            Surface(color = Color(0xFF1E1E1E).copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color.White) }; Column { Text(character.name, color = Color.White, fontWeight = FontWeight.Bold); Text(currentActiveApi.name, color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall) } }
                    Button(onClick = { launcher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("换壁纸") }
                }
            }

            CharacterStatusHud(state = characterState, character = character)

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(messages) { msg -> ChatBubble(message = msg, character = character); Spacer(modifier = Modifier.height(16.dp)) }
            }

            Surface(color = Color(0xFF1E1E1E).copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f), placeholder = { Text("发送消息...", color = Color.Gray) }, shape = RoundedCornerShape(24.dp), enabled = !isLoading, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF2C2C2C).copy(alpha = 0.8f), unfocusedContainerColor = Color(0xFF2C2C2C).copy(alpha = 0.8f), focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            val userText = inputText
                            messages = messages + ChatMessage("user", userText)
                            inputText = ""
                            isLoading = true
                            coroutineScope.launch {
                                val rawAiReply = fetchAIReply(messages, systemPrompt, currentActiveApi)
                                // 【正则脚本清洗引擎】
                                var cleanedReply = rawAiReply
                                globalRegexRules.filter { it.isEnabled }.forEach { rule -> try { cleanedReply = cleanedReply.replace(Regex(rule.pattern), rule.replacement) } catch (e: Exception) {} }
                                val (finalReply, newState) = parseAiState(cleanedReply)
                                if (newState != null) characterState = newState
                                messages = messages + ChatMessage("assistant", displayContent = finalReply, rawContent = rawAiReply)
                                isLoading = false
                            }
                        }
                    }, enabled = !isLoading, shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B4513))) { Text(if (isLoading) "..." else "发送", color = Color.White) }
                }
            }
        }
    }
}

// =======================
// HUD 游戏化面板
// =======================
@Composable
fun CharacterStatusHud(state: CharacterState, character: TavernCharacter) {
    Surface(modifier = Modifier.fillMaxWidth().padding(12.dp).border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f), RoundedCornerShape(16.dp)), color = Color(0xFF1E1E1E).copy(alpha = 0.95f), shape = RoundedCornerShape(16.dp), shadowElevation = 12.dp) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (character.avatarUri != null) AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)).border(2.dp, Color(0xFFE0E0E0).copy(alpha = 0.5f), RoundedCornerShape(30.dp)), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(60.dp).background(Color(0xFF673AB7), shape = RoundedCornerShape(30.dp)).border(2.dp, Color(0xFFE0E0E0).copy(alpha = 0.5f), RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) { Text(character.name.take(1), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("❤ 好感: ${state.affection}", color = Color(0xFFFF4081), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace))
                    Spacer(modifier = Modifier.width(8.dp))
                    val progress by animateFloatAsState(targetValue = state.affection / 100f)
                    LinearProgressIndicator(progress = progress, modifier = Modifier.height(6.dp).fillMaxWidth().clip(RoundedCornerShape(3.dp)), color = Color(0xFFFF4081), trackColor = Color(0xFF333333))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("💭 [内心]：\"${state.thoughts}\"", color = Color(0xFF90CAF9), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif, lineHeight = 18.sp))
                Spacer(modifier = Modifier.height(4.dp))
                Text("👀 [外貌]：${state.appearance}", color = Color(0xFFA5D6A7), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Light, letterSpacing = 1.2.sp))
            }
        }
    }
}

// JSON 状态提取
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

// =======================
// 聊天气泡 (支持开关代码渲染)
// =======================
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
                // 根据悬浮窗全局开关渲染
                if (globalEnableRendering) AndroidView(modifier = Modifier.wrapContentSize(), factory = { ctx -> TextView(ctx).apply { setTextColor(android.graphics.Color.parseColor("#EFEFEF")); textSize = 16f; movementMethod = android.text.method.LinkMovementMethod.getInstance() } }, update = { textView -> markwon.setMarkdown(textView, message.displayContent) })
                else Text(message.displayContent, color = Color(0xFFEFEFEF), fontSize = 16.sp)
            }
        }
        if (isUser) { Spacer(modifier = Modifier.width(8.dp)); Box(modifier = Modifier.size(40.dp).background(Color(0xFF1976D2), shape = RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("我", color = Color.White) } }
    }
}

// 世界书自动匹配注入
fun scanWorldBook(history: List<ChatMessage>): String {
    val recentText = history.takeLast(3).joinToString(" ") { it.displayContent }
    val triggeredLore = java.lang.StringBuilder()
    globalWorldBook.forEach { entry ->
        if (entry.keys.any { key -> recentText.contains(key, ignoreCase = true) }) triggeredLore.append("[世界设定补充]: ").append(entry.content).append("\n")
    }
    return triggeredLore.toString()
}

// 网络请求
suspend fun fetchAIReply(history: List<ChatMessage>, systemPrompt: String, apiConfig: ApiConfig): String {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
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

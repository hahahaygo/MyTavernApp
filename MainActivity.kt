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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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

// =======================
// 数据模型
// =======================
data class ApiConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String, var category: String, var baseUrl: String, var apiKey: String, var modelName: String
)

data class TavernCharacter(
    val name: String = "未知角色", val description: String = "", val personality: String = "",
    val scenario: String = "", @SerializedName("first_mes") val firstMes: String = "你好...",
    var category: String = "未分类", var avatarUri: String? = null // 新增头像URI字段
)
data class CharacterCardData(val data: TavernCharacter?)
data class ChatMessage(val role: String, val displayContent: String, val rawContent: String = displayContent)
data class CharacterState(var affection: Int = 50, var thoughts: String = "正在观察你...", var appearance: String = "神态平静")

// 全局状态
val globalApiConfigs = mutableStateListOf(
    ApiConfig(name = "DeepSeek官方", category = "国内大模型", baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "你的KEY", modelName = "deepseek-chat"),
    ApiConfig(name = "本地大模型", category = "本地部署", baseUrl = "http://192.168.1.100:1234/v1/chat/completions", apiKey = "lm-studio", modelName = "local-model")
)
var currentActiveApi by mutableStateOf(globalApiConfigs[0])
val globalCharacterList = mutableStateListOf(
    TavernCharacter("艾琳", "酒馆老板娘", "泼辣、开朗", "红龙酒馆里", "呦，客人，想喝点什么？", "奇幻")
)
var globalHomeBgUri by mutableStateOf<Uri?>(null) // 主页背景

// =======================
// PNG 角色卡解析器 (硬核提取 tEXt chunk)
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
                    val chunkData = bytes.copyOfRange(offset + 8, offset + 8 + length)
                    val textString = String(chunkData, StandardCharsets.UTF_8)
                    if (textString.startsWith("chara\u0000")) {
                        val base64Data = textString.substring(6) // 跳过 'chara' 和 null 字符
                        val jsonString = String(Base64.decode(base64Data, Base64.DEFAULT), StandardCharsets.UTF_8)
                        val cardData = Gson().fromJson(jsonString, TavernCharacter::class.java)
                        // 若解析的并非外层包有 "data" 的标准V2，可能是直接的角色属性
                        return cardData.apply { this.avatarUri = uri.toString() }
                    }
                }
                offset += 12 + length
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    return null
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TavernAppNavigation() } }
    }
}

@Composable
fun TavernAppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen(navController) }
        composable("settings") { ApiSettingsScreen(navController) }
        composable("chat/{charName}") { backStackEntry ->
            val charName = backStackEntry.arguments?.getString("charName")
            val character = globalCharacterList.find { it.name == charName } ?: globalCharacterList[0]
            TavernChatScreen(character, navController)
        }
    }
}

// =======================
// 主页 (选角大厅)
// =======================
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("全部") }
    val categories = listOf("全部") + globalCharacterList.map { it.category }.distinct()
    
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri != null) globalHomeBgUri = uri }
    
    // 角色导入器：同时支持 JSON 和 PNG
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val type = context.contentResolver.getType(uri)
            if (type?.contains("image/png") == true) {
                val chara = parsePngTavernCard(context, uri)
                if (chara != null && chara.name.isNotBlank()) {
                    chara.category = "PNG导入"
                    globalCharacterList.add(chara)
                    Toast.makeText(context, "PNG导入成功: ${chara.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "未在PNG中找到角色数据", Toast.LENGTH_SHORT).show()
                }
            } else {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val cardData = Gson().fromJson(InputStreamReader(inputStream), CharacterCardData::class.java)
                        if (cardData?.data != null) {
                            cardData.data.category = "JSON导入"
                            globalCharacterList.add(cardData.data)
                            Toast.makeText(context, "JSON导入成功", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) { Toast.makeText(context, "解析失败", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (globalHomeBgUri != null) {
            AsyncImage(model = globalHomeBgUri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        }

        Scaffold(
            containerColor = Color.Transparent,
            floatingActionButton = {
                FloatingActionButton(onClick = { importLauncher.launch("*/*") }, containerColor = Color(0xFF8B4513), contentColor = Color.White) {
                    Icon(Icons.Filled.Add, "导入角色卡")
                }
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("我的酒馆", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Row {
                        Button(onClick = { bgLauncher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("换背景") }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { navController.navigate("settings") }) { Icon(Icons.Filled.Settings, "API设置", tint = Color.White) }
                    }
                }
                LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    items(categories) { category ->
                        val isSelected = category == selectedCategory
                        Surface(modifier = Modifier.padding(end = 8.dp).clickable { selectedCategory = category }, shape = RoundedCornerShape(16.dp), color = if (isSelected) Color(0xFF8B4513) else Color(0xFF2C2C2C).copy(alpha = 0.8f)) {
                            Text(category, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
                val filteredList = if (selectedCategory == "全部") globalCharacterList else globalCharacterList.filter { it.category == selectedCategory }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    items(filteredList) { chara ->
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { navController.navigate("chat/${chara.name}") }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.85f))) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                // 列表展示头像
                                if (chara.avatarUri != null) {
                                    AsyncImage(model = Uri.parse(chara.avatarUri), contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)), contentScale = ContentScale.Crop)
                                } else {
                                    Box(modifier = Modifier.size(60.dp).background(Color(0xFF673AB7), shape = RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) { Text(chara.name.take(1), color = Color.White, fontSize = 24.sp) }
                                }
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
// 自定义 API 设置页 (可自行添加/修改)
// =======================
@Composable
fun ApiSettingsScreen(navController: NavController) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newBaseUrl by remember { mutableStateOf("https://api.openai.com/v1/chat/completions") }
    var newKey by remember { mutableStateOf("") }
    var newModel by remember { mutableStateOf("gpt-3.5-turbo") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加自定义模型接口") },
            text = {
                Column {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("接口名称 (如: 我的本地模型)") })
                    OutlinedTextField(value = newBaseUrl, onValueChange = { newBaseUrl = it }, label = { Text("接口地址 (Base URL)") })
                    OutlinedTextField(value = newKey, onValueChange = { newKey = it }, label = { Text("API Key (无则留空)") })
                    OutlinedTextField(value = newModel, onValueChange = { newModel = it }, label = { Text("模型名称 (如: qwen-max)") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    globalApiConfigs.add(ApiConfig(name = newName, category = "自定义", baseUrl = newBaseUrl, apiKey = newKey, modelName = newModel))
                    showAddDialog = false
                }) { Text("保存") }
            },
            dismissButton = { Button(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("← 返回", color = Color.White) }
                Text("API 管理", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
            Button(onClick = { showAddDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))) { Text("添加接口") }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前生效的接口", color = Color.White, fontWeight = FontWeight.Bold)
                Text("名称: ${currentActiveApi.name} \n模型: ${currentActiveApi.modelName}\n地址: ${currentActiveApi.baseUrl}", color = Color.White)
            }
        }

        LazyColumn {
            items(globalApiConfigs) { api ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { currentActiveApi = api }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(api.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text("模型: ${api.modelName}", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                            Text(api.baseUrl, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        if (currentActiveApi.id == api.id) Icon(Icons.Filled.Settings, "已选中", tint = Color(0xFF4CAF50))
                    }
                }
            }
        }
    }
}

// =======================
// 聊天界面
// =======================
@Composable
fun TavernChatScreen(character: TavernCharacter, navController: NavController) {
    val systemPrompt = """
        你是${character.name}。
        【设定】：${character.description}
        【性格】：${character.personality}
        【当前场景】：${character.scenario}
        
        【系统强制指令】：你每次回复必须严格遵守以下JSON加文本格式，并且所有动作神态用 *星号* 包裹：
        ```json
        { "affection": 55, "thoughts": "这人真有意思。", "appearance": "微微一笑" }
        ```
        *擦了擦桌子* 欢迎光临！
    """.trimIndent()

    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", character.firstMes))) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var characterState by remember { mutableStateOf(CharacterState()) }
    var bgUri by remember { mutableStateOf<Uri?>(null) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> bgUri = uri }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        if (bgUri != null) {
            AsyncImage(model = bgUri, contentDescription = "背景", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(color = Color(0xFF1E1E1E).copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color.White) }
                        Column { Text(character.name, color = Color.White, fontWeight = FontWeight.Bold); Text(currentActiveApi.name, color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall) }
                    }
                    Button(onClick = { launcher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) { Text("换壁纸") }
                }
            }

            CharacterStatusHud(characterState, character)

            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(messages) { msg -> ChatBubble(message = msg, character = character); Spacer(modifier = Modifier.height(16.dp)) }
            }

            Surface(color = Color(0xFF1E1E1E).copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("发送消息...", color = Color.Gray) }, shape = RoundedCornerShape(24.dp), enabled = !isLoading,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF2C2C2C).copy(alpha = 0.8f), unfocusedContainerColor = Color(0xFF2C2C2C).copy(alpha = 0.8f), focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent)
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
                                    val (cleanReply, newState) = parseAiState(rawAiReply)
                                    if (newState != null) characterState = newState
                                    messages = messages + ChatMessage("assistant", displayContent = cleanReply, rawContent = rawAiReply)
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

// 状态面板 (支持头像显示)
@Composable
fun CharacterStatusHud(state: CharacterState, character: TavernCharacter) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp).border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
        color = Color(0xFF1E1E1E).copy(alpha = 0.95f), shape = RoundedCornerShape(16.dp), shadowElevation = 12.dp
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 解析并显示自定义头像
            if (character.avatarUri != null) {
                AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)).border(2.dp, Color(0xFFE0E0E0).copy(alpha = 0.5f), RoundedCornerShape(30.dp)), contentScale = ContentScale.Crop)
            } else {
                Box(modifier = Modifier.size(60.dp).background(Color(0xFF673AB7), shape = RoundedCornerShape(30.dp)).border(2.dp, Color(0xFFE0E0E0).copy(alpha = 0.5f), RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) {
                    Text(character.name.take(1), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                }
            }
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
            if (character.avatarUri != null) { AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop) }
            else { Box(modifier = Modifier.size(40.dp).background(Color(0xFF9C27B0), shape = RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text(character.name.take(1), color = Color.White) } }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(if (isUser) "你" else character.name, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp))
            Box(modifier = Modifier.background(color = if (isUser) Color(0xFF2B5278).copy(alpha = 0.9f) else Color(0xFF2C2C2C).copy(alpha = 0.9f), shape = RoundedCornerShape(16.dp, 16.dp, if (isUser) 16.dp else 4.dp, if (isUser) 4.dp else 16.dp)).padding(16.dp).widthIn(max = 280.dp)) {
                AndroidView(modifier = Modifier.wrapContentSize(), factory = { ctx -> TextView(ctx).apply { setTextColor(android.graphics.Color.parseColor("#EFEFEF")); textSize = 16f; movementMethod = android.text.method.LinkMovementMethod.getInstance() } }, update = { textView -> markwon.setMarkdown(textView, message.displayContent) })
            }
        }
        if (isUser) { Spacer(modifier = Modifier.width(8.dp)); Box(modifier = Modifier.size(40.dp).background(Color(0xFF1976D2), shape = RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text("我", color = Color.White) } }
    }
}

suspend fun fetchAIReply(history: List<ChatMessage>, systemPrompt: String, apiConfig: ApiConfig): String {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            val jsonBody = JSONObject().apply {
                put("model", apiConfig.modelName)
                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                history.forEach { msg -> messagesArray.put(JSONObject().apply { put("role", msg.role); put("content", msg.rawContent) }) }
                put("messages", messagesArray)
            }
            val request = Request.Builder().url(apiConfig.baseUrl).addHeader("Authorization", "Bearer ${apiConfig.apiKey}").post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "API请求失败: ${response.code}，请检查设置页的接口配置！"
                return@withContext JSONObject(response.body?.string() ?: return@withContext "无数据").getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        } catch (e: Exception) { return@withContext "请求异常: ${e.message}" }
    }
}

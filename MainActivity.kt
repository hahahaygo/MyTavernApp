package com.example.tavernapp

import android.net.Uri
import android.os.Bundle
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
import java.util.concurrent.TimeUnit

// =======================
// 数据模型定义区
// =======================

// API 配置
data class ApiConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String,
    var category: String,
    var baseUrl: String,
    var apiKey: String,
    var modelName: String
)

// 酒馆角色卡 V2
data class TavernCharacter(
    val name: String = "未知角色",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerializedName("first_mes") val firstMes: String = "你好...",
    var category: String = "未分类"
)
data class CharacterCardData(val data: TavernCharacter?)

// 聊天消息 (分为展示内容和包含JSON的原始内容)
data class ChatMessage(val role: String, val displayContent: String, val rawContent: String = displayContent)

// 角色的动态状态HUD
data class CharacterState(
    var affection: Int = 50,
    var thoughts: String = "正在观察你...",
    var appearance: String = "神态平静"
)

// =======================
// 全局状态模拟数据库
// =======================

val globalApiConfigs = mutableStateListOf(
    ApiConfig(name = "DeepSeek官方", category = "国内大模型", baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "替换成你的API_KEY", modelName = "deepseek-chat"),
    ApiConfig(name = "本地大模型", category = "本地部署", baseUrl = "http://192.168.1.100:1234/v1/chat/completions", apiKey = "lm-studio", modelName = "local-model")
)
var currentActiveApi by mutableStateOf(globalApiConfigs[0])

val globalCharacterList = mutableStateListOf(
    TavernCharacter("艾琳", "酒馆老板娘，热情好客。喜欢幽默的人，讨厌粗鲁的客人。", "泼辣、开朗、有些傲娇", "你在红龙酒馆里，艾琳正在擦拭吧台", "呦，客人，今天想喝点什么？", "奇幻")
)

// =======================
// 主程序入口
// =======================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TavernAppNavigation()
            }
        }
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
// 页面一：主页 (选角大厅)
// =======================
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf("全部") }
    val categories = listOf("全部") + globalCharacterList.map { it.category }.distinct()
    
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val cardData = Gson().fromJson(InputStreamReader(inputStream), CharacterCardData::class.java)
                    if (cardData?.data != null) {
                        cardData.data.category = "新导入"
                        globalCharacterList.add(cardData.data)
                        Toast.makeText(context, "导入成功: ${cardData.data.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "解析失败，请确保是酒馆V2的JSON格式", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "读取错误", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { importLauncher.launch("application/json") }, containerColor = Color(0xFF8B4513), contentColor = Color.White) {
                Icon(Icons.Filled.Add, "导入角色卡")
            }
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("我的酒馆", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                IconButton(onClick = { navController.navigate("settings") }) {
                    Icon(Icons.Filled.Settings, "API设置", tint = Color.White)
                }
            }
            LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                items(categories) { category ->
                    val isSelected = category == selectedCategory
                    Surface(modifier = Modifier.padding(end = 8.dp).clickable { selectedCategory = category }, shape = RoundedCornerShape(16.dp), color = if (isSelected) Color(0xFF8B4513) else Color(0xFF2C2C2C)) {
                        Text(category, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            val filteredList = if (selectedCategory == "全部") globalCharacterList else globalCharacterList.filter { it.category == selectedCategory }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                items(filteredList) { chara ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { navController.navigate("chat/${chara.name}") }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(chara.name, color = Color.White, style = MaterialTheme.typography.titleLarge)
                            Text("分类: ${chara.category}", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(chara.description, color = Color(0xFFCCCCCC), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

// =======================
// 页面二：API 设置与管理
// =======================
@Composable
fun ApiSettingsScreen(navController: NavController) {
    var selectedApiCategory by remember { mutableStateOf("全部") }
    val apiCategories = listOf("全部") + globalApiConfigs.map { it.category }.distinct()

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                Text("← 返回", color = Color.White)
            }
            Text("API 接口管理", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前生效的接口", color = Color.White, fontWeight = FontWeight.Bold)
                Text("名称: ${currentActiveApi.name} (${currentActiveApi.modelName})", color = Color.White)
            }
        }
        LazyRow(modifier = Modifier.padding(bottom = 16.dp)) {
            items(apiCategories) { cat ->
                val isSelected = cat == selectedApiCategory
                Surface(modifier = Modifier.padding(end = 8.dp).clickable { selectedApiCategory = cat }, shape = RoundedCornerShape(16.dp), color = if (isSelected) Color(0xFF1976D2) else Color(0xFF2C2C2C)) {
                    Text(cat, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
        val filteredApiList = if (selectedApiCategory == "全部") globalApiConfigs else globalApiConfigs.filter { it.category == selectedApiCategory }
        LazyColumn {
            items(filteredApiList) { api ->
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { currentActiveApi = api }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                    Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(api.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text("分类: ${api.category} | 模型: ${api.modelName}", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
                            Text(api.baseUrl, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                        if (currentActiveApi.id == api.id) {
                            Icon(Icons.Filled.Settings, "已选中", tint = Color(0xFF4CAF50))
                        }
                    }
                }
            }
        }
    }
}

// =======================
// 页面三：沉浸式聊天与 HUD
// =======================
@Composable
fun TavernChatScreen(character: TavernCharacter, navController: NavController) {
    // 【系统指令】：强制输出 JSON 状态，且要求用星号包裹动作
    val systemPrompt = """
        你是${character.name}。
        【设定】：${character.description}
        【性格】：${character.personality}
        【当前场景】：${character.scenario}
        
        【系统强制指令】：
        你每次回复时，必须严格遵守以下格式，分为两部分：
        第一部分：使用一个 JSON 代码块来表达你的内部状态。好感度是0到100的整数。
        第二部分：你实际对客人说出的话和动作描写。所有的动作、神态描写请务必用 *星号* 包裹起来（例如：*微笑着看着你*）。
        
        严格参考如下格式输出：
        ```json
        {
          "affection": 55,
          "thoughts": "这个客人真有趣，想多聊聊。",
          "appearance": "嘴角微微上扬，眼神温柔"
        }
        ```
        *擦了擦桌子* 欢迎光临，客人！
    """.trimIndent()

    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", character.firstMes))) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var characterState by remember { mutableStateOf(CharacterState()) }
    var bgUri by remember { mutableStateOf<Uri?>(null) }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> bgUri = uri }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        // 背景渲染
        if (bgUri != null) {
            AsyncImage(model = bgUri, contentDescription = "背景", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部栏
            Surface(color = Color(0xFF1E1E1E).copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) {
                            Text("←", color = Color.White)
                        }
                        Column {
                            Text(character.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(currentActiveApi.name, color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = { launcher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
                        Text("换壁纸")
                    }
                }
            }

            // 【状态面板 HUD 渲染】
            CharacterStatusHud(characterState, character.name)

            // 聊天区
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                items(messages) { msg -> 
                    ChatBubble(message = msg, charName = character.name)
                    Spacer(modifier = Modifier.height(16.dp)) 
                }
            }

            // 底部输入区
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

// =======================
// 核心 UI：科幻/文学风状态 HUD
// =======================
@Composable
fun CharacterStatusHud(state: CharacterState, name: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .border(
                width = 1.dp, 
                color = Color(0xFF4CAF50).copy(alpha = 0.3f), // 荧光绿科幻边框
                shape = RoundedCornerShape(16.dp)
            ),
        color = Color(0xFF1E1E1E).copy(alpha = 0.95f),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 12.dp
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 头像
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color(0xFF673AB7), shape = RoundedCornerShape(30.dp))
                    .border(2.dp, Color(0xFFE0E0E0).copy(alpha = 0.5f), RoundedCornerShape(30.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1), 
                    color = Color.White, 
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                // 1. 好感度血条 (等宽机器码风)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "❤ 好感度: ${state.affection}/100", 
                        color = Color(0xFFFF4081), 
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val progress by animateFloatAsState(targetValue = state.affection / 100f)
                    LinearProgressIndicator(
                        progress = progress, modifier = Modifier.height(6.dp).fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFFFF4081), trackColor = Color(0xFF333333)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 2. 心理想法 (衬线斜体文学风)
                Text(
                    text = "💭 [内心]：\"${state.thoughts}\"", 
                    color = Color(0xFF90CAF9),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 18.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                // 3. 外貌动作 (细字重旁白风)
                Text(
                    text = "👀 [外貌]：${state.appearance}", 
                    color = Color(0xFFA5D6A7),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.2.sp
                    )
                )
            }
        }
    }
}

// =======================
// 核心逻辑：JSON 拦截与提取
// =======================
fun parseAiState(rawContent: String): Pair<String, CharacterState?> {
    val regex = "(?s)```json\\s*(\\{.*?\\})\\s*```(.*)".toRegex()
    val match = regex.find(rawContent)
    if (match != null) {
        try {
            val jsonStr = match.groupValues[1]
            val chatText = match.groupValues[2].trim()
            val json = JSONObject(jsonStr)
            val state = CharacterState(
                affection = json.optInt("affection", 50),
                thoughts = json.optString("thoughts", "无"),
                appearance = json.optString("appearance", "无")
            )
            return Pair(chatText, state)
        } catch (e: Exception) { e.printStackTrace() }
    }
    return Pair(rawContent, null)
}

// =======================
// 核心 UI：纯文字气泡与富文本渲染
// =======================
@Composable
fun ChatBubble(message: ChatMessage, charName: String) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val markwon = remember { Markwon.builder(context).usePlugin(HtmlPlugin.create()).build() }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) { Avatar(name = charName.take(1), color = Color(0xFF9C27B0)); Spacer(modifier = Modifier.width(8.dp)) }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(text = if (isUser) "你" else charName, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp, end = 4.dp))
            Box(modifier = Modifier.background(color = if (isUser) Color(0xFF2B5278).copy(alpha = 0.9f) else Color(0xFF2C2C2C).copy(alpha = 0.9f), shape = RoundedCornerShape(16.dp, 16.dp, if (isUser) 16.dp else 4.dp, if (isUser) 4.dp else 16.dp)).padding(16.dp).widthIn(max = 280.dp)) {
                AndroidView(
                    modifier = Modifier.wrapContentSize(),
                    factory = { ctx -> 
                        TextView(ctx).apply { 
                            // 气泡里的纯文字统一使用亮白色
                            setTextColor(android.graphics.Color.parseColor("#EFEFEF")) 
                            textSize = 16f
                            movementMethod = android.text.method.LinkMovementMethod.getInstance() 
                        } 
                    },
                    // 将干净的内容交给 Markwon 渲染，如果包含 *星号* 的动作描写，会自动渲染成斜体！
                    update = { textView -> markwon.setMarkdown(textView, message.displayContent) }
                )
            }
        }
        if (isUser) { Spacer(modifier = Modifier.width(8.dp)); Avatar(name = "我", color = Color(0xFF1976D2)) }
    }
}

@Composable 
fun Avatar(name: String, color: Color) { 
    Box(modifier = Modifier.size(40.dp).background(color, shape = RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { 
        Text(text = name, color = Color.White, style = MaterialTheme.typography.titleMedium) 
    } 
}

// =======================
// 网络请求：携带隐藏 JSON 记忆的会话
// =======================
suspend fun fetchAIReply(history: List<ChatMessage>, systemPrompt: String, apiConfig: ApiConfig): String {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            val jsonBody = JSONObject().apply {
                put("model", apiConfig.modelName)
                val messagesArray = JSONArray()
                messagesArray.put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                // 发送给 AI 的是 rawContent，里面包含了它上一次自己输出的 JSON 状态，这样它就能记住好感度！
                history.forEach { msg -> messagesArray.put(JSONObject().apply { put("role", msg.role); put("content", msg.rawContent) }) }
                put("messages", messagesArray)
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(apiConfig.baseUrl).addHeader("Authorization", "Bearer ${apiConfig.apiKey}").post(requestBody).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "网络错误: ${response.code}"
                val responseJson = JSONObject(response.body?.string() ?: return@withContext "无返回数据")
                return@withContext responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "请求异常: ${e.message}"
        }
    }
}

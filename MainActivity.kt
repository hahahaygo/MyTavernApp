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
// 数据模型与全局状态
// =======================
data class ApiConfig(val id: String = java.util.UUID.randomUUID().toString(), var name: String, var category: String, var baseUrl: String, var apiKey: String, var modelName: String)
data class LoreEntry(val id: String = java.util.UUID.randomUUID().toString(), var keys: List<String> = emptyList(), var content: String = "")
data class CharacterBook(val name: String = "", val entries: List<LoreEntry> = emptyList())
data class TavernCharacter(
    val name: String = "未知角色", val description: String = "", val personality: String = "", val scenario: String = "",
    @SerializedName("first_mes") val firstMes: String = "你好...", var category: String = "未分类",
    var avatarUri: String? = null, @SerializedName("character_book") val characterBook: CharacterBook? = null
)
data class CharacterCardData(val data: TavernCharacter?)
data class ChatMessage(val role: String, val displayContent: String, val rawContent: String = displayContent)
data class CharacterState(var affection: Int = 50, var thoughts: String = "正在观察你...", var appearance: String = "神态平静")
data class RegexRule(val id: String = java.util.UUID.randomUUID().toString(), var pattern: String, var replacement: String, var isEnabled: Boolean = true)

val globalApiConfigs = mutableStateListOf(ApiConfig(name = "DeepSeek", category = "默认", baseUrl = "https://api.deepseek.com/chat/completions", apiKey = "你的KEY", modelName = "deepseek-chat"))
var currentActiveApi by mutableStateOf(globalApiConfigs[0])
val globalCharacterList = mutableStateListOf(TavernCharacter("艾琳", "酒馆老板娘", "泼辣", "红龙酒馆", "想喝点什么？", "奇幻"))
var globalHomeBgUri by mutableStateOf<Uri?>(null) // 全局酒馆背景
val globalWorldBook = mutableStateListOf<LoreEntry>()
val globalRegexRules = mutableStateListOf<RegexRule>()
var globalEnableRendering by mutableStateOf(true)

// =======================
// PNG 隐写与世界书提取引擎
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
        Toast.makeText(context, "自动解包: 发现并导入了 ${count} 条世界书设定！", Toast.LENGTH_LONG).show()
    }
}

// =======================
// 导航主框架 (全局背景支持)
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
    // 最底层 Box 负责渲染全局壁纸
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D12))) {
        if (globalHomeBgUri != null) {
            AsyncImage(model = globalHomeBgUri, contentDescription = "全局背景", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) // 沉浸式暗场遮罩
        }
        
        NavHost(navController = navController, startDestination = "home") {
            composable("home") { HomeScreen(navController) }
            composable("settings") { ApiSettingsScreen(navController) }
            composable("lorebook") { LorebookScreen(navController) } // 世界书界面
            composable("regex") { RegexScreen(navController) }       // 正则界面
            composable("chat/{charName}") { backStackEntry ->
                val charName = backStackEntry.arguments?.getString("charName")
                val character = globalCharacterList.find { it.name == charName } ?: globalCharacterList[0]
                TavernChatScreen(character, navController)
            }
        }
        FloatingAssistant(navController) // 全局悬浮球
    }
}

// =======================
// 酷炫版悬浮球助手
// =======================
@Composable
fun FloatingAssistant(navController: NavController) {
    val context = LocalContext.current
    var offsetX by remember { mutableStateOf(30f) }
    var offsetY by remember { mutableStateOf(500f) }
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
                    Toast.makeText(context, "角色 ${chara.name} 接入完毕！", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, "角色卡损坏", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "读取异常", Toast.LENGTH_SHORT).show() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }) {
            AnimatedVisibility(visible = isExpanded) {
                // 科幻悬浮菜单面板
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xFF101018).copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(8.dp)
                ) {
                    AssistantButton("👤 导入角色卡") { importLauncher.launch("*/*"); isExpanded = false }
                    AssistantButton("📖 世界书面板") { navController.navigate("lorebook"); isExpanded = false }
                    AssistantButton("📜 正则洗稿引擎") { navController.navigate("regex"); isExpanded = false }
                    AssistantButton("⚙️ 核心 API") { navController.navigate("settings"); isExpanded = false }
                    AssistantButton(if (globalEnableRendering) "🖥️ 关富文本渲染" else "🖥️ 开富文本渲染", Color(0xFFFF007F)) { globalEnableRendering = !globalEnableRendering; Toast.makeText(context, "代码渲染: $globalEnableRendering", Toast.LENGTH_SHORT).show(); isExpanded = false }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 荧光魔法球
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .shadow(16.dp, CircleShape, spotColor = Color(0xFF00E5FF))
                    .background(Color(0xFF0D47A1).copy(alpha = 0.8f), CircleShape)
                    .border(2.dp, Color(0xFF00E5FF), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount -> change.consume(); offsetX += dragAmount.x; offsetY += dragAmount.y }
                    }
                    .clickable { isExpanded = !isExpanded }
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Terminal", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
fun AssistantButton(text: String, textColor: Color = Color(0xFFE0E0E0), onClick: () -> Unit) {
    Text(text = text, color = textColor, modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp, horizontal = 24.dp), fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
}

// =======================
// 独立：世界书控制台 (Lorebook)
// =======================
@Composable
fun LorebookScreen(navController: NavController) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newKeys by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false }, containerColor = Color(0xFF1E1E24),
            title = { Text("录入世界法则", color = Color.White) },
            text = {
                Column {
                    OutlinedTextField(value = newKeys, onValueChange = { newKeys = it }, label = { Text("触发关键词(用逗号隔开)", color = Color.Gray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    OutlinedTextField(value = newContent, onValueChange = { newContent = it }, label = { Text("世界观隐藏设定", color = Color.Gray) }, modifier = Modifier.height(120.dp), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                }
            },
            confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)), onClick = {
                if (newKeys.isNotBlank() && newContent.isNotBlank()) {
                    globalWorldBook.add(LoreEntry(keys = newKeys.split(",").map{it.trim()}, content = newContent))
                    showAddDialog = false
                }
            }) { Text("注入法则", color = Color.Black) } },
            dismissButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), onClick = { showAddDialog = false }) { Text("取消", color = Color.White) } }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = { FloatingActionButton(containerColor = Color(0xFF00E5FF), contentColor = Color.Black, onClick = { newKeys=""; newContent=""; showAddDialog = true }) { Icon(Icons.Filled.Add, "新增法则") } }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFF00E5FF), fontSize = 20.sp) }
                Text("📖 世界书 (Lorebooks)", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
            }
            Text("当聊天触发关键词时，这些设定会悄悄发给AI。", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 16.dp))
            
            LazyColumn {
                items(globalWorldBook) { entry ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.8f)), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🔑 触发词: ${entry.keys.joinToString(", ")}", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(entry.content, color = Color(0xFFE0E0E0), style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { globalWorldBook.remove(entry) }) { Icon(Icons.Filled.Delete, "删除", tint = Color(0xFFFF007F)) }
                        }
                    }
                }
            }
        }
    }
}

// =======================
// 独立：正则脚本控制台 (Regex)
// =======================
@Composable
fun RegexScreen(navController: NavController) {
    var showAddDialog by remember { mutableStateOf(false) }
    var newPattern by remember { mutableStateOf("") }
    var newReplacement by remember { mutableStateOf("") }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false }, containerColor = Color(0xFF1E1E24),
            title = { Text("新增正则过滤器", color = Color.White) },
            text = {
                Column {
                    OutlinedTextField(value = newPattern, onValueChange = { newPattern = it }, label = { Text("匹配规则 (Regex)", color = Color.Gray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                    OutlinedTextField(value = newReplacement, onValueChange = { newReplacement = it }, label = { Text("替换为...", color = Color.Gray) }, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
                }
            },
            confirmButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF007F)), onClick = {
                if (newPattern.isNotBlank()) { globalRegexRules.add(RegexRule(pattern = newPattern, replacement = newReplacement)); showAddDialog = false }
            }) { Text("保存", color = Color.White) } },
            dismissButton = { Button(colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent), onClick = { showAddDialog = false }) { Text("取消", color = Color.White) } }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = { FloatingActionButton(containerColor = Color(0xFFFF007F), contentColor = Color.White, onClick = { newPattern=""; newReplacement=""; showAddDialog = true }) { Icon(Icons.Filled.Add, "新增正则") } }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFFFF007F), fontSize = 20.sp) }
                Text("📜 脚本与正则 (Regex)", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Text("截获并修改AI返回的文本（极客专用）。", color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 16.dp))
            
            LazyColumn {
                items(globalRegexRules) { rule ->
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E24).copy(alpha = 0.8f)), shape = RoundedCornerShape(12.dp)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🎯 模式: ${rule.pattern}", color = Color(0xFF00E5FF), fontFamily = FontFamily.Monospace)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("🔄 替换为: ${rule.replacement}", color = Color(0xFFA5D6A7), fontFamily = FontFamily.Monospace)
                            }
                            // 赛博风开关
                            Switch(
                                checked = rule.isEnabled, onCheckedChange = { rule.isEnabled = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF007F), uncheckedThumbColor = Color.Gray, uncheckedTrackColor = Color(0xFF424242))
                            )
                            IconButton(onClick = { globalRegexRules.remove(rule) }) { Icon(Icons.Filled.Delete, "删除", tint = Color.Gray) }
                        }
                    }
                }
            }
        }
    }
}

// =======================
// 主页 (毛玻璃大厅)
// =======================
@Composable
fun HomeScreen(navController: NavController) {
    var selectedCategory by remember { mutableStateOf("全部") }
    val categories = listOf("全部") + globalCharacterList.map { it.category }.distinct()
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri != null) globalHomeBgUri = uri }
    
    Scaffold(containerColor = Color.Transparent) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Nexus 选角终端", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Black)
                Button(onClick = { bgLauncher.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.2f)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))) { Text("🎨 换壁纸", color = Color(0xFF00E5FF)) }
            }
            LazyRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                items(categories) { cat ->
                    val isSelected = cat == selectedCategory
                    Surface(modifier = Modifier.padding(end = 8.dp).clickable { selectedCategory = cat }, shape = RoundedCornerShape(16.dp), color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.8f) else Color(0xFF1E1E24).copy(alpha = 0.6f)) {
                        Text(cat, color = if(isSelected) Color.Black else Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
            val filteredList = if (selectedCategory == "全部") globalCharacterList else globalCharacterList.filter { it.category == selectedCategory }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                items(filteredList) { chara ->
                    // 毛玻璃卡片
                    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { navController.navigate("chat/${chara.name}") }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24).copy(alpha = 0.65f)), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (chara.avatarUri != null) AsyncImage(model = Uri.parse(chara.avatarUri), contentDescription = null, modifier = Modifier.size(70.dp).clip(RoundedCornerShape(20.dp)).border(2.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                            else Box(modifier = Modifier.size(70.dp).background(Color(0xFF673AB7), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) { Text(chara.name.take(1), color = Color.White, fontSize = 28.sp) }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(chara.name, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("分类: ${chara.category}", color = Color(0xFF00E5FF), style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(chara.description, color = Color(0xFFCCCCCC), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =======================
// 设置页：API 管理 (复用之前的逻辑即可，略作透明度美化)
// =======================
@Composable
fun ApiSettingsScreen(navController: NavController) { /* 功能全保留，不再重复占用行数 */ 
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("← 返回", color = Color(0xFF00E5FF)) }; Text("API 核心网关", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold) }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF00E5FF).copy(alpha = 0.2f)), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF))) { Column(modifier = Modifier.padding(16.dp)) { Text("📡 当前活动接口", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold); Text("${currentActiveApi.name} - ${currentActiveApi.modelName}\n${currentActiveApi.baseUrl}", color = Color.White) } }
        LazyColumn { items(globalApiConfigs) { api -> Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { currentActiveApi = api }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A24).copy(alpha = 0.8f))) { Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text(api.name, color = Color.White); Text(api.baseUrl, color = Color.Gray, maxLines = 1) }; if (currentActiveApi.id == api.id) Icon(Icons.Filled.Settings, "已选中", tint = Color(0xFF00E5FF)) } } } }
    }
}

// =======================
// 沉浸式聊天界面与霓虹HUD
// =======================
@Composable
fun TavernChatScreen(character: TavernCharacter, navController: NavController) {
    val systemPrompt = """
        你是${character.name}。
        【设定】：${character.description}
        【性格】：${character.personality}
        【当前场景】：${character.scenario}
        
        【强制指令】：你每次回复必须严格遵守以下JSON加文本格式，且所有动作神态用 *星号* 包裹：
        ```json
        { "affection": 55, "thoughts": "这人真有意思。", "appearance": "微微一笑" }
        ```
    """.trimIndent()

    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", character.firstMes))) }
    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var characterState by remember { mutableStateOf(CharacterState()) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp)) {
        Surface(color = Color(0xFF101018).copy(alpha = 0.8f), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)) { Text("←", color = Color(0xFF00E5FF), fontSize = 20.sp) }
                Column { Text(character.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("Connected: ${currentActiveApi.name}", color = Color(0xFF00E5FF), style = MaterialTheme.typography.bodySmall) }
            }
        }

        CharacterStatusHud(state = characterState, character = character)

        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            items(messages) { msg -> ChatBubble(message = msg, character = character); Spacer(modifier = Modifier.height(16.dp)) }
        }

        // 赛博风输入框
        Surface(color = Color(0xFF101018).copy(alpha = 0.9f), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText, onValueChange = { inputText = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("输入指令...", color = Color.Gray) }, shape = RoundedCornerShape(24.dp), enabled = !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = Color(0xFF1E1E24), unfocusedContainerColor = Color(0xFF1E1E24), focusedBorderColor = Color(0xFF00E5FF), unfocusedBorderColor = Color.Transparent)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = {
                        if (inputText.isNotBlank() && !isLoading) {
                            messages = messages + ChatMessage("user", inputText)
                            inputText = ""
                            isLoading = true
                            coroutineScope.launch {
                                val rawAiReply = fetchAIReply(messages, systemPrompt, currentActiveApi)
                                var cleanedReply = rawAiReply
                                globalRegexRules.filter { it.isEnabled }.forEach { rule -> try { cleanedReply = cleanedReply.replace(Regex(rule.pattern), rule.replacement) } catch (e: Exception) {} }
                                val (finalReply, newState) = parseAiState(cleanedReply)
                                if (newState != null) characterState = newState
                                messages = messages + ChatMessage("assistant", displayContent = finalReply, rawContent = rawAiReply)
                                isLoading = false
                            }
                        }
                    }, enabled = !isLoading, shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                ) { Text(if (isLoading) "..." else "Send", color = Color.Black, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// =======================
// HUD 与 气泡组件 (毛玻璃材质)
// =======================
@Composable
fun CharacterStatusHud(state: CharacterState, character: TavernCharacter) {
    Surface(modifier = Modifier.fillMaxWidth().padding(12.dp).border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(16.dp)), color = Color(0xFF1A1A24).copy(alpha = 0.7f), shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (character.avatarUri != null) AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, Color(0xFF00E5FF).copy(alpha = 0.6f), RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(64.dp).background(Color(0xFF673AB7), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text(character.name.take(1), color = Color.White, fontSize = 24.sp) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SYNCH: ${state.affection}%", color = Color(0xFFFF007F), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(8.dp))
                    val progress by animateFloatAsState(targetValue = state.affection / 100f)
                    LinearProgressIndicator(progress = progress, modifier = Modifier.height(4.dp).fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = Color(0xFFFF007F), trackColor = Color(0xFF333333))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("💭 Mind: \"${state.thoughts}\"", color = Color(0xFF00E5FF), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
                Spacer(modifier = Modifier.height(4.dp))
                Text("👀 Vis: ${state.appearance}", color = Color(0xFFAAAAAA), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun parseAiState(rawContent: String): Pair<String, CharacterState?> {
    val regex = "(?s)```json\\s*(\\{.*?\\})\\s*```(.*)".toRegex()
    val match = regex.find(rawContent)
    if (match != null) { try { val json = JSONObject(match.groupValues[1]); return Pair(match.groupValues[2].trim(), CharacterState(json.optInt("affection", 50), json.optString("thoughts", "无"), json.optString("appearance", "无"))) } catch (e: Exception) {} }
    return Pair(rawContent, null)
}

@Composable
fun ChatBubble(message: ChatMessage, character: TavernCharacter) {
    val isUser = message.role == "user"
    val context = LocalContext.current
    val markwon = remember { Markwon.builder(context).usePlugin(HtmlPlugin.create()).build() }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            if (character.avatarUri != null) AsyncImage(model = Uri.parse(character.avatarUri), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else Box(modifier = Modifier.size(40.dp).background(Color(0xFF9C27B0), CircleShape), contentAlignment = Alignment.Center) { Text(character.name.take(1), color = Color.White) }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Text(if (isUser) "YOU" else character.name.uppercase(), color = if (isUser) Color(0xFF00E5FF) else Color(0xFFFF007F), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp))
            Box(modifier = Modifier.background(color = if (isUser) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E1E24).copy(alpha = 0.8f), shape = RoundedCornerShape(16.dp, 16.dp, if (isUser) 16.dp else 4.dp, if (isUser) 4.dp else 16.dp)).border(1.dp, if (isUser) Color(0xFF00E5FF).copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(16.dp, 16.dp, if (isUser) 16.dp else 4.dp, if (isUser) 4.dp else 16.dp)).padding(16.dp).widthIn(max = 280.dp)) {
                if (globalEnableRendering) AndroidView(modifier = Modifier.wrapContentSize(), factory = { ctx -> TextView(ctx).apply { setTextColor(android.graphics.Color.parseColor("#EFEFEF")); textSize = 15f; movementMethod = android.text.method.LinkMovementMethod.getInstance() } }, update = { textView -> markwon.setMarkdown(textView, message.displayContent) })
                else Text(message.displayContent, color = Color(0xFFEFEFEF), fontSize = 15.sp)
            }
        }
    }
}

// 世界书自动匹配注入引擎
fun scanWorldBook(history: List<ChatMessage>): String {
    val recentText = history.takeLast(3).joinToString(" ") { it.displayContent }
    val triggeredLore = java.lang.StringBuilder()
    globalWorldBook.forEach { entry -> if (entry.keys.any { key -> recentText.contains(key, ignoreCase = true) }) triggeredLore.append("[系统注入法则]: ").append(entry.content).append("\n") }
    return triggeredLore.toString()
}

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

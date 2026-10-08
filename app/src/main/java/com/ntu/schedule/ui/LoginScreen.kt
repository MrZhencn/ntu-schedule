package com.ntu.schedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntu.schedule.ui.theme.NtuBlue
import com.ntu.schedule.ui.theme.NtuBlueLight

/**
 * 登录表单。全屏登录页与「重新导入」对话框共用同一份实现 ——
 * 两个入口各写一套，早晚会出现「对话框里能勾记住密码、全屏页不能」这类不一致。
 *
 * @param rememberInitial 上次是否勾了「记住密码」（有保存的密码才为 true）
 * @param canRemember 本机能否加密保存密码；false 时开关置灰并说明原因，
 *                    而不是让用户勾了却什么都没发生
 * @param compact 对话框里的紧凑排版（小一点的行距与字号）
 */
@Composable
fun LoginForm(
    initialStudentId: String,
    initialPassword: String,
    rememberInitial: Boolean,
    canRemember: Boolean,
    loading: Boolean,
    compact: Boolean = false,
    onConfirm: (studentId: String, password: String, remember: Boolean) -> Unit,
) {
    var studentId by rememberSaveable(initialStudentId) { mutableStateOf(initialStudentId) }
    var password by rememberSaveable(initialPassword) { mutableStateOf(initialPassword) }
    var remember by rememberSaveable(rememberInitial, canRemember) {
        mutableStateOf(rememberInitial && canRemember)
    }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        if (loading) return
        focus.clearFocus()
        keyboard?.hide()
        onConfirm(studentId.trim(), password, remember)
    }

    val fieldShape = RoundedCornerShape(12.dp)
    val gap = if (compact) 10.dp else 14.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = studentId,
            onValueChange = { studentId = it.filter { c -> !c.isWhitespace() } },
            label = { Text("学号") },
            placeholder = { Text("请输入学号") },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            singleLine = true,
            enabled = !loading,
            shape = fieldShape,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(gap))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                    )
                }
            },
            singleLine = true,
            enabled = !loading,
            shape = fieldShape,
            visualTransformation = if (showPassword) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(gap))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.School,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "记住账号密码",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (canRemember) "用系统密钥库加密后存在本机，下次打开自动填好"
                    else "本机无法使用系统密钥库，不能安全保存密码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = remember,
                onCheckedChange = { remember = it },
                enabled = canRemember && !loading,
            )
        }

        Spacer(Modifier.height(gap + 2.dp))
        Button(
            onClick = { submit() },
            enabled = !loading,
            shape = fieldShape,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 44.dp else 50.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text("正在登录并读取课表…")
            } else {
                Text("登录并导入课表", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(gap))
        Text(
            "连续输错 5 次会锁定学校账号，请确认学号与密码无误后再提交。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 首次进入（本机还没有课表）时的整屏登录页。
 *
 * 做成整屏而不是弹窗：用户第一次打开 App 唯一要做的事就是登录，
 * 弹窗背后压着一片空白反而让人以为 App 坏了。
 */
@Composable
fun LoginScreen(
    initialStudentId: String,
    initialPassword: String,
    rememberInitial: Boolean,
    canRemember: Boolean,
    loading: Boolean,
    onConfirm: (studentId: String, password: String, remember: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(NtuBlue, NtuBlueLight),
                    startY = 0f,
                    endY = 620f,
                ),
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 渐变铺满整屏（含状态栏后面），但内容要躲开状态栏/手势条
                .safeDrawingPadding()
                .imePadding()
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(30.dp))
            Icon(
                Icons.Filled.School,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "南通大学课表",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "用教务系统账号登录，自动导入本学期课表",
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.86f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Text(
                        "登录教务系统",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "密码只用于本次登录；若勾选「记住账号密码」，"
                            + "会用系统密钥库加密后保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    LoginForm(
                        initialStudentId = initialStudentId,
                        initialPassword = initialPassword,
                        rememberInitial = rememberInitial,
                        canRemember = canRemember,
                        loading = loading,
                        onConfirm = onConfirm,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "本 App 与学校官方无关，课表数据来自教务系统公开接口。",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
            // 署名放在页面最底下、免责声明之后：它属于「你可能想看一眼」的信息，
            // 不该抢在登录表单上面的黄金位置。
            Spacer(Modifier.height(16.dp))
            AuthorNotice(onGradient = true)
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * 「重新导入」用的对话框版本，包的是同一份 [LoginForm]。
 *
 * 导入过程中不允许取消：请求已经发出去了，把对话框关掉并不能撤回它，
 * 反而让用户以为没在跑而反复点。
 */
@Composable
fun ImportDialog(
    initialStudentId: String,
    initialPassword: String,
    rememberInitial: Boolean,
    canRemember: Boolean,
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (studentId: String, password: String, remember: Boolean) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = !loading,
            dismissOnClickOutside = !loading,
        ),
        title = {
            Text(
                if (loading) "正在导入…" else "导入课表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            LoginForm(
                initialStudentId = initialStudentId,
                initialPassword = initialPassword,
                rememberInitial = rememberInitial,
                canRemember = canRemember,
                loading = loading,
                compact = true,
                onConfirm = onConfirm,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("取消") }
        },
    )
}

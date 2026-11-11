package com.kongye.docs.autofill

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kongye.docs.KongyeApplication
import com.kongye.docs.data.Settings
import com.kongye.docs.data.ThemeMode
import com.kongye.docs.data.VaultEntry
import com.kongye.docs.data.VaultStore
import com.kongye.docs.ui.components.ConfirmDialog
import com.kongye.docs.ui.components.KongyeIcons
import com.kongye.docs.ui.theme.Kongye
import com.kongye.docs.ui.theme.KongyeTheme
import com.kongye.docs.vault.AppCerts
import com.kongye.docs.vault.CharClass
import com.kongye.docs.vault.PasswordRules
import com.kongye.docs.vault.VaultAccess
import com.kongye.docs.vault.VaultApi
import com.kongye.docs.vault.VaultUnlockActivity
import java.util.Calendar

/**
 * 自动填充弹出的界面（透明背景 + 底部卡片）：
 * - fill：选中下拉框里的一条 → 解锁 → 把账号密码交给系统
 * - more：「更多密码」→ 搜索 → 选中 → 解锁 → 交给系统，并关联当前 App / 网站
 * - generate：「生成强密码」→ 显示要求和生成的密码 → 填进新密码框和确认框
 * - save：提交后系统的保存提示 → 确认页（改名称、勾选字段）→ 存进密码空间
 * 窗口禁止截屏；账号密码只在内存里经过。
 */
class AutofillActivity : ComponentActivity() {
    private val mode get() = intent.getStringExtra(EXTRA_MODE).orEmpty()
    private val pkg get() = intent.getStringExtra(EXTRA_PKG).orEmpty()
    private val app get() = intent.getStringExtra(EXTRA_APP).orEmpty()
    private val domain get() = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
    private val label get() = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifEmpty { domain.ifEmpty { VaultApi.appLabel(this, pkg) ?: pkg } }

    @Suppress("DEPRECATION") private fun id(key: String): AutofillId? = intent.getParcelableExtra(key)

    /** 解锁后要填的那一条 */
    private var pendingEntry: String? = null
    private var certWarning by mutableStateOf<VaultEntry?>(null)

    private val unlock = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pendingEntry
        val e = id?.let { VaultStore.get(it) }
        if (result.resultCode == Activity.RESULT_OK && e != null && VaultAccess.isUnlocked(this)) deliver(e) else cancel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KongyeApplication.awaitReady()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Kongye.dark = darkNow()
        pendingEntry = savedInstanceState?.getString("pending")
        when (mode) {
            MODE_FILL -> {
                setContent { KongyeTheme { CertWarning() } }
                if (savedInstanceState == null) intent.getStringExtra(EXTRA_ENTRY)?.let { pick(it) } ?: cancel()
            }
            MODE_MORE -> setContent { KongyeTheme { Sheet(::cancel) { SearchContent() }; CertWarning() } }
            MODE_GENERATE -> setContent { KongyeTheme { Sheet(::cancel) { GenerateContent() } } }
            MODE_SAVE -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                val input = PendingSaves.peek(token)
                if (input == null) { Toast.makeText(this, "保存请求已过期", Toast.LENGTH_SHORT).show(); finish(); return }
                setContent { KongyeTheme { Sheet({ PendingSaves.take(token); finish() }) { SaveContent(token, input) } } }
            }
            else -> cancel()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingEntry?.let { outState.putString("pending", it) }
    }

    private fun darkNow(): Boolean {
        val st = Settings.value
        return when (st.theme) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            ThemeMode.SCHEDULE -> {
                val c = Calendar.getInstance()
                val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                if (st.darkFrom <= st.darkTo) now in st.darkFrom until st.darkTo else now >= st.darkFrom || now < st.darkTo
            }
        }
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    // ---------- 填充 ----------

    /** 选中一条：证书不一致先提醒；没解锁先解锁；然后交给系统 */
    private fun pick(entryId: String, certConfirmed: Boolean = false) {
        val e = VaultStore.get(entryId) ?: return cancel()
        if (!certConfirmed && !VaultApi.certOk(this, e, app)) { certWarning = e; return }
        if (!VaultAccess.isUnlocked(this)) {
            pendingEntry = e.id
            unlock.launch(Intent(this, VaultUnlockActivity::class.java)
                .putExtra(VaultUnlockActivity.EXTRA_CALLER, "自动填充 · $label")
                .putExtra(VaultUnlockActivity.EXTRA_ENTRY, e.id))
            return
        }
        deliver(e)
    }

    private fun deliver(e: VaultEntry) {
        val accountId = id(EXTRA_ACCOUNT_ID)
        val passwordId = id(EXTRA_PASSWORD_ID)
        val b = Dataset.Builder()
        var any = false
        @Suppress("DEPRECATION")
        if (accountId != null && e.account.isNotEmpty()) { b.setValue(accountId, AutofillValue.forText(e.account)); any = true }
        @Suppress("DEPRECATION")
        if (passwordId != null && e.password.isNotEmpty()) { b.setValue(passwordId, AutofillValue.forText(e.password)); any = true }
        if (!any) { Toast.makeText(this, "这一条没有可以填的账号或密码", Toast.LENGTH_SHORT).show(); return cancel() }
        // 记下使用时间；关联当前 App（带证书）或网站，下次直接推荐
        VaultStore.update(e.id) { VaultApi.bind(it.copy(lastUsedAt = System.currentTimeMillis()), app, domain, AppCerts.of(this, app)) }
        VaultApi.log(VaultApi.AUTOFILL_CALLER, "reveal", pkg, domain, e.name, VaultApi.OK)
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, b.build()))
        finish()
    }

    @Composable
    private fun CertWarning() {
        val e = certWarning ?: return
        ConfirmDialog(
            "签名和上次不同",
            "「$label」的签名和上次关联「${e.name}」时不一样，可能是同名的仿冒 App。确定是正版再填入。",
            "仍然填入", danger = true,
            onDismiss = { certWarning = null; if (mode == MODE_FILL) cancel() },
        ) { certWarning = null; pick(e.id, certConfirmed = true) }
    }

    @Composable
    private fun SearchContent() {
        var query by remember { mutableStateOf("") }
        val full = Settings.value.autofillFullAccount
        val shown = remember(query) {
            if (query.isBlank()) {
                val matched = VaultApi.match(app, domain) { AppCerts.of(this, it) }.map { it.first }
                (matched + VaultApi.fillable().sortedByDescending { it.lastUsedAt }).distinctBy { it.id }.take(40)
            } else VaultApi.search(query)
        }
        Title("选择要填入的密码", label)
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Field).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(KongyeIcons.Search, null, Modifier.size(18.dp), tint = Kongye.Icon2)
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = Kongye.Ink), cursorBrush = SolidColor(Kongye.Primary),
                modifier = Modifier.weight(1f).semantics { contentDescription = "搜索密码空间" },
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索名称、账号、网址", fontSize = 15.sp, color = Kongye.Sub); inner() } },
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            if (shown.isEmpty()) item { Text("没有找到", Modifier.fillMaxWidth().padding(28.dp), fontSize = 14.sp, color = Kongye.Sub, textAlign = TextAlign.Center) }
            items(shown, key = { it.id }) { e -> EntryRow(e, full) { pick(e.id) } }
        }
    }

    @Composable
    private fun EntryRow(e: VaultEntry, full: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(e.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (e.account.isBlank()) e.name else if (full) e.account else VaultApi.mask(e.account),
                    fontSize = 16.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("${e.name} · ********", fontSize = 13.sp, color = Kongye.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    // ---------- 生成 ----------

    @Composable
    private fun GenerateContent() {
        @Suppress("DEPRECATION") val base = remember { intent.getBundleExtra(EXTRA_RULES)?.let { PasswordRules.fromBundle(it) } ?: PasswordRules() }
        val stated = intent.getBooleanExtra(EXTRA_RULES_STATED, false)
        val symbolsAllowed = (base.allowed ?: CharClass.entries.toSet()).contains(CharClass.SYMBOL)
        var noSymbols by remember { mutableStateOf(false) }
        fun rules() = if (noSymbols) base.merge(PasswordRules(allowed = setOf(CharClass.LOWER, CharClass.UPPER, CharClass.DIGIT))) else base
        var generated by remember { mutableStateOf(PasswordRules.generate(rules())) }
        Title("生成强密码", label)
        Text(
            if (stated) "按页面要求：${generated.summary}" else "页面没写要求，按默认规则：${generated.summary}",
            Modifier.padding(horizontal = 20.dp), fontSize = 13.sp, color = Kongye.Text3,
        )
        if (!generated.satisfied) Text("页面的要求互相矛盾，只能尽量满足，填入后请留意页面提示", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 13.sp, color = Kongye.Danger)
        Box(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Kongye.Field).padding(horizontal = 14.dp, vertical = 16.dp)) {
            Text(generated.password, fontSize = 19.sp, fontFamily = FontFamily.Monospace, color = Kongye.Ink, modifier = Modifier.semantics { contentDescription = "生成的密码" })
        }
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("换一个") { generated = PasswordRules.generate(rules()) }
            if (symbolsAllowed) Chip(if (noSymbols) "✓ 不含符号" else "不含符号") { noSymbols = !noSymbols; generated = PasswordRules.generate(rules()) }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("填入这个密码") { useGenerated(generated.password) }
    }

    private fun useGenerated(password: String) {
        val newId = id(EXTRA_NEW_ID) ?: return cancel()
        val confirmId = id(EXTRA_CONFIRM_ID)
        val b = Dataset.Builder()
        @Suppress("DEPRECATION") b.setValue(newId, AutofillValue.forText(password))
        @Suppress("DEPRECATION") confirmId?.let { b.setValue(it, AutofillValue.forText(password)) }
        VaultApi.log(VaultApi.AUTOFILL_CALLER, "generate", pkg, domain, "", VaultApi.OK)
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, b.build()))
        finish()
    }

    // ---------- 保存 ----------

    @Composable
    private fun SaveContent(token: String, input: VaultApi.SaveInput) {
        val existing = remember { VaultApi.findExisting(input) }
        var name by remember { mutableStateOf(input.name.ifEmpty { input.domain.ifEmpty { "网页密码" } }) }
        val checked = remember { mutableStateListOf(*input.fields.map { true }.toTypedArray()) }
        val account = input.resolvedAccount
        Title(
            if (existing != null) "更新密码" else "保存到空叶文档",
            if (existing != null) "将更新「${existing.name}」的密码，旧密码会保留在条目里" else "将新建一条密码",
        )
        if (existing == null) {
            Text("名称", Modifier.padding(start = 20.dp, top = 4.dp), fontSize = 13.sp, color = Kongye.Text3)
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(8.dp)).background(Kongye.Field).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    name, { name = it.take(60) }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = Kongye.Ink), cursorBrush = SolidColor(Kongye.Primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "名称" },
                )
            }
        }
        InfoLine("账号", if (account.isEmpty()) "（没有）" else VaultApi.mask(account))
        InfoLine("密码", "********")
        input.fields.forEachIndexed { i, f ->
            if (f.value.trim() == account) return@forEachIndexed
            Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (checked[i]) "☑" else "☐", fontSize = 18.sp, color = if (checked[i]) Kongye.Primary else Kongye.Sub)
                Spacer(Modifier.width(10.dp))
                Text(f.storedLabel, fontSize = 15.sp, color = Kongye.Ink)
                Spacer(Modifier.weight(1f))
                Text(VaultApi.mask(f.value), fontSize = 14.sp, color = Kongye.Text3)
            }
        }
        if (input.domain.isNotEmpty()) InfoLine("网址", input.domain)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { SecondaryButton("不保存") { PendingSaves.take(token); finish() } }
            Box(Modifier.weight(1f)) {
                PrimaryButton(if (existing != null) "更新" else "保存", inset = false) {
                    val taken = PendingSaves.take(token) ?: run { finish(); return@PrimaryButton }
                    val final = taken.copy(name = name.trim(), fields = taken.fields.filterIndexed { i, _ -> checked.getOrElse(i) { true } })
                    runCatching { VaultApi.saveCredential(this@AutofillActivity, final) }
                        .onSuccess { r ->
                            VaultApi.log(VaultApi.AUTOFILL_CALLER, "save", pkg, final.domain, r.name, VaultApi.OK)
                            Toast.makeText(this@AutofillActivity, if (r.result == "updated") "已更新「${r.name}」" else "已存到空叶文档", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { Toast.makeText(this@AutofillActivity, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
                    finish()
                }
            }
        }
    }

    // ---------- 通用界面 ----------

    @Composable
    private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
        BackHandler(onBack = onDismiss)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(Kongye.Surface)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().imePadding().padding(bottom = 12.dp),
            ) { content() }
        }
    }

    @Composable
    private fun Title(title: String, subtitle: String) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = Kongye.Text, fontWeight = FontWeight.Medium)
                if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 13.sp, color = Kongye.Text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { if (mode == MODE_SAVE) { PendingSaves.take(intent.getStringExtra(EXTRA_TOKEN).orEmpty()); finish() } else cancel() }
                .semantics { contentDescription = "关闭" }, contentAlignment = Alignment.Center) {
                Icon(KongyeIcons.Close, null, Modifier.size(18.dp), tint = Kongye.Icon2)
            }
        }
    }

    @Composable
    private fun Avatar(name: String) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Kongye.PrimarySoft), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).ifEmpty { "#" }, fontSize = 16.sp, color = Kongye.Primary, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun InfoLine(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(label, fontSize = 15.sp, color = Kongye.Text2)
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 15.sp, color = Kongye.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun Chip(text: String, onClick: () -> Unit) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Kongye.Field).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(text, fontSize = 14.sp, color = Kongye.Text2)
        }
    }

    @Composable
    private fun PrimaryButton(text: String, inset: Boolean = true, onClick: () -> Unit) {
        Box(
            Modifier.then(if (inset) Modifier.padding(horizontal = 16.dp) else Modifier).fillMaxWidth().height(46.dp)
                .clip(RoundedCornerShape(10.dp)).background(Kongye.Primary).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium) }
    }

    @Composable
    private fun SecondaryButton(text: String, onClick: () -> Unit) {
        Box(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp)).background(Kongye.Field).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(text, fontSize = 16.sp, color = Kongye.Text2) }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_APP = "app"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_LABEL = "label"
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_ACCOUNT_ID = "accountId"
        const val EXTRA_PASSWORD_ID = "passwordId"
        const val EXTRA_NEW_ID = "newId"
        const val EXTRA_CONFIRM_ID = "confirmId"
        const val EXTRA_RULES = "rules"
        const val EXTRA_RULES_STATED = "rulesStated"
        const val EXTRA_TOKEN = "token"

        const val MODE_FILL = "fill"
        const val MODE_MORE = "more"
        const val MODE_GENERATE = "generate"
        const val MODE_SAVE = "save"
    }
}

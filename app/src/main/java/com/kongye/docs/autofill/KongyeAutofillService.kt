package com.kongye.docs.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.kongye.docs.KongyeApplication
import com.kongye.docs.MainActivity
import com.kongye.docs.R
import com.kongye.docs.data.Settings
import com.kongye.docs.data.VaultEntry
import com.kongye.docs.data.VaultStore
import com.kongye.docs.vault.AppCerts
import com.kongye.docs.vault.Browsers
import com.kongye.docs.vault.PasswordRules
import com.kongye.docs.vault.VaultApi
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统自动填充服务（需求文档 v2 第 9.1 节）：
 * - 登录页：输入框下的下拉框（安卓 11 起也能显示在键盘工具栏）列出匹配的账号，最后一行「更多密码」；
 * - 注册 / 改密码页：新密码框下「生成强密码 · 要求摘要」；
 * - 提交后：系统弹出保存提示，确认页里可以改名称、取消勾选字段。
 * 每一条都要先解锁（AutofillActivity）才把账号密码交给系统；这里只放打码后的账号。
 */
class KongyeAutofillService : AutofillService() {
    private val codes = AtomicInteger(0)

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        KongyeApplication.awaitReady()
        val response = try { build(request) } catch (_: Throwable) { null }
        if (cancellationSignal.isCanceled) return
        callback.onSuccess(response)
    }

    private fun ignored(pkg: String) = pkg.isEmpty() || pkg == packageName || pkg.startsWith("com.kongye.")

    private fun build(request: FillRequest): FillResponse? {
        if (!VaultStore.healthy) return null
        val read = StructureReader.read(request.fillContexts.last().structure)
        val page = read.page
        if (ignored(page.pkg)) return null
        val browser = Browsers.isBrowser(this, page.pkg)
        val app = if (browser) "" else page.pkg
        val domain = page.domain
        val account = page.account
        val login = page.loginPassword
        val newPassword = page.newPassword
        val confirm = page.confirm
        if (account == null && login == null && newPassword == null) return null

        val target = Target(page.pkg, app, domain, label = domain.ifEmpty { VaultApi.appLabel(this, page.pkg) ?: page.pkg })
        val builder = FillResponse.Builder()
        val inline = if (Build.VERSION.SDK_INT >= 30) InlineSlots(request) else null
        var datasets = 0

        // 注册 / 改密码：生成强密码，填进新密码框和确认框
        if (newPassword != null) {
            val rules = page.rules()
            val summary = PasswordRules.generate(rules).summary
            val ids = listOfNotNull(newPassword.id, confirm?.id)
            val intent = authIntent(AutofillActivity.MODE_GENERATE, target) {
                putExtra(AutofillActivity.EXTRA_NEW_ID, newPassword.id)
                confirm?.let { putExtra(AutofillActivity.EXTRA_CONFIRM_ID, it.id) }
                putExtra(AutofillActivity.EXTRA_RULES, rules.toBundle())
                putExtra(AutofillActivity.EXTRA_RULES_STATED, rules.stated)
            }
            builder.addDataset(dataset(ids, "生成强密码", summary, "+", intent, inline))
            datasets++
        }

        // 登录：匹配的账号，最后是「更多密码」
        val loginIds = listOfNotNull(account?.id, login?.id)
        if (loginIds.isNotEmpty()) {
            val full = Settings.value.autofillFullAccount
            val matches = VaultApi.match(app, domain) { AppCerts.of(this, it) }
                .filter { it.second != VaultApi.CERT_MISMATCH }.map { it.first }.take(6)
            for (e in matches) {
                val title = shownAccount(e, full)
                val intent = authIntent(AutofillActivity.MODE_FILL, target) {
                    putExtra(AutofillActivity.EXTRA_ENTRY, e.id)
                    account?.let { putExtra(AutofillActivity.EXTRA_ACCOUNT_ID, it.id) }
                    login?.let { putExtra(AutofillActivity.EXTRA_PASSWORD_ID, it.id) }
                }
                builder.addDataset(dataset(loginIds, title, "${e.name} · ********", e.name.trim().take(1).ifEmpty { "#" }, intent, inline))
                datasets++
            }
            val more = authIntent(AutofillActivity.MODE_MORE, target) {
                account?.let { putExtra(AutofillActivity.EXTRA_ACCOUNT_ID, it.id) }
                login?.let { putExtra(AutofillActivity.EXTRA_PASSWORD_ID, it.id) }
            }
            builder.addDataset(dataset(loginIds, "更多密码", "搜索空叶文档的密码空间", "⋯", more, inline))
            datasets++
        }

        // 保存：提交后系统交来所有字段
        val savePassword = newPassword ?: login
        var hasSave = false
        if (savePassword != null) {
            val optional = (page.all(FieldType.USERNAME, FieldType.PHONE, FieldType.EMAIL, FieldType.CURRENT, FieldType.CONFIRM, FieldType.PASSWORD).map { it.id } - savePassword.id).distinct()
            val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS
            val save = SaveInfo.Builder(type, arrayOf(savePassword.id))
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                .setDescription("保存到空叶文档")
            if (optional.isNotEmpty()) save.setOptionalIds(optional.toTypedArray())
            if (Build.VERSION.SDK_INT >= 28) read.submit?.let { save.setTriggerId(it) }
            builder.setSaveInfo(save.build())
            builder.setClientState(Bundle().apply {
                putString(STATE_KIND, page.kind.name)
                val rules = page.rules()
                if (rules.stated) putString(STATE_RULES, rules.copy(avoid = emptyList()).encode())
            })
            hasSave = true
        }
        if (datasets == 0 && !hasSave) return null
        return builder.build()
    }

    private fun shownAccount(e: VaultEntry, full: Boolean): String = when {
        e.account.isBlank() -> e.name
        full -> e.account
        else -> VaultApi.mask(e.account)
    }

    /** 下拉框里的一行：头像（首字）、标题、副标题 */
    private fun presentation(title: String, subtitle: String, avatar: String) = RemoteViews(packageName, R.layout.autofill_item).apply {
        setTextViewText(R.id.autofill_avatar, avatar)
        setTextViewText(R.id.autofill_title, title)
        setTextViewText(R.id.autofill_subtitle, subtitle)
        setContentDescription(R.id.autofill_root, "$title $subtitle")
    }

    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private fun dataset(ids: List<AutofillId>, title: String, subtitle: String, avatar: String, auth: IntentSender, inline: InlineSlots?): Dataset {
        val view = presentation(title, subtitle, avatar)
        val b = Dataset.Builder(view)
        val chip = inline?.next(title, subtitle)
        for (id in ids) {
            if (chip != null && Build.VERSION.SDK_INT >= 30) b.setValue(id, null, view, chip) else b.setValue(id, null, view)
        }
        b.setAuthentication(auth)
        return b.build()
    }

    private data class Target(val pkg: String, val app: String, val domain: String, val label: String)

    private fun authIntent(mode: String, target: Target, extras: Intent.() -> Unit): IntentSender {
        val intent = Intent(this, AutofillActivity::class.java)
            .putExtra(AutofillActivity.EXTRA_MODE, mode)
            .putExtra(AutofillActivity.EXTRA_PKG, target.pkg)
            .putExtra(AutofillActivity.EXTRA_APP, target.app)
            .putExtra(AutofillActivity.EXTRA_DOMAIN, target.domain)
            .putExtra(AutofillActivity.EXTRA_LABEL, target.label)
            .apply(extras)
        // 系统要往里面补内容，所以安卓 12 起要可变；Intent 是显式的，只能打开自己的界面
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getActivity(this, 0x4100 + codes.incrementAndGet() % 4000, intent, flags).intentSender
    }

    /** 安卓 11 起显示在键盘工具栏里的候选：数量和样式由键盘决定 */
    @RequiresApi(30)
    // getSlice 是 InlineSuggestionUi 官方示例的用法，只是被标成了库内部 API
    @SuppressLint("RestrictedApi")
    private inner class InlineSlots(request: FillRequest) {
        private val specs: List<InlinePresentationSpec> = request.inlineSuggestionsRequest?.inlinePresentationSpecs.orEmpty()
        private val max = request.inlineSuggestionsRequest?.maxSuggestionCount ?: 0
        private var used = 0

        fun next(title: String, subtitle: String): InlinePresentation? {
            if (specs.isEmpty() || used >= max) return null
            val spec = specs[minOf(used, specs.size - 1)]
            if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
            val attribution = PendingIntent.getActivity(
                this@KongyeAutofillService, 0x4000, Intent(this@KongyeAutofillService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val content = InlineSuggestionUi.newContentBuilder(attribution)
                .setTitle(title).setSubtitle(subtitle)
                .setStartIcon(Icon.createWithResource(this@KongyeAutofillService, R.drawable.ic_autofill_key))
                .setContentDescription("$title $subtitle")
                .build()
            used++
            return InlinePresentation(content.slice, spec, false)
        }
    }

    // ---------- 保存 ----------

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        KongyeApplication.awaitReady()
        try {
            val pages = request.fillContexts.map { StructureReader.read(it.structure).page }
            val last = pages.last()
            if (ignored(last.pkg) || !VaultStore.healthy) { callback.onSuccess(); return }
            val state = request.clientState
            val kind = runCatching { PageKind.valueOf(state?.getString(STATE_KIND).orEmpty()) }.getOrDefault(last.kind)
            // 分步登录：账号在前一页、密码在后一页，所以每一页都看
            val values = LinkedHashMap<FieldType, String>()
            var password = ""
            var current = ""
            for (p in pages) {
                p.fields.forEachIndexed { i, f ->
                    val v = f.value.trim()
                    if (v.isEmpty()) return@forEachIndexed
                    when (val t = p.types[i]) {
                        FieldType.USERNAME, FieldType.PHONE, FieldType.EMAIL -> values[t] = v
                        else -> Unit
                    }
                }
                (p.newPassword ?: p.loginPassword)?.value?.takeIf { it.isNotEmpty() }?.let { password = it }
                if (p.kind == PageKind.CHANGE) p.first(FieldType.CURRENT)?.value?.takeIf { it.isNotEmpty() }?.let { current = it }
            }
            if (password.isEmpty()) { callback.onSuccess(); return }
            val browser = Browsers.isBrowser(this, last.pkg)
            val domain = pages.lastOrNull { it.domain.isNotEmpty() }?.domain.orEmpty()
            val input = VaultApi.SaveInput(
                app = if (browser) "" else last.pkg, domain = domain,
                name = "", account = "", password = password,
                kind = when (kind) { PageKind.REGISTER -> "register"; PageKind.CHANGE -> "change"; PageKind.LOGIN -> "login" },
                fields = values.map { (t, v) -> VaultApi.SaveField(t.key, "", v) },
                currentPassword = current, rules = state?.getString(STATE_RULES).orEmpty(),
            )
            val existing = VaultApi.findExisting(input)
            // 用已存的同一个密码登录：不打扰，只记一下使用时间
            if (existing != null && existing.password == password) {
                VaultStore.update(existing.id) { it.copy(lastUsedAt = System.currentTimeMillis()) }
                callback.onSuccess(); return
            }
            if (Settings.value.autofillConfirmSave && Build.VERSION.SDK_INT >= 28) {
                val token = PendingSaves.put(input.copy(name = input.app.let { VaultApi.appLabel(this, it) } ?: domain))
                val intent = Intent(this, AutofillActivity::class.java)
                    .putExtra(AutofillActivity.EXTRA_MODE, AutofillActivity.MODE_SAVE)
                    .putExtra(AutofillActivity.EXTRA_TOKEN, token)
                    .putExtra(AutofillActivity.EXTRA_PKG, last.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val sender = PendingIntent.getActivity(this, 0x4F00, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
                callback.onSuccess(sender)
            } else {
                val r = VaultApi.saveCredential(this, input)
                VaultApi.log(VaultApi.AUTOFILL_CALLER, "save", last.pkg, domain, r.name, VaultApi.OK)
                android.widget.Toast.makeText(this, if (r.result == "updated") "已更新空叶文档里的密码" else "已存到空叶文档", android.widget.Toast.LENGTH_SHORT).show()
                callback.onSuccess()
            }
        } catch (t: Throwable) {
            callback.onFailure("空叶文档保存失败")
        }
    }

    companion object {
        const val STATE_KIND = "kongye.kind"
        const val STATE_RULES = "kongye.rules"
    }
}

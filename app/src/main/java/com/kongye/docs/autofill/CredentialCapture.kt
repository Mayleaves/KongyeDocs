package com.kongye.docs.autofill

import com.kongye.docs.vault.VaultApi
import com.kongye.docs.data.VaultEntry

/** Capture readable input fields across login steps; the confirmation sheet decides what to keep. */
internal object CredentialCapture {
    fun hasNewFields(input: VaultApi.SaveInput, existing: VaultEntry?): Boolean = input.fields
        .filter { it.value.isNotBlank() && (it.type!="username" || it.value.trim()!=input.resolvedAccount) }
        .any { field -> existing?.fields?.none { it.label==field.storedLabel && it.value==field.value.trim() }!=false }

    fun <T> collect(pages: List<Page<T>>, app: String, domain: String, kind: PageKind, rules: String=""): VaultApi.SaveInput? {
        val values=linkedMapOf<Pair<FieldType,T>,Pair<PageField<T>,FieldType>>()
        var passwordField: Pair<FieldType,T>?=null
        var password=""
        var current=""
        pages.forEach { page ->
            (page.newPassword ?: page.loginPassword)?.let { field ->
                if(field.value.isNotEmpty()) { password=field.value; passwordField=page.typeOf(field) to field.id }
            }
            if(page.kind==PageKind.CHANGE) page.first(FieldType.CURRENT)?.value?.takeIf { it.isNotEmpty() }?.let { current=it }
            page.fields.forEachIndexed { i,field ->
                val key=page.types[i] to field.id
                if(field.value.isNotBlank() || key !in values) values[key]=field to page.types[i]
            }
        }
        if(password.isEmpty()) return null
        val labels=mutableSetOf<String>()
        val fields=values.filterKeys { it!=passwordField }.values.map { (field,type) ->
            val base=when(type) {
                FieldType.USERNAME -> "用户名"; FieldType.PHONE -> "手机号"; FieldType.EMAIL -> "邮箱"
                FieldType.CURRENT -> "原密码"; FieldType.CONFIRM -> "确认密码"; FieldType.OTP -> "验证码"
                else -> listOf("label","aria-label","placeholder","title","name","id").firstNotNullOfOrNull { field.htmlAttrs[it]?.trim()?.takeIf(String::isNotEmpty) }
                    ?: field.label.trim().ifEmpty { "其他字段" }
            }.take(15)
            var label=base; var suffix=2
            while(!labels.add(label)) { label="$base ${suffix++}" }
            VaultApi.SaveField(if(type.isAccount && label==base) type.key else "other",label,field.value.take(500),type.isPassword || type==FieldType.OTP)
        }
        return VaultApi.SaveInput(app,domain,"","",password,
            kind=when(kind) { PageKind.LOGIN -> "login"; PageKind.REGISTER -> "register"; PageKind.CHANGE -> "change" },
            fields=fields,currentPassword=current,rules=rules)
    }
}

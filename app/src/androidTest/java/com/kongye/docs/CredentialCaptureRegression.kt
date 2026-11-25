package com.kongye.docs

import com.kongye.docs.autofill.*
import com.kongye.docs.data.*

internal object CredentialCaptureRegression {
    fun run(pass: (String)->Unit) {
        fun field(id: Int,label: String,value: String,type: String="text",autocomplete: String="")=
            PageField(id,label=label,value=value,htmlType=type,autocomplete=autocomplete)
        fun capture(vararg pages: Page<Int>)=CredentialCapture.collect(pages.toList(),"","capture.example",PageKind.REGISTER)
        val registration=Page("browser","capture.example",listOf(
            field(1,"用户名","demo-user",autocomplete="username"),
            field(2,"邮箱","first@example.test","email"),
            field(3,"手机号","13800000000","tel"),
            field(4,"公司名称","测试公司"),
            field(5,"短信验证码","123456"),
            field(6,"设置密码","  synthetic-password  ","password","new-password"),
            field(7,"确认密码","  synthetic-password  ","password"),field(8,"单位地址","")),emptyList())
        val input=capture(registration)!!
        check(input.resolvedAccount=="demo-user" && input.password=="  synthetic-password  ")
        check(input.fields.size==7 && input.fields.any { it.storedLabel=="单位地址" && it.value.isEmpty() })
        check(input.fields.any { it.type=="email" && it.value=="first@example.test" })
        check(input.fields.any { it.type=="phone" && it.value=="13800000000" })
        check(input.fields.any { it.storedLabel=="公司名称" && it.value=="测试公司" })
        check(input.fields.any { it.storedLabel=="验证码" && it.secret })
        check(input.fields.any { it.storedLabel=="确认密码" && it.secret })
        pass("registration-captures-email-phone-other-inputs-and-confirmation-fields-without-trimming-password")
        val first=Page("browser","capture.example",listOf(field(1,"用户名","first-step",autocomplete="username"),field(2,"邮箱","old@example.test","email")),emptyList())
        val last=Page("browser","capture.example",listOf(field(1,"密码","new-password","password"),field(2,"邮箱","updated@example.test","email"),field(3,"备用邮箱","second@example.test","email")),emptyList())
        val steps=capture(first,last)!!
        check(steps.resolvedAccount=="first-step" && steps.password=="new-password")
        check(steps.fields.none { it.value=="old@example.test" })
        check(steps.fields.filter { "邮箱" in it.storedLabel }.map { it.value }==listOf("updated@example.test","second@example.test"))
        check(steps.fields.map { it.storedLabel }.distinct().size==steps.fields.size)
        check(capture(first)==null)
        val choices=Page("browser","capture.example",listOf(
            field(1,"接收邮箱通知","是").copy(textInput=false),field(2,"密码","pass","password"),
            field(3,"所在城市","上海").copy(textInput=false)),emptyList())
        val choiceInput=capture(choices)!!
        check(choiceInput.resolvedAccount.isEmpty() && choiceInput.fields.map { it.value }==listOf("是","上海"))
        pass("multiple-login-steps-and-repeated-email-fields-keep-latest-values-and-distinct-labels")
        val stored=VaultEntry(type=VaultType.PASSWORD,name="测试",account=input.resolvedAccount,password=input.password,
            fields=input.fields.filter { it.type!="username" && it.value.isNotBlank() }.map { VaultField(label=it.storedLabel,value=it.value.trim(),secret=it.secret) })
        check(!CredentialCapture.hasNewFields(input,stored))
        check(CredentialCapture.hasNewFields(input.copy(fields=input.fields+com.kongye.docs.vault.VaultApi.SaveField("other","新增字段","新内容")),stored))
        pass("empty-optional-inputs-do-not-repeat-save-prompt-but-new-values-do")
    }
}

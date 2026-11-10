package com.kongye.docs.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.DialogInterface
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.widget.Toast
import com.kongye.docs.data.VaultStore

/**
 * 透明的解锁页（不进最近任务）：键盘 / 悬浮球 reveal 得到 locked 后 send() 返回的 PendingIntent 打开这里。
 * 用指纹验证，没有指纹或选择时用锁屏密码兜底；验证成功后解锁 5 分钟（VaultAccess），然后关掉自己回到原来的输入框。
 */
class VaultUnlockActivity : Activity() {
    private val cancel = CancellationSignal()
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean("asked") == true
        if (VaultAccess.isUnlocked(this)) return done(true)
        if (getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
            Toast.makeText(this, "请先在系统设置里设置锁屏密码或指纹，再解锁密码空间", Toast.LENGTH_LONG).show()
            return done(false)
        }
        if (!asked) { asked = true; authenticate() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("asked", asked)
    }

    private val callerLabel get() = intent.getStringExtra(EXTRA_CALLER)?.let { VaultApi.appLabel(this, it) ?: it } ?: "其他应用"
    private val entryName get() = intent.getStringExtra(EXTRA_ENTRY)?.let { VaultStore.get(it)?.name }

    private fun authenticate() {
        val subtitle = "允许「$callerLabel」读取" + (entryName?.let { "「$it」" } ?: "密码空间")
        val description = "解锁后 ${VaultAccess.UNLOCK_MILLIS / 60_000} 分钟内有效，锁屏立即失效"
        when {
            Build.VERSION.SDK_INT >= 30 -> BiometricPrompt.Builder(this)
                .setTitle("解锁密码空间").setSubtitle(subtitle).setDescription(description)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build().authenticate(cancel, mainExecutor, biometricCallback())
            Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION") BiometricPrompt.Builder(this)
                .setTitle("解锁密码空间").setSubtitle(subtitle).setDescription(description)
                .setDeviceCredentialAllowed(true)
                .build().authenticate(cancel, mainExecutor, biometricCallback())
            Build.VERSION.SDK_INT == 28 -> BiometricPrompt.Builder(this)
                .setTitle("解锁密码空间").setSubtitle(subtitle).setDescription(description)
                .setNegativeButton("使用锁屏密码", mainExecutor) { _: DialogInterface, _: Int -> askCredential(subtitle) }
                .build().authenticate(cancel, mainExecutor, biometricCallback())
            else -> askCredential(subtitle)
        }
    }

    /** 只在 Android 9+ 用到（Android 8 直接走锁屏密码），所以用到时才创建 */
    @androidx.annotation.RequiresApi(28)
    private fun biometricCallback() = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // Android 9 没录指纹：改用锁屏密码
            if (Build.VERSION.SDK_INT == 28 && (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT || errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE))
                askCredential("允许「$callerLabel」读取密码空间")
            else done(false)
        }
    }

    /** Android 8–9 的锁屏密码验证 */
    @Suppress("DEPRECATION")
    private fun askCredential(subtitle: String) {
        val intent = getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent("解锁密码空间", subtitle)
        if (intent == null) done(false) else startActivityForResult(intent, REQUEST_CREDENTIAL)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CREDENTIAL) done(resultCode == RESULT_OK)
    }

    private fun done(success: Boolean) {
        if (success) VaultAccess.unlock()
        setResult(if (success) RESULT_OK else RESULT_CANCELED)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) cancel.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CALLER = "caller"
        const val EXTRA_ENTRY = "entry"
        private const val REQUEST_CREDENTIAL = 0x5642
    }
}

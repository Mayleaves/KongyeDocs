package com.kongye.docs.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * App 签名证书的 SHA-256（需求文档 v2 第 7.4 节）。
 * 关联 App 时记下证书，以后只在证书相同时推荐，防止侧载一个同包名的假 App 骗走密码。
 */
object AppCerts {
    /** 取不到（没装、看不到）返回 null */
    fun of(context: Context, pkg: String): String? {
        if (pkg.isEmpty()) return null
        return runCatching {
            val pm = context.packageManager
            // 多签名的 App：全部证书排序后拼起来；单签名（可能轮换过密钥）：取当前证书，即历史里的最后一个
            val chosen = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
                if (info.hasMultipleSigners()) info.apkContentsSigners?.toList()
                else info.signingCertificateHistory?.lastOrNull()?.let { listOf(it) }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.toList()
            }
            if (chosen.isNullOrEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            chosen.map { sig -> digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted().joinToString(",")
        }.getOrNull()
    }

    /**
     * 这个条目能不能推荐给这个 App：
     * - 条目没记过这个 App 的证书（3.2 及以前关联的）→ 可以，下次关联时补记
     * - 记过且和现在的一样 → 可以
     * - 记过但不一样，或者现在取不到证书 → 不行
     */
    fun matches(recorded: String?, current: String?): Boolean = recorded == null || (current != null && recorded == current)
}

package com.kongye.docs.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.kongye.docs.KongyeApplication

/**
 * 密码空间对外接口：content://<applicationId>.vault，只用 call()，不提供表格查询。
 * 访问要有签名权限 com.kongye.permission.VAULT（Manifest 里的 android:permission），
 * call() 里再查一次权限并核对调用方白名单（VaultApi.allowed）。
 */
class VaultProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return Bundle().apply { putString("status", VaultApi.ERROR) }
        // Provider 比 Application.onCreate 先建好：等设置和密码空间读完再处理
        KongyeApplication.awaitReady()
        if (context.checkCallingOrSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
            return Bundle().apply { putString("status", VaultApi.DENIED) }
        return VaultApi.handle(context, callingPackage, method, extras)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val PERMISSION = "com.kongye.permission.VAULT"
    }
}

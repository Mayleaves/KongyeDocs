package com.kongye.docs

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.provider.DocumentsContract
import com.kongye.docs.data.*
import kotlinx.coroutines.runBlocking
import java.io.File

internal object VaultChangeBackupRegression {
    private const val PASSWORD = "SyntheticBackupPassword#2026"

    fun run(inst: Instrumentation, pass: (String) -> Unit) = runBlocking {
        val context = inst.targetContext
        val token = newId()
        val fixture = object : ContextWrapper(context) {
            private val root = File(context.filesDir, "vault-change-fixture-$token").apply { mkdirs() }
            override fun getFilesDir() = root
            override fun getApplicationContext(): Context = this
        }
        val local = File(context.filesDir, "v27-saf-fixture/$token").apply { mkdirs() }
        val tree = DocumentsContract.buildTreeDocumentUri(context.packageName + ".test-backups", "root/$token")
        Settings.init(fixture)
        Repository.init(fixture)
        try {
            MockDav(fullPaths = true).use { server ->
                Settings.updateBackup {
                    it.copy(
                        localEnabled = false,
                        localTree = tree.toString(),
                        keepDays = 0,
                        lastLocal = System.currentTimeMillis(),
                        davEnabled = false,
                        davUrl = "http://127.0.0.1:${server.port}/",
                        davUser = "synthetic-user",
                        davPassword = "SyntheticDavPassword",
                        davKeepDays = 0,
                        lastDav = System.currentTimeMillis(),
                        allowInsecureDav = true,
                        vaultPassword = PASSWORD,
                    )
                }
                val a = VaultEntry(type = VaultType.PASSWORD, name = "Synthetic A", password = "First#State")
                val b = VaultEntry(type = VaultType.PASSWORD, name = "Synthetic B", password = "External#State")
                VaultStore.save(a)
                check(VaultStore.get(a.id)?.password == "First#State")
                check(VaultStore.entries.value.any { it.matches("Synthetic") })
                VaultStore.update(a.id) { it.copy(lastUsedAt = System.currentTimeMillis()) }
                VaultStore.save(a.copy(password = "Second#State"))
                VaultStore.update(a.id) { it.copy(password = "Third#State") }
                VaultStore.add(b)
                VaultStore.delete(b.id)
                VaultStore.delete(a.id)
                VaultStore.delete(a.id)
                AutoBackup.flushVaultBackups(fixture)
                check(!AutoBackup.hasPendingVaultBackups(fixture))
                val uploads = server.requests.filter { it.first == "PUT" }.map { it.second }
                check(uploads.size == 6 && uploads.all { it.startsWith("/KongyeDocs/") })
                val states = uploads.map { path ->
                    val text = server.files.getValue(path).toString(Charsets.UTF_8)
                    val backup = BackupCodec.decode(text)
                    val vault = backup.vault ?: error("Missing encrypted vault snapshot")
                    val name = path.substringAfterLast('/')
                    check(File(local, name).readText() == text)
                    check(!text.contains("First#State") && !text.contains("External#State"))
                    VaultStore.open(vault, PASSWORD).map { it.password }
                }
                check(states == listOf(
                    listOf("First#State"),
                    listOf("Second#State"),
                    listOf("Third#State"),
                    listOf("Third#State", "External#State"),
                    listOf("Third#State"),
                    emptyList(),
                ))
                check(local.listFiles().orEmpty().count { it.name.endsWith(".json") } == 6)
                pass("every-save-edit-external-add-and-delete-has-its-own-local-and-cloud-snapshot")
                pass("reads-last-used-updates-and-no-op-deletes-do-not-create-backups")
                pass("deleting-the-final-password-backs-up-an-encrypted-empty-vault")

                server.rejectPut.set(true)
                val retry = VaultEntry(type = VaultType.PASSWORD, name = "Retry", password = "Retry#Secret")
                VaultStore.save(retry)
                AutoBackup.flushVaultBackups(fixture)
                check(AutoBackup.hasPendingVaultBackups(fixture))
                check(Settings.value.backup.davError.isNotEmpty())
                check(local.listFiles().orEmpty().count { it.name.endsWith(".json") } == 7)
                val queue = File(fixture.filesDir, "vault-backup-queue").listFiles().orEmpty()
                    .filter { it.name.endsWith(".json") }
                check(queue.size == 1)
                check(queue.all {
                    val text = it.readText()
                    CredentialStore.isEncrypted(text) &&
                        !text.contains("Retry#Secret") && !text.contains(PASSWORD) &&
                        !text.contains("SyntheticDavPassword")
                })
                Settings.init(fixture)
                Repository.init(fixture)
                server.rejectPut.set(false)
                AutoBackup.runScheduled(fixture)
                check(!AutoBackup.hasPendingVaultBackups(fixture))
                check(local.listFiles().orEmpty().count { it.name.endsWith(".json") } == 7)
                check(server.files.size == 7)
                server.files.forEach { (path, data) ->
                    check(File(local, path.substringAfterLast('/')).readBytes().contentEquals(data))
                }
                pass("failed-cloud-upload-survives-reload-and-retries-without-duplicating-local-backups")

                Settings.updateBackup { it.copy(vaultPassword = "") }
                VaultStore.save(retry.copy(password = "NoBackupPassword#State"))
                AutoBackup.flushVaultBackups(fixture)
                check(VaultStore.get(retry.id)?.password == "NoBackupPassword#State")
                check(server.files.size == 7 && !AutoBackup.hasPendingVaultBackups(fixture))
                check(Settings.value.backup.localError.contains("备份密码") &&
                    Settings.value.backup.davError.contains("备份密码"))
                pass("missing-backup-password-reports-failure-without-writing-a-vault-free-backup")
            }
        } finally {
            Settings.init(context)
            Repository.init(context)
            BackupJobService.schedule(context)
        }
    }
}
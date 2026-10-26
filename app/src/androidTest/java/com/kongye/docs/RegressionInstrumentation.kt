package com.kongye.docs

import android.app.Instrumentation
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import java.io.File
import com.kongye.docs.data.*
import android.app.job.JobScheduler
import kotlinx.coroutines.runBlocking

class RegressionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var passed = 0
        try {
            val suite = File(targetContext.filesDir, "regression-${System.currentTimeMillis()}").apply { mkdirs() }
            fun isolated(root: File) = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = root.apply { mkdirs() }
                override fun getApplicationContext(): Context = this
            }
            RegressionCases.names.forEach { name ->
                val root=File(suite,name).apply { mkdirs() }
                RegressionCases.run(isolated(root), name)
                passed++
                sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
                if(name=="full-backup") {
                    val original=BackupCodec.decode(Repository.exportBackup())
                    val text=Repository.exportBackup()
                    val fresh=isolated(File(suite,"fresh-restore"))
                    Settings.init(fresh); Repository.init(fresh)
                    Repository.importBackup(text)
                    val restored=BackupCodec.decode(Repository.exportBackup())
                    check(original.docs.all { restored.docs[it.key]==it.value })
                    check(original.templates.all { restored.templates[it.key]==it.value })
                    check(original.exportFiles.all { restored.exportFiles[it.key]==it.value })
                    check(original.images.all { restored.images[it.key]==it.value })
                    passed++
                    sendStatus(0,Bundle().apply { putString("stream","PASS fresh-full-restore\n") })
                }
            }
            val davContext=isolated(File(suite,"dav"))
            Settings.init(davContext); Repository.init(davContext)
            MockDav().use { server ->
                Settings.updateBackup { it.copy(davUrl="http://127.0.0.1:${server.port}/",davDir="backup",davEnabled=true) }
                runBlocking { check(AutoBackup.testDav(Settings.value.backup).startsWith("连接失败")) }
                Settings.updateBackup { it.copy(allowInsecureDav=true) }
                repeat(4) { server.files[AutoBackup.PREFIX+"20000101-00000$it.json"]="OLD".toByteArray() }
                server.tamper.set(true)
                runBlocking { AutoBackup.runScheduled(davContext) }
                check(Settings.value.backup.davError.isNotEmpty() && Settings.value.backup.lastDav==0L && server.deletes.get()==0)
                server.tamper.set(false)
                runBlocking { AutoBackup.runScheduled(davContext) }
                check(Settings.value.backup.davError.isEmpty() && Settings.value.backup.lastDav>0L && server.deletes.get()>0)
                check(server.files.values.any { runCatching { BackupCodec.decode(it.toString(Charsets.UTF_8)) }.isSuccess })
                BackupJobService.schedule(davContext)
                val scheduler=targetContext.getSystemService(JobScheduler::class.java)
                val job=scheduler.getPendingJob(2201)
                check(job!=null && job.isPersisted && job.intervalMillis==15*60*1000L)
                scheduler.cancel(2201)
            }
            passed++
            sendStatus(0,Bundle().apply { putString("stream","PASS dav-readback-retention-and-persistent-job\n") })
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "ALL $passed REGRESSION TESTS PASSED\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED after $passed tests: ${error.stackTraceToString()}\n") })
        }
    }
}

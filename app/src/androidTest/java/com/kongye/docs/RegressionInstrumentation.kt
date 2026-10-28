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
    private var verifyPaths: Pair<String,String>?=null
    private var editorModels=false
    private var onlyV213=false
    private var onlyCats=false
    private var onlyUi=false
    private var onlyEditor=false
    private var manualKeyboard=false
    override fun onCreate(arguments: Bundle?) { onlyV213=arguments?.getString("only")=="v213"; onlyCats=arguments?.getString("only")=="cats"; editorModels=arguments?.getString("only")=="editor-models"; if(arguments?.getString("only")=="verify-backups") verifyPaths=arguments.getString("before")!! to arguments.getString("after")!!; manualKeyboard=arguments?.getString("only")=="keyboard"; onlyUi=arguments?.getString("only")=="ui"; onlyEditor=arguments?.getString("only")=="editor"; super.onCreate(arguments); start() }
    private fun report(code: Int,bundle: Bundle) {
        File(targetContext.cacheDir,"regression-progress.txt").appendText(bundle.getString("stream").orEmpty())
        sendStatus(code,bundle)
    }
    override fun onStart() {
        File(targetContext.cacheDir,"regression-progress.txt").writeText("")
        // Wait for Application.onCreate before switching the repository to isolated fixtures.
        runOnMainSync { }
        var passed = 0
        try {
            verifyPaths?.let { (before,after) ->
                val valid=Regex("^/sdcard/空叶文档备份-[0-9]{8}-[0-9]{6}\\.json$")
                check(valid.matches(before) && valid.matches(after))
                val activity=startTestActivity(this,targetContext)
                val files=listOf(File(targetContext.cacheDir,"verify-before.json"),File(targetContext.cacheDir,"verify-after.json"))
                try {
                    listOf(before,after).zip(files).forEach { (source,target) ->
                        uiAutomation.executeShellCommand("cat $source").use { fd ->
                            java.io.FileInputStream(fd.fileDescriptor).use { input -> target.outputStream().use { input.copyTo(it,65536) } }
                        }
                        check(target.length() in 1..BackupCodec.MAX_BYTES.toLong())
                    }
                    val result=VerifyUpgradeStream.verify(files[0].path,files[1].path)
                    finish(Activity.RESULT_OK,Bundle().apply { putString("stream",result) })
                } finally {
                    files.forEach { it.delete() }
                    runOnMainSync { activity.finish() }
                }
                return
            }
            if(onlyV213) {
                V213Regression.run(this,targetContext) { name -> passed++; report(0,Bundle().apply { putString("stream","PASS $name\n") }) }
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","ALL $passed V2.13 TESTS PASSED\n") })
                return
            }
            if(onlyCats) {
                CatUiRegression.run(this,targetContext) { name -> passed++; report(0,Bundle().apply { putString("stream","PASS $name\n") }) }
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","ALL $passed CAT UI TESTS PASSED\n") })
                return
            }
            if(editorModels) {
                val root=File(targetContext.filesDir,"editor-models-${System.currentTimeMillis()}")
                val fixture=object: ContextWrapper(targetContext) {
                    override fun getFilesDir()=root.apply { mkdirs() }
                    override fun getApplicationContext(): Context=this
                }
                try {
                    var result: Result<Unit>?=null
                    runOnMainSync { result=runCatching {
                        FeatureRegression.run(fixture,"whole-article-actions")
                        V27Regression.run(fixture,targetContext,"spanning-selection-cut-undo-format")
                    } }
                    result!!.getOrThrow()
                    finish(Activity.RESULT_OK,Bundle().apply { putString("stream","PASS whole-body undo/redo, title protection, cross-paragraph formatting and comment restoration\n") })
                } finally { runOnMainSync { Settings.init(targetContext); Repository.init(targetContext) } }
                return
            }
            if(manualKeyboard) {
                FeatureUiRegression.run(this,targetContext,manualKeyboard=true,passed={})
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","MANUAL KEYBOARD FIXTURE CLOSED\n") }); return
            }
            if(onlyUi || onlyEditor) {
                if (!onlyEditor) V27UiRegression.run(this,targetContext) { name -> passed++; report(0,Bundle().apply { putString("stream","PASS $name\n") }) }
                FeatureUiRegression.run(this,targetContext) { name -> passed++; report(0,Bundle().apply { putString("stream","PASS $name\n") }) }
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","ALL $passed UI TESTS PASSED\n") })
                return
            }
            val suite = File(targetContext.filesDir, "regression-${System.currentTimeMillis()}").apply { mkdirs() }
            fun isolated(root: File) = object : ContextWrapper(targetContext) {
                override fun getFilesDir(): File = root.apply { mkdirs() }
                override fun getApplicationContext(): Context = this
            }
            RegressionCases.names.forEach { name ->
                val root=File(suite,name).apply { mkdirs() }
                RegressionCases.run(isolated(root), name)
                passed++
                report(0, Bundle().apply { putString("stream", "PASS $name\n") })
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
                    report(0,Bundle().apply { putString("stream","PASS fresh-full-restore\n") })
                }
            }
            FeatureRegression.names.forEach { name ->
                val fixture=isolated(File(suite,name))
                var result: Result<Unit>?=null
                runOnMainSync { result=runCatching { FeatureRegression.run(fixture,name) } }
                result!!.getOrThrow()
                passed++
                report(0,Bundle().apply { putString("stream","PASS $name\n") })
            }
            V27Regression.names.forEach { name ->
                val fixture=isolated(File(suite,name))
                var result: Result<Unit>?=null
                runOnMainSync { result=runCatching { V27Regression.run(fixture,targetContext,name) } }
                result!!.getOrThrow()
                passed++
                report(0,Bundle().apply { putString("stream","PASS $name\n") })
            }
            val timestampContext=isolated(File(suite,"local-version-time-roundtrip"))
            var timestampResult: Result<Unit>?=null
            runOnMainSync { timestampResult=runCatching { V29Regression.timestampRoundtrip(timestampContext) } }
            timestampResult!!.getOrThrow()
            passed++
            report(0,Bundle().apply { putString("stream","PASS local-version-and-comment-times-survive-reload-and-legacy-migration\n") })
            V28Regression.names.forEach { name ->
                V28Regression.run(targetContext,name)
                passed++
                report(0,Bundle().apply { putString("stream","PASS $name\n") })
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
                val filesBefore=server.files.keys.toSet()
                val last=System.currentTimeMillis()-4*60_000L
                Settings.updateBackup { it.copy(lastDav=last,davIntervalHours=24,lastDavRevision=-1) }
                Repository.createDoc(null,"frequency changed",DocContent(listOf(Block(text="new content"))))
                runBlocking { AutoBackup.runScheduled(davContext) }
                check(server.files.keys.toSet()==filesBefore && Settings.value.backup.lastDav==last)
                passed++
                report(0,Bundle().apply { putString("stream","PASS dav-no-backup-after-four-minutes-with-new-revision\n") })
            }
            passed++
            report(0,Bundle().apply { putString("stream","PASS dav-readback-retention-and-persistent-job\n") })
            V27UiRegression.run(this,targetContext) { name -> passed++; report(0,Bundle().apply { putString("stream","PASS $name\n") }) }
                FeatureUiRegression.run(this,targetContext) { name -> passed++; report(0,Bundle().apply { putString("stream","PASS $name\n") }) }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "ALL $passed REGRESSION TESTS PASSED\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED after $passed tests: ${error.stackTraceToString()}\n") })
        }
    }
}

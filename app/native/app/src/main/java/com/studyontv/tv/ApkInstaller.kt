package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL





object ApkInstaller {

    /** 200 MB 上限：足够任何正常安装包，又能防止异常/恶意线路把设备存储写满 */
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "updates").apply { mkdirs() }

    fun apkFile(ctx: Context): File = File(dir(ctx), "update.apk")

    
    @Volatile var lastError = ""
        private set

    @Suppress("DEPRECATION")
    private fun signatures(ctx: Context, info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else info.signatures
        return sigs?.map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }?.toSet() ?: emptySet()
    }

    /** 包名一致、版本更高、签名与当前安装的版本一致（镜像下载也不会被替换成别人的包） */
    @Suppress("DEPRECATION")
    private fun validUpdate(ctx: Context, file: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: run { lastError = "安装包损坏或不完整"; return false }
        archive.applicationInfo?.let { it.sourceDir = file.absolutePath; it.publicSourceDir = file.absolutePath }
        val current = ctx.packageManager.getPackageInfo(ctx.packageName, flags)
        if (archive.packageName != ctx.packageName) { lastError = "安装包与本应用不符"; return false }
        if (archive.versionCode <= current.versionCode) { lastError = "安装包版本不比当前新"; return false }
        val mine = signatures(ctx, current)
        val theirs = signatures(ctx, archive)
        if (mine.isEmpty() || theirs.isEmpty() || mine.intersect(theirs).isEmpty()) { lastError = "安装包签名与当前版本不一致，已拒绝"; return false }
        return true
    }

    fun alreadyDownloaded(ctx: Context, sizeHint: Long): Boolean {
        val f = apkFile(ctx)
        return f.exists() && (sizeHint <= 0 || f.length() == sizeHint) && validUpdate(ctx, f)
    }

    /** 依次尝试直连和各个加速镜像，任一线路成功即返回 */
    fun download(ctx: Context, url: String, sha256: String, onSource: (String) -> Unit, onProgress: (Int) -> Unit): File? {
        lastError = ""
        for ((name, candidate) in Updater.downloadCandidates(ctx, url)) {
            onSource(name)
            val file = downloadFrom(ctx, candidate, sha256, onProgress)
            if (file != null) { Prefs.setUpdateLastSource(ctx, name); return file }
            if (lastError.contains("签名") || lastError.contains("校验")) android.util.Log.w("StudyOnTV", "Rejected update from $name: $lastError")
        }
        return null
    }

    private fun downloadFrom(ctx: Context, url: String, sha256: String, onProgress: (Int) -> Unit): File? {
        val target = apkFile(ctx)
        val partial = File(dir(ctx), "update.pending.apk")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 60000   // 蒲公英 APK 文件大，慢网需要更长时间
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Pgyer.USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            // 手动跟随重定向（部分 HTTP→HTTPS 或 302 跳转 HttpURLConnection 不处理）
            var finalConn: HttpURLConnection = conn!!
            var redirectCode = finalConn.responseCode
            var redirectCount = 0
            while (redirectCode in 301..303 && redirectCount++ < 5) {
                val loc = finalConn.getHeaderField("Location") ?: break
                finalConn.disconnect()
                finalConn = URL(loc).openConnection() as HttpURLConnection
                finalConn.connectTimeout = 12000; finalConn.readTimeout = 60000
                finalConn.instanceFollowRedirects = true
                finalConn.setRequestProperty("User-Agent", Pgyer.USER_AGENT)
                redirectCode = finalConn.responseCode
            }
            conn = finalConn
            if (redirectCode !in 200..299) { lastError = "下载失败（HTTP $redirectCode）"; return null }
            val total = finalConn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (total > MAX_APK_BYTES) { lastError = "安装包过大，已中止下载"; return null }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            conn!!.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    var shown = -1
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        done += read
                        // 没有 Content-Length 的线路也要有硬上限，否则可以把设备存储写满
                        if (done > MAX_APK_BYTES) { lastError = "安装包过大，已中止下载"; return null }
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (percent != shown) { shown = percent; onProgress(percent) }
                    }
                    output.flush()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256.isNotEmpty() && !actual.equals(sha256, true)) { lastError = "安装包校验失败（SHA-256 不一致）"; return null }
            if (!validUpdate(ctx, partial)) return null
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) { lastError = "无法保存安装包"; return null }
            onProgress(100)
            return target
        } catch (e: Exception) {
            lastError = "下载中断：" + (e.message ?: e.javaClass.simpleName).take(80)
            return null
        } finally {
            partial.delete()
            conn?.disconnect()
        }
    }

    fun install(ctx: Context, file: File): Boolean {
        return try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ApkProvider.uriFor(ctx, file)
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            GateService.openingSystemPage()
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            lastError = "无法打开安装器：" + (e.message ?: e.javaClass.simpleName)
            android.util.Log.w("StudyOnTV", "Cannot open package installer", e)
            false
        }
    }
}

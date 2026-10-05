package com.lyq.localwebserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * 在 App 内下载 APK
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    
    fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit, onFinish: (Boolean, String?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val toRequest = permissions.filter { 
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED 
            }
            if (toRequest.isNotEmpty()) {
                // 需要运行时权限（实际项目中用 Activity Result API）
                onFinish(false, "需要文件读写权限")
                return
            }
        }
        
        thread(name = "download-thread") {
            try {
                val client = OkHttpClient.Builder().build()
                val request = Request.Builder().url(url).build()
                
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    onFinish(false, "下载失败：${response.code}")
                    return@thread
                }
                
                val body = response.body ?: throw Exception("Response body null")
                val totalSize = body.contentLength()
                var downloadedBytes = 0L
                
                val cacheDir = context.cacheDir
                val apkFile = File(cacheDir, "update.apk")
                
                FileOutputStream(apkFile).use { fos ->
                    val buffer = ByteArray(8192)
                    body.byteStream().use { input ->
                        while (true) {
                            val count = input.read(buffer, 0, buffer.size)
                            if (count <= 0) break
                            fos.write(buffer, 0, count)
                            downloadedBytes += count
                            
                            val progress = if (totalSize > 0) {
                                ((downloadedBytes * 100 / totalSize) % 100)
                            } else 0
                            onProgress(progress)
                        }
                    }
                }
                
                // 下载成功，删除旧安装包
                if (apkFile.exists()) {
                    // 删除旧的 update.apk
                    val oldApk = File(cacheDir, "old_update.apk")
                    if (apkFile.length() > oldApk.length()) {
                        apkFile.renameTo(oldApk)
                        apkFile.createNewFile()
                    }
                    
                    // 移动当前文件到临时位置
                    apkFile.renameTo(File(cacheDir, "tmp_update.apk"))
                    apkFile.delete()
                    
                    onFinish(true, cacheDir.absolutePath + "/tmp_update.apk")
                } else {
                    onFinish(true, "")
                }
                
            } catch (e: Exception) {
                onFinish(false, e.message ?: "未知错误")
            }
        }
    }
}

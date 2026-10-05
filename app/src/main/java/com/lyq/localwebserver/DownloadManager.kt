package com.lyq.localwebserver

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 在 App 内直接下载更新包（APK）到应用缓存目录。
 * 缓存目录属于应用私有空间，无需申请存储权限。
 */
object DownloadManager {

    fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
        onFinish: (Boolean, String?) -> Unit
    ) {
        thread(name = "apk-download") {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    onFinish(false, "HTTP ${response.code}")
                    return@thread
                }
                val body = response.body
                if (body == null) {
                    onFinish(false, "响应为空")
                    return@thread
                }

                val total = body.contentLength()
                val outFile = File(context.cacheDir, "update.apk")
                if (outFile.exists()) outFile.delete()

                var done = 0L
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            done += n
                            val percent: Int =
                                if (total > 0L) ((done * 100L) / total).toInt().coerceIn(0, 100) else 0
                            onProgress(percent)
                        }
                    }
                }

                if (outFile.exists() && outFile.length() > 0L) {
                    onFinish(true, outFile.absolutePath)
                } else {
                    onFinish(false, "文件为空")
                }
            } catch (e: Exception) {
                onFinish(false, e.message ?: "未知错误")
            }
        }
    }
}

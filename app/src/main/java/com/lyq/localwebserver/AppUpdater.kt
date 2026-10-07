package com.lyq.localwebserver

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * 应用内更新（主页 / 设置页共用同一套逻辑与 UI）：
 * 检查 GitHub Releases -> 弹窗（含更新说明）-> 应用内下载 -> 拉起安装。
 *
 * 之前只有主页能做应用内更新，设置页只会把用户"请到主页"，这里统一掉。
 */
object AppUpdater {

    private const val REPO = "lyqxml/android-local-web-server"
    private val handler = Handler(Looper.getMainLooper())
    private var progress: ProgressDialog? = null

    /** Activity 销毁时收掉可能还在的下载进度框 */
    fun dismissProgress() {
        try { progress?.dismiss() } catch (_: Exception) {}
        progress = null
    }

    fun checkForUpdate(activity: ComponentActivity, root: View) {
        thread(name = "check-update") {
            var latestTag: String? = null
            var apkUrl: String? = null
            var notes: String? = null
            var error: String? = null
            try {
                val conn = (java.net.URL("https://api.github.com/repos/$REPO/releases/latest")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "LocalWebServer-Updater")
                }
                val text = conn.inputStream.bufferedReader().readText()
                // 用 JSONObject 正规解析，并顺带取更新说明
                val json = JSONObject(text)
                val tag = json.optString("tag_name", "").ifEmpty { null }
                latestTag = tag
                notes = json.optString("body", "").ifEmpty { null }
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name", "")
                        val url = a.optString("browser_download_url", "")
                        if ((name.endsWith(".apk", true) || url.endsWith(".apk", true)) && url.isNotEmpty()) {
                            apkUrl = url
                            break
                        }
                    }
                }
                // 兜底：按 GitHub Releases 资产地址规则拼，避免取不到链接就只会"去仓库"
                if (apkUrl == null && tag != null) {
                    apkUrl = "https://github.com/$REPO/releases/download/$tag/app-release.apk"
                }
                if (latestTag == null) error = "无法解析版本信息"
            } catch (e: Exception) {
                error = "检查失败：${e.message}"
            }

            handler.post {
                val cur = currentVersion(activity)
                val tag = latestTag
                when {
                    error != null -> MaterialAlertDialogBuilder(activity)
                        .setTitle("检查更新")
                        .setMessage(error!!)
                        .setPositiveButton("知道了", null)
                        .show()

                    tag != null && tag.removePrefix("v") == cur.removePrefix("v") ->
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("检查更新")
                            .setMessage("已是最新版本（$cur）")
                            .setPositiveButton("知道了", null)
                            .show()

                    tag != null && apkUrl != null -> {
                        val msg = "当前版本：$cur\n最新版本：$tag\n" +
                                (notes?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n更新说明：\n$it\n" } ?: "") +
                                "\n是否立即下载并安装？"
                        MaterialAlertDialogBuilder(activity)
                            .setTitle("发现新版本")
                            .setMessage(msg)
                            .setPositiveButton("立即更新") { _, _ ->
                                downloadAndInstall(activity, root, apkUrl!!)
                            }
                            .setNeutralButton("稍后再说", null)
                            .show()
                    }

                    else -> MaterialAlertDialogBuilder(activity)
                        .setTitle("检查更新")
                        .setMessage("发现新版本 $tag，但未找到下载链接\n请到 GitHub Releases 下载")
                        .setPositiveButton("去下载") { _, _ ->
                            try {
                                activity.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/$REPO/releases")))
                            } catch (_: Exception) {}
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    private fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
    } catch (_: Exception) { "未知" }

    /** 在 App 内下载 APK，完成后拉起系统安装器 */
    private fun downloadAndInstall(activity: ComponentActivity, root: View, apkUrl: String) {
        val dlg = ProgressDialog(activity).apply {
            setTitle("正在下载更新")
            setMessage("请稍候...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCanceledOnTouchOutside(false)
            max = 100
            show()
        }
        dismissProgress()
        progress = dlg
        DownloadManager.downloadApk(
            activity,
            apkUrl,
            onProgress = { p -> handler.post { try { dlg.progress = p } catch (_: Exception) {} } },
            onFinish = { success, path ->
                handler.post {
                    dismissProgress()
                    if (success && !path.isNullOrEmpty()) {
                        if (!installApk(activity, path)) {
                            Snackbar.make(root, "安装失败，请到 GitHub Releases 手动下载", Snackbar.LENGTH_LONG).show()
                        }
                    } else {
                        Snackbar.make(root, "下载失败：${path ?: "未知错误"}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun installApk(context: Context, apkPath: String): Boolean {
        return try {
            val file = File(apkPath)
            if (!file.exists()) return false
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) {
            false
        }
    }
}

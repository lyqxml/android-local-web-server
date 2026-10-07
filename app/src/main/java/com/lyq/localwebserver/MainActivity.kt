package com.lyq.localwebserver

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.*
import eightbitlab.com.blurview.BlurView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private lateinit var statusDot: ImageView
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var switchServer: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var port: EditText
    private lateinit var log: TextView
    private lateinit var currentSite: TextView
    private lateinit var fileList: LinearLayout
    private lateinit var root: View
    private val handler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == WebServerService.ACTION_LOG) {
                append(i.getStringExtra("message") ?: "")
            }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            thread { uris.forEach { uri -> copyFileToSite(uri) } }
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            thread { importFolderToSite(uri) }
        }
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread { importZipToSite(uri) }
        }
    }

    private lateinit var switchTunnel: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tunnelStatus: TextView
    private lateinit var tunnelUrl: TextView
    private lateinit var btnProvider1: com.google.android.material.button.MaterialButton
    private lateinit var btnProvider2: com.google.android.material.button.MaterialButton
    private lateinit var btnTestConnection: com.google.android.material.button.MaterialButton
    private lateinit var statsView: TextView
    // 三页容器 + 底部导航
    private lateinit var pageHome: View
    private lateinit var pageFiles: View
    private lateinit var pageConsole: View
    private lateinit var tabHome: View
    private lateinit var tabFiles: View
    private lateinit var tabConsole: View
    private lateinit var tabHomeIcon: ImageView
    private lateinit var tabFilesIcon: ImageView
    private lateinit var tabConsoleIcon: ImageView
    private lateinit var tabHomeText: TextView
    private lateinit var tabFilesText: TextView
    private lateinit var tabConsoleText: TextView
    private lateinit var tabHomeInner: LinearLayout
    private lateinit var tabFilesInner: LinearLayout
    private lateinit var tabConsoleInner: LinearLayout
    private var currentPageIndex = -1
    private lateinit var blurView: BlurView
    private lateinit var bottomNav: LinearLayout
    private var liquidGlassView: com.example.liquidglass.LiquidGlassView? = null
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    private var selectedProvider: Provider = TunnelService.LOCALHOST_RUN
    private var accessPassword: String? = null
    // DDNS
    private lateinit var switchDdns: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var etDdnsDomain: EditText
    private lateinit var etDdnsToken: EditText
    private lateinit var etDdnsSub: EditText
    private lateinit var btnDdnsSync: com.google.android.material.button.MaterialButton
    private lateinit var tvDdnsStatus: TextView

    private val ddnsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == DdnsService.ACTION_DDNS) {
                val s = i.getStringExtra(DdnsService.EXTRA_STATUS) ?: return
                handler.post { tvDdnsStatus.text = s }
            }
        }
    }
    // 更新下载相关
    private var downloadProgress: android.app.ProgressDialog? = null
    // 自定义服务器参数（从 SharedPreferences 恢复）
    private var customHost: String = ""
    private var customPort: Int = 22
    private var customRemotePort: Int = 80
    private var customUsername: String = "root"
    private var customPassword: String = ""

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == WebServerService.ACTION_STATS) {
                val total = i.getLongExtra("total", 0L)
                val today = i.getLongExtra("today", 0L)
                handler.post { updateStats(total, today) }
            }
        }
    }

    private val tunnelReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == TunnelService.ACTION_TUNNEL) {
                val status = i.getStringExtra(TunnelService.EXTRA_STATUS)
                val url = i.getStringExtra(TunnelService.EXTRA_URL)
                if (status != null) {
                    handler.post { tunnelStatus.text = status }
                }
                if (url != null) {
                    handler.post {
                        tunnelUrl.visibility = View.VISIBLE
                        tunnelUrl.text = url
                    }
                }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try {
            setContentView(R.layout.activity_main)
            root = findViewById(android.R.id.content)
            statusDot = findViewById(R.id.statusDot)
            status = findViewById(R.id.status)
            address = findViewById(R.id.address)
            switchServer = findViewById(R.id.switchServer)
            port = findViewById(R.id.port)
            log = findViewById(R.id.log)
            currentSite = findViewById(R.id.currentSite)
            fileList = findViewById(R.id.fileList)
            switchTunnel = findViewById(R.id.switchTunnel)
            tunnelStatus = findViewById(R.id.tunnelStatus)
            tunnelUrl = findViewById(R.id.tunnelUrl)
            btnProvider1 = findViewById(R.id.btnProvider1)
            btnProvider2 = findViewById(R.id.btnProvider2)
            btnTestConnection = findViewById(R.id.btnTestConnection)
            statsView = findViewById(R.id.statsView)
            switchDdns = findViewById(R.id.switchDdns)
            etDdnsDomain = findViewById(R.id.etDdnsDomain)
            etDdnsToken = findViewById(R.id.etDdnsToken)
            etDdnsSub = findViewById(R.id.etDdnsSub)
            btnDdnsSync = findViewById(R.id.btnDdnsSync)
            tvDdnsStatus = findViewById(R.id.tvDdnsStatus)
            pageHome = findViewById(R.id.pageHome)
            pageFiles = findViewById(R.id.pageFiles)
            pageConsole = findViewById(R.id.pageConsole)
            tabHome = findViewById(R.id.tabHome)
            tabFiles = findViewById(R.id.tabFiles)
            tabConsole = findViewById(R.id.tabConsole)
            tabHomeIcon = findViewById(R.id.tabHomeIcon)
            tabFilesIcon = findViewById(R.id.tabFilesIcon)
            tabConsoleIcon = findViewById(R.id.tabConsoleIcon)
            tabHomeText = findViewById(R.id.tabHomeText)
            tabFilesText = findViewById(R.id.tabFilesText)
            tabConsoleText = findViewById(R.id.tabConsoleText)
            tabHomeInner = findViewById(R.id.tabHomeInner)
            tabFilesInner = findViewById(R.id.tabFilesInner)
            tabConsoleInner = findViewById(R.id.tabConsoleInner)
            toolbar = findViewById(R.id.toolbar)

            // 底部导航
            tabHome.setOnClickListener { switchPage(0) }
            tabFiles.setOnClickListener { switchPage(1) }
            tabConsole.setOnClickListener { switchPage(2) }

            // 右上角齿轮：全屏设置
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_settings -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    else -> false
                }
            }

            // 图标着色
            tabHomeIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_faint))
            tabFilesIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_faint))
            tabConsoleIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_faint))

            switchPage(0)

            // 底栏毛玻璃：由 BlurView（公开库 Dimezis/BlurView）自动抓取背后内容并实时模糊
            blurView = findViewById(R.id.bottomBlur)
            bottomNav = findViewById(R.id.bottomNav)
            try {
                blurView.setupWith(findViewById<FrameLayout>(R.id.pageContainer))
                    .setFrameClearDrawable(ColorDrawable(currentBaseBackgroundColor()))
                    .setBlurRadius(20f)
                    .setBlurAutoUpdate(true)
            } catch (_: Exception) {
            }

            registerReceiver(receiver, IntentFilter(WebServerService.ACTION_LOG), RECEIVER_NOT_EXPORTED)
            registerReceiver(tunnelReceiver, IntentFilter(TunnelService.ACTION_TUNNEL), RECEIVER_NOT_EXPORTED)
            registerReceiver(statsReceiver, IntentFilter(WebServerService.ACTION_STATS), RECEIVER_NOT_EXPORTED)
            registerReceiver(ddnsReceiver, IntentFilter(DdnsService.ACTION_DDNS), RECEIVER_NOT_EXPORTED)

            // 恢复 DDNS 设置
            val dprefs = getSharedPreferences("settings", MODE_PRIVATE)
            etDdnsDomain.setText(dprefs.getString("ddns_domain", "") ?: "")
            etDdnsToken.setText(dprefs.getString("ddns_token", "") ?: "")
            etDdnsSub.setText(dprefs.getString("ddns_sub", "") ?: "")
            tvDdnsStatus.text = dprefs.getString("ddns_status", "未启用") ?: "未启用"
            val ddnsEnabled = dprefs.getBoolean("ddns_enabled", false)
            switchDdns.isChecked = ddnsEnabled
            if (ddnsEnabled) startDdnsService()

            switchDdns.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    val domain = etDdnsDomain.text.toString().trim()
                    val token = etDdnsToken.text.toString().trim()
                    if (domain.isEmpty() || token.isEmpty()) {
                        Snackbar.make(root, "请先填写域名和 Token 再开启", Snackbar.LENGTH_SHORT).show()
                        switchDdns.isChecked = false
                        return@setOnCheckedChangeListener
                    }
                    dprefs.edit().putBoolean("ddns_enabled", true).apply()
                    saveDdnsFields()
                    startDdnsService()
                } else {
                    dprefs.edit().putBoolean("ddns_enabled", false).apply()
                    stopService(Intent(this, DdnsService::class.java))
                    tvDdnsStatus.text = "已关闭"
                }
            }

            btnDdnsSync.setOnClickListener { saveDdnsAndSync() }

            // 读取已保存的访问密码
            accessPassword = getSharedPreferences("settings", MODE_PRIVATE).getString("access_password", null)

            // 恢复上次使用的站点（防止退出重进后站点丢失）
            val savedSite = getSharedPreferences("settings", MODE_PRIVATE).getString("current_site", null)
            if (savedSite != null && savedSite.isNotEmpty()) {
                WebServerService.currentSite = savedSite
            }

            // 恢复自定义服务器参数
            val cprefs = getSharedPreferences("settings", MODE_PRIVATE)
            customHost = cprefs.getString("custom_host", "") ?: ""
            customPort = cprefs.getInt("custom_port", 22)
            customRemotePort = cprefs.getInt("custom_remote_port", 80)
            customUsername = cprefs.getString("custom_username", "root") ?: "root"
            customPassword = cprefs.getString("custom_password", "") ?: ""

            switchServer.setOnCheckedChangeListener { _, checked ->
                if (checked) startServer() else stopServer()
            }

            switchTunnel.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!WebServerService.running) {
                        Snackbar.make(root, "请先启动 HTTP 服务", Snackbar.LENGTH_SHORT).show()
                        switchTunnel.isChecked = false
                    } else {
                        showTunnelWarningIfFirst()
                    }
                } else {
                    stopTunnel()
                }
            }

            btnProvider1.setOnClickListener { selectBuiltinProvider() }
            btnProvider2.setOnClickListener { showCustomProviderDialog() }

            btnTestConnection.setOnClickListener { testProviderConnection() }

            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQRCode).setOnClickListener { showQRCode() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCopyAddr).setOnClickListener { copyAddress() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSetPassword).setOnClickListener { showPasswordDialog() }

            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnUpload).setOnClickListener { pickFiles() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnImportFolder).setOnClickListener { pickFolder() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnImportZip).setOnClickListener { pickZipFile() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSites).setOnClickListener { showSitesDialog() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNewSite).setOnClickListener { showNewSiteDialog() }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearLog).setOnClickListener { log.text = "" }

            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
            }

            updateUI(false)
            refreshFileList()
            refreshStatsFromPrefs()
            handleIncomingIntent(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // 处理「打开方式」传入的文件：ZIP 或 HTML
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val uri = when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            else -> null
        }
        if (uri != null) {
            val name = queryName(uri).lowercase()
            when {
                name.endsWith(".zip") -> {
                    handler.postDelayed({ importZipToSite(uri) }, 300)
                }
                name.endsWith(".html") || name.endsWith(".htm") -> {
                    handler.postDelayed({ copyFileToSite(uri) }, 300)
                }
                else -> {
                    // 其他文件也尝试复制
                    handler.postDelayed({ copyFileToSite(uri) }, 300)
                }
            }
        }
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else "file"
                } else "file"
            } ?: uri.lastPathSegment ?: "file"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "file"
        }
    }

    private fun makeIntent(): Intent {
        val site = WebServerService.currentSite.ifEmpty { "default" }
        val intent = Intent(this, WebServerService::class.java)
            .putExtra("port", port.text.toString().toIntOrNull() ?: 8080)
            .putExtra("site", site)
        accessPassword?.let { intent.putExtra("password", it) }
        return intent
    }

    private fun startServer() {
        val p = port.text.toString().toIntOrNull() ?: 8080
        if (p !in 1024..65535) {
            Snackbar.make(root, "端口范围 1024-65535", Snackbar.LENGTH_SHORT).show()
            switchServer.isChecked = false
            return
        }
        ContextCompat.startForegroundService(this, makeIntent())
        handler.postDelayed({ updateUI(WebServerService.running) }, 800)
    }

    private fun stopServer() {
        stopService(Intent(this, WebServerService::class.java))
        handler.postDelayed({ updateUI(false) }, 800)
    }

    private fun startTunnel() {
        val p = port.text.toString().toIntOrNull() ?: 8080
        tunnelUrl.visibility = View.GONE
        tunnelUrl.text = ""
        tunnelStatus.text = "正在连接 ${selectedProvider.label} ..."
        val intent = Intent(this, TunnelService::class.java)
            .putExtra("port", p)
            .putExtra("provider", selectedProvider.key)
        if (selectedProvider.key == "custom") {
            intent.putExtra("custom_host", customHost)
                .putExtra("custom_port", customPort)
                .putExtra("custom_remote_port", customRemotePort)
                .putExtra("custom_username", customUsername)
                .putExtra("custom_password", customPassword)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTunnel() {
        stopService(Intent(this, TunnelService::class.java))
        tunnelStatus.text = "关闭。开启后可通过外网访问你的站点"
        tunnelUrl.visibility = View.GONE
        tunnelUrl.text = ""
    }

    // 选择内置 localhost.run
    private fun selectBuiltinProvider() {
        selectedProvider = TunnelService.LOCALHOST_RUN
        btnProvider1.isChecked = true
        btnProvider2.isChecked = false
        if (switchTunnel.isChecked) {
            stopTunnel()
            handler.postDelayed({ startTunnel() }, 500)
        }
    }

    // 自定义服务器弹窗
    private fun showCustomProviderDialog() {
        val container = layoutInflater.inflate(R.layout.dialog_custom_server, null)
        val hostInput = container.findViewById<EditText>(R.id.etCustomHost)
        val portInput = container.findViewById<EditText>(R.id.etCustomPort)
        val remotePortInput = container.findViewById<EditText>(R.id.etCustomRemotePort)
        val userInput = container.findViewById<EditText>(R.id.etCustomUsername)
        val pwdInput = container.findViewById<EditText>(R.id.etCustomPassword)

        hostInput.setText(customHost)
        portInput.setText(customPort.toString())
        remotePortInput.setText(customRemotePort.toString())
        userInput.setText(customUsername)
        pwdInput.setText(customPassword)

        MaterialAlertDialogBuilder(this)
            .setTitle("自定义服务器")
            .setView(container)
            .setPositiveButton("保存并使用") { _, _ ->
                customHost = hostInput.text.toString().trim()
                customPort = portInput.text.toString().toIntOrNull() ?: 22
                customRemotePort = remotePortInput.text.toString().toIntOrNull() ?: 80
                customUsername = userInput.text.toString().trim().ifEmpty { "root" }
                customPassword = pwdInput.text.toString().trim()
                if (customHost.isEmpty()) {
                    Snackbar.make(root, "请填写服务器地址", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 持久化
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putString("custom_host", customHost)
                    .putInt("custom_port", customPort)
                    .putInt("custom_remote_port", customRemotePort)
                    .putString("custom_username", customUsername)
                    .putString("custom_password", customPassword)
                    .apply()
                selectedProvider = TunnelService.CUSTOM
                btnProvider1.isChecked = false
                btnProvider2.isChecked = true
                if (switchTunnel.isChecked) {
                    stopTunnel()
                    handler.postDelayed({ startTunnel() }, 500)
                }
                Snackbar.make(root, "已切换到自定义服务器: $customHost", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 首次开启公网模式时显示 5 秒警告
    private fun showTunnelWarningIfFirst() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val warned = prefs.getBoolean("tunnel_warned", false)
        if (warned) {
            startTunnel()
            return
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ 公网访问提醒")
            .setMessage(
                "开启公网隧道后，你的站点将暴露到互联网，任何获得该公网地址的人都能访问。\n\n" +
                "请注意：\n" +
                "• 不要托管敏感、隐私内容\n" +
                "• 免费隧道服务可能不稳定，公网地址会变化\n" +
                "• 关闭开关即可立即停止公网访问\n\n" +
                "是否继续开启？"
            )
            .setPositiveButton("继续开启") { _, _ ->
                prefs.edit().putBoolean("tunnel_warned", true).apply()
                startTunnel()
            }
            .setNegativeButton("取消") { _, _ ->
                switchTunnel.isChecked = false
            }
            .show()
    }

    // 保存 DDNS 输入框内容
    private fun saveDdnsFields() {
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("ddns_domain", etDdnsDomain.text.toString().trim())
            .putString("ddns_token", etDdnsToken.text.toString().trim())
            .putString("ddns_sub", etDdnsSub.text.toString().trim())
            .apply()
    }

    // 保存并立即同步
    private fun saveDdnsAndSync() {
        val domain = etDdnsDomain.text.toString().trim()
        val token = etDdnsToken.text.toString().trim()
        if (domain.isEmpty() || token.isEmpty()) {
            Snackbar.make(root, "请先填写域名和 DNSPod Token", Snackbar.LENGTH_SHORT).show()
            return
        }
        saveDdnsFields()
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("ddns_enabled", true).apply()
        switchDdns.isChecked = true
        // 重启服务，立即开始一轮同步
        stopService(Intent(this, DdnsService::class.java))
        handler.postDelayed({ startDdnsService() }, 300)
        Snackbar.make(root, "已保存，正在同步...", Snackbar.LENGTH_SHORT).show()
    }

    private fun startDdnsService() {
        ContextCompat.startForegroundService(this, Intent(this, DdnsService::class.java))
    }

    // 从 SharedPreferences 读统计刷新
    private fun refreshStatsFromPrefs() {
        val prefs = getSharedPreferences("stats", MODE_PRIVATE)
        val total = prefs.getLong("total_visits", 0L)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date())
        val todayCount = prefs.getLong("day_$today", 0L)
        updateStats(total, todayCount)
    }

    private fun updateStats(total: Long, today: Long) {
        statsView.text = "今日访问 $today · 累计访问 $total"
    }

    // 获取当前可分享的地址：优先公网地址，其次局域网
    private fun currentShareUrl(): String? {
        val publicUrl = TunnelService.publicUrl
        if (publicUrl != null && publicUrl.isNotEmpty()) return publicUrl
        if (WebServerService.running) {
            return "http://${WebServerService.localIp()}:${port.text}/"
        }
        return null
    }

    // 显示二维码
    private fun showQRCode() {
        val url = currentShareUrl()
        if (url == null) {
            Snackbar.make(root, "请先启动 HTTP 服务或开启公网隧道", Snackbar.LENGTH_SHORT).show()
            return
        }
        val qrBitmap = QRCodeGenerator.generate(url) ?: run {
            Snackbar.make(root, "二维码生成失败", Snackbar.LENGTH_SHORT).show()
            return
        }
        val iv = ImageView(this).apply {
            setImageBitmap(qrBitmap)
            setPadding(40, 40, 40, 40)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("扫码访问")
            .setMessage(url)
            .setView(iv)
            .setPositiveButton("关闭", null)
            .show()
    }

    // 复制地址到剪贴板
    private fun copyAddress() {
        val url = currentShareUrl()
        if (url == null) {
            Snackbar.make(root, "请先启动 HTTP 服务或开启公网隧道", Snackbar.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
        Snackbar.make(root, "已复制: $url", Snackbar.LENGTH_SHORT).show()
    }

    // 设置访问密码
    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            hint = "留空则关闭密码保护"
            setPadding(32, 16, 32, 16)
        }
        input.setText(accessPassword ?: "")
        MaterialAlertDialogBuilder(this)
            .setTitle("访问密码")
            .setMessage("设置后，访问者需要输入密码才能浏览网站（HTTP Basic 认证）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val pwd = input.text.toString().trim()
                accessPassword = if (pwd.isEmpty()) null else pwd
                getSharedPreferences("settings", MODE_PRIVATE)
                    .edit().putString("access_password", accessPassword).apply()
                if (WebServerService.running) {
                    // 重启服务使密码生效
                    stopServer()
                    handler.postDelayed({ startServer() }, 600)
                }
                val tip = if (accessPassword == null) "已关闭访问密码" else "访问密码已设置"
                Snackbar.make(root, tip, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 测试当前服务商连接（简单 TCP socket 检测 SSH 端口）
    private fun testProviderConnection() {
        thread(name = "test-conn") {
            val host = if (selectedProvider.key == "custom") customHost else selectedProvider.host
            val sshPort = if (selectedProvider.key == "custom") customPort else selectedProvider.port
            if (host.isEmpty()) {
                handler.post {
                    Snackbar.make(root, "请先配置自定义服务器", Snackbar.LENGTH_SHORT).show()
                }
                return@thread
            }
            val ok = try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, sshPort), 8000)
                socket.close()
                true
            } catch (e: Exception) {
                false
            }
            handler.post {
                if (ok) {
                    Snackbar.make(root, "✅ $host 连接正常", Snackbar.LENGTH_SHORT).show()
                    tunnelStatus.text = "✅ $host 测试连接成功"
                } else {
                    Snackbar.make(root, "❌ $host 无法连接", Snackbar.LENGTH_LONG).show()
                    tunnelStatus.text = "❌ $host 连接失败"
                }
            }
        }
    }

    private fun updateUI(on: Boolean) {
        statusDot.setImageResource(if (on) R.drawable.dot_on else R.drawable.dot_off)
        status.text = if (on) "服务运行中" else "服务已停止"
        status.setTextColor(if (on) ContextCompat.getColor(this, R.color.success) else ContextCompat.getColor(this, R.color.text_secondary))
        val site = WebServerService.currentSite.ifEmpty { "default" }
        address.text = if (on) "http://${WebServerService.localIp()}:${port.text}/  |  $site" else "http://—:${port.text}"
        switchServer.isChecked = on
    }

    private fun append(s: String) {
        handler.post {
            log.append("$s\n")
            val scrollView = findViewById<ScrollView>(R.id.logScrollView)
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun pickFiles() {
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            Snackbar.make(root, "无法打开文件选择器: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun pickFolder() {
        try {
            pickFolder.launch(null)
        } catch (e: Exception) {
            Snackbar.make(root, "无法打开文件夹选择器: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun pickZipFile() {
        try {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        } catch (e: Exception) {
            Snackbar.make(root, "无法打开文件选择器: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun copyFileToSite(uri: Uri) {
        try {
            var name: String? = null
            try {
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx)
                    }
                }
            } catch (_: Exception) {}

            val fileName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
            val site = WebServerService.currentSite.ifEmpty { "default" }
            val dest = File(filesDir, "sites/$site/$fileName")
            dest.parentFile?.mkdirs()

            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            append("[文件] 已导入: $fileName")
            handler.post {
                Snackbar.make(root, "已导入: $fileName", Snackbar.LENGTH_SHORT).show()
                refreshFileList()
            }
        } catch (e: SecurityException) {
            handler.post { Snackbar.make(root, "权限不足，请重新选择文件", Snackbar.LENGTH_SHORT).show() }
        } catch (e: Exception) {
            handler.post { Snackbar.make(root, "导入失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
        }
    }

    // 导入文件夹：保留子目录结构，复制到当前站点
    private fun importFolderToSite(treeUri: Uri) {
        try {
            val site = WebServerService.currentSite.ifEmpty { "default" }
            val baseDir = File(filesDir, "sites/$site").apply { mkdirs() }
            var count = 0
            count = copyTree(treeUri, baseDir, count)
            append("[文件夹] 导入了 $count 个文件到站点: $site")
            handler.post {
                Snackbar.make(root, "导入完成，共 $count 个文件", Snackbar.LENGTH_SHORT).show()
                refreshFileList()
            }
        } catch (e: Exception) {
            handler.post { Snackbar.make(root, "文件夹导入失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
        }
    }

    // 递归复制 DocumentFile 树到 File
    private fun copyTree(uri: Uri, destDir: File, count: Int): Int {
        val dfNode = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri) ?: return count
        var c = count
        destDir.mkdirs()
        val items = dfNode.listFiles()
        for (item in items) {
            if (item.isDirectory) {
                val subDir = File(destDir, item.name ?: "dir_${System.currentTimeMillis()}")
                c = copyTree(item.uri, subDir, c)
            } else {
                val name = item.name ?: "file_${System.currentTimeMillis()}"
                val out = File(destDir, name)
                contentResolver.openInputStream(item.uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                c++
            }
        }
        return c
    }

    // 导入 ZIP：先读 site.json，再按配置解压到站点目录
    private fun importZipToSite(uri: Uri) {
        try {
            // 先复制 ZIP 到临时文件
            val tmpZip = File(cacheDir, "import_${System.currentTimeMillis()}.zip")
            contentResolver.openInputStream(uri)?.use { input ->
                tmpZip.outputStream().use { output -> input.copyTo(output) }
            }

            // 第一遍：读取 site.json
            var siteConfig: JSONObject? = null
            try {
                ZipInputStream(FileInputStream(tmpZip)).use { zin ->
                    while (true) {
                        val e = zin.nextEntry ?: break
                        if (e.isDirectory) { zin.closeEntry(); continue }
                        val name = e.name
                        if (name == "site.json" || name.endsWith("/site.json")) {
                            val content = zin.readBytes().toString(Charsets.UTF_8)
                            siteConfig = try { JSONObject(content) } catch (_: Exception) { null }
                        }
                        zin.closeEntry()
                    }
                }
            } catch (e: Exception) {
                handler.post { Snackbar.make(root, "ZIP 读取失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
                tmpZip.delete()
                return
            }

            // 确定目标站点目录
            var targetSite = WebServerService.currentSite.ifEmpty { "default" }
            var siteName: String? = null
            val cfg = siteConfig
            if (cfg != null) {
                val cfgSite = cfg.optString("site", "")
                if (cfgSite.isNotEmpty()) targetSite = cfgSite
                siteName = cfg.optString("name", "")
            }

            // 第二遍：按顺序解压，防止路径穿越
            val destRoot = File(filesDir, "sites/$targetSite").apply { mkdirs() }
            var imported = 0
            ZipInputStream(FileInputStream(tmpZip)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (!e.isDirectory) {
                        val name = e.name
                        if (name != "site.json" && !name.endsWith("/site.json")) {
                            val safe = safePath(name)
                            if (safe != null) {
                                val out = File(destRoot, safe)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { o -> zin.copyTo(o) }
                                imported++
                            }
                        }
                    }
                    zin.closeEntry()
                }
            }
            tmpZip.delete()

            // 同步当前站点并持久化
            WebServerService.currentSite = targetSite
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit().putString("current_site", targetSite).apply()
            append("[ZIP] 站点导入完成: ${siteName ?: targetSite}，共 $imported 个文件")
            handler.post {
                val msg = if (siteName != null) "已导入站点「$siteName」：$imported 个文件" else "已导入 $imported 个文件"
                Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show()
                refreshFileList()
            }
        } catch (e: Exception) {
            handler.post { Snackbar.make(root, "ZIP 导入失败: ${e.message}", Snackbar.LENGTH_LONG).show() }
        }
    }

    // 防路径穿越：规范化 zip entry 名称
    private fun safePath(name: String): String? {
        val cleaned = name.replace('\\', '/').trim('/')
        if (cleaned.isEmpty()) return null
        if (cleaned.contains("..")) return null
        if (cleaned.startsWith("/")) return null
        return cleaned
    }

    private fun refreshFileList() {
        try {
            val site = WebServerService.currentSite.ifEmpty { "default" }
            val dir = File(filesDir, "sites/$site")
            currentSite.text = "站点: $site"
            fileList.removeAllViews()
            val files = dir.listFiles()
            if (files == null || files.isEmpty()) {
                fileList.addView(TextView(this).apply {
                    text = "（空目录，请导入文件）"; textSize = 12f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_faint)); setPadding(8, 12, 8, 0)
                })
                return
            }
            files.sortedBy { it.name }.forEach { f ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(8, 10, 8, 10); gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = "${if (f.isDirectory) "📁 " else "📄 "}${f.name}"; textSize = 13f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_file))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 16f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger)); setPadding(16, 4, 4, 4)
                    setOnClickListener {
                        try { f.deleteRecursively() } catch (_: Exception) {}
                        refreshFileList(); Snackbar.make(root, "已删除: ${f.name}", Snackbar.LENGTH_SHORT).show()
                    }
                })
                fileList.addView(row)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "刷新文件列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartServerWithSite(newSite: String) {
        WebServerService.currentSite = newSite
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit().putString("current_site", newSite).apply()
        File(filesDir, "sites/$newSite").mkdirs()
        refreshFileList()
        stopServer()
        handler.postDelayed({
            ContextCompat.startForegroundService(this, makeIntent())
            handler.postDelayed({ updateUI(WebServerService.running) }, 800)
        }, 1200)
    }

    private fun showSitesDialog() {
        try {
            val sitesDir = File(filesDir, "sites").apply { mkdirs() }
            if (!File(sitesDir, "default").exists()) File(sitesDir, "default").mkdirs()
            val sites = sitesDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: listOf("default")
            val current = WebServerService.currentSite.ifEmpty { "default" }
            val checked = sites.indexOf(current).coerceAtLeast(0)

            MaterialAlertDialogBuilder(this)
                .setTitle("切换站点")
                .setSingleChoiceItems(sites.toTypedArray(), checked) { dialog, which ->
                    dialog.dismiss()
                    val chosen = sites[which]
                    if (chosen != WebServerService.currentSite) {
                        restartServerWithSite(chosen)
                    }
                    Snackbar.make(root, "已切换到: $chosen", Snackbar.LENGTH_SHORT).show()
                }
                .setPositiveButton("确定", null)
                .show()
        } catch (e: Exception) {
            Snackbar.make(root, "切换站点失败", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showNewSiteDialog() {
        try {
            val input = EditText(this).apply { hint = "请输入站点名称"; setPadding(32, 16, 32, 16) }
            MaterialAlertDialogBuilder(this)
                .setTitle("新建站点")
                .setMessage("输入站点名（字母/数字/中文/下划线）")
                .setView(input)
                .setPositiveButton("创建") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty() && name.matches(Regex("^[a-zA-Z0-9_\\u4e00-\\u9fa5-]+$"))) {
                        File(filesDir, "sites/$name").mkdirs()
                        restartServerWithSite(name)
                        Snackbar.make(root, "站点已创建: $name", Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(root, "站点名不合法", Snackbar.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            Snackbar.make(root, "创建站点失败", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun switchPage(index: Int) {
        val pages = arrayOf<View>(pageHome, pageFiles, pageConsole)
        val density = resources.displayMetrics.density

        if (currentPageIndex != index) {
            val oldIndex = currentPageIndex

            // 先把非目标、非当前的页面收起来
            pages.forEachIndexed { i, p ->
                if (i != index && i != oldIndex) {
                    p.visibility = View.GONE
                    p.alpha = 1f
                    p.translationY = 0f
                }
            }

            // 旧页淡出
            if (oldIndex in 0..2) {
                val old = pages[oldIndex]
                old.animate().alpha(0f).setDuration(160).withEndAction {
                    if (currentPageIndex != oldIndex) {
                        old.visibility = View.GONE
                    }
                    old.alpha = 1f
                    old.translationY = 0f
                }.start()
            }

            // 新页淡入 + 轻微上滑
            val newPage = pages[index]
            newPage.visibility = View.VISIBLE
            newPage.alpha = 0f
            newPage.translationY = 16f * density
            newPage.animate().alpha(1f).translationY(0f).setDuration(220).start()

            currentPageIndex = index
        }

        val accent = ContextCompat.getColor(this, R.color.accent)
        val faint = ContextCompat.getColor(this, R.color.text_faint)
        val dim = ContextCompat.getColor(this, R.color.text_dim)

        tabHomeIcon.setColorFilter(if (index == 0) accent else faint)
        tabFilesIcon.setColorFilter(if (index == 1) accent else faint)
        tabConsoleIcon.setColorFilter(if (index == 2) accent else faint)

        tabHomeText.setTextColor(if (index == 0) accent else dim)
        tabFilesText.setTextColor(if (index == 1) accent else dim)
        tabConsoleText.setTextColor(if (index == 2) accent else dim)

        tabHome.isSelected = index == 0
        tabFiles.isSelected = index == 1
        tabConsole.isSelected = index == 2

        updateTabBackgrounds(index)
    }

    /** Tab 选中背景：液态=玻璃胶囊，现代=胶囊，经典=方形描边 */
    private fun updateTabBackgrounds(selected: Int) {
        val modern = ThemeManager.isModern(this)
        val liquid = modern && ThemeManager.isLiquidGlass(this)
        val bgRes = when {
            liquid -> R.drawable.bg_tab_liquid
            modern -> R.drawable.bg_tab_pill
            else -> R.drawable.bg_tab_square
        }
        tabHomeInner.background = if (selected == 0) ContextCompat.getDrawable(this, bgRes) else null
        tabFilesInner.background = if (selected == 1) ContextCompat.getDrawable(this, bgRes) else null
        tabConsoleInner.background = if (selected == 2) ContextCompat.getDrawable(this, bgRes) else null
    }

    /** 应用主题：背景、卡片颜色与圆角、底栏与 Tab */
    private fun applyTheme() {
        try {
            val rootLayout = findViewById<androidx.coordinatorlayout.widget.CoordinatorLayout>(R.id.rootLayout)

            // 背景 + 卡片（现代=圆角 / 经典=直角）
            ThemeApply.background(this, rootLayout)
            ThemeApply.cards(this, rootLayout)

            val modern = ThemeManager.isModern(this)
            val liquid = modern && ThemeManager.isLiquidGlass(this)

            // 底栏容器：液态=悬浮圆角 LiquidGlassView；现代高斯=同款悬浮圆角 BlurView；经典=贴边全宽
            applyBottomBarContainer(modern, liquid)
            try { blurView.setBlurEnabled(!liquid) } catch (_: Exception) {}

            // 底栏“玻璃质感层”：现代（高斯/液态都）加顶部内高光 + 高光描边，纯色背景上也能看出玻璃
            bottomNav.background = ContextCompat.getDrawable(
                this,
                if (modern) R.drawable.bg_nav_glass else R.drawable.bg_bottom_classic
            )
            updateTabBackgrounds(currentPageIndex.coerceAtLeast(0))
        } catch (_: Exception) {}
    }

    /**
     * 切换底栏容器（幂等）：
     * - liquid=true         -> bottomNav 搬进悬浮圆角 LiquidGlassView（12dp 边距 / 28dp 圆角）
     * - modern=true, 液态否 -> bottomNav 放回 BlurView，并把 BlurView 也裁成同款悬浮圆角条（高斯模糊）
     * - modern=false(经典)  -> bottomNav 放回 BlurView，贴边全宽、直角
     * 只有一份 Tab 内容，在两种容器之间移动，避免重复 id 与重复事件。
     */
    private fun applyBottomBarContainer(modern: Boolean, liquid: Boolean) {
        val container = findViewById<FrameLayout>(R.id.pageContainer)
        val density = resources.displayMetrics.density
        val m = (12 * density).toInt()
        val radius = 28f * density
        val barHeight = (64 * density).toInt()

        if (liquid) {
            var glass = liquidGlassView
            if (glass == null) {
                glass = com.example.liquidglass.LiquidGlassView(this)
                val lp = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, barHeight, android.view.Gravity.BOTTOM
                )
                lp.leftMargin = m
                lp.rightMargin = m
                lp.bottomMargin = m
                glass.layoutParams = lp
                try {
                    glass.cornerRadius = radius
                    glass.enableSensorHighlight = true     // API 33+ 高光随重力变化
                    glass.enablePressEffect = false        // 整条底栏不要跟着手指缩放
                    glass.dispersionStrength = 0.25f       // 边缘色散更明显（默认 0.10，纯色背景也能看出彩边）
                    glass.saturation = 165f                // 饱和度提升，玻璃更“活”
                    glass.enableShadow = true              // 悬浮投影，更像浮起来的一层
                } catch (_: Throwable) {
                }
                container.addView(glass)
                liquidGlassView = glass
            }
            // 背景会动，必须开，否则玻璃“冻住”（切回非液态时关掉，避免隐藏时仍逐帧采样）
            try { glass.enableDynamicBackground = true } catch (_: Throwable) {}
            if (bottomNav.parent !== glass) {
                (bottomNav.parent as? ViewGroup)?.removeView(bottomNav)
                bottomNav.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                glass.addView(bottomNav)
            }
            glass.visibility = View.VISIBLE
            blurView.visibility = View.GONE
        } else {
            try { liquidGlassView?.enableDynamicBackground = false } catch (_: Throwable) {}
            // 高斯（现代）= 悬浮圆角；经典 = 贴边全宽直角
            val lp = (blurView.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, barHeight)
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = barHeight
            lp.gravity = android.view.Gravity.BOTTOM
            lp.leftMargin = if (modern) m else 0
            lp.rightMargin = if (modern) m else 0
            lp.bottomMargin = if (modern) m else 0
            blurView.layoutParams = lp
            if (modern) {
                // 把实时模糊层裁成圆角 -> 和液态玻璃同款悬浮圆角玻璃条
                blurView.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                blurView.clipToOutline = true
            } else {
                blurView.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                blurView.clipToOutline = false
            }
            if (bottomNav.parent !== blurView) {
                (bottomNav.parent as? ViewGroup)?.removeView(bottomNav)
                bottomNav.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                blurView.addView(bottomNav)
            }
            blurView.visibility = View.VISIBLE
            liquidGlassView?.visibility = View.GONE
        }
    }

    /** 模糊帧的兜底底色（有自定义背景色时优先用它） */
    private fun currentBaseBackgroundColor(): Int {
        ThemeManager.getBgColor(this)?.let { return it }
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        return if (isNight) 0xFF14181F.toInt() else 0xFFFDFCFF.toInt()
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ applyTheme() }, 300)
    }

    private fun showAboutDialog() {
        val title = getString(R.string.app_name)
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
        } catch (_: Exception) { "未知" }
        val curVer = versionName.removePrefix("v")
        val msg = "版本：$versionName\n包名：$packageName\n\n" +
                "一个纯本地运行的 Android 静态网页托管服务。\n" +
                "支持多站点、文件导入、二维码分享、访问密码、公网隧道。\n\n" +
                "已使用固定签名，更新可直接覆盖安装。"
        MaterialAlertDialogBuilder(this)
            .setTitle("关于 $title")
            .setMessage(msg)
            .setNeutralButton("检查更新") { _, _ ->
                checkUpdate()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun checkUpdate() {
        thread(name = "check-update") {
            var latestTag: String? = null
            var apkUrl: String? = null
            var notes: String? = null
            var error: String? = null
            try {
                val apiUrl = java.net.URL("https://api.github.com/repos/lyqxml/android-local-web-server/releases/latest")
                val conn = apiUrl.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "LocalWebServer-Updater")
                val text = conn.inputStream.bufferedReader().readText()
                // 用 JSONObject 正规解析（比正则稳），并顺带取更新说明
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
                // 兜底：按 GitHub Releases 资产地址规则拼，避免个别机型取不到链接就只会“去仓库”
                if (apkUrl == null && tag != null) {
                    apkUrl = "https://github.com/lyqxml/android-local-web-server/releases/download/$tag/app-release.apk"
                }
                if (latestTag == null) error = "无法解析版本信息"
            } catch (e: Exception) {
                error = "检查失败：${e.message}"
            }

            handler.post {
                when {
                    error != null -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("检查更新")
                            .setMessage(error!!)
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                    latestTag != null && latestTag!!.removePrefix("v") == currentVersion().removePrefix("v") -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("检查更新")
                            .setMessage("已是最新版本（${currentVersion()}）")
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                    latestTag != null && apkUrl != null -> {
                        val msg = "当前版本：${currentVersion()}\n最新版本：${latestTag}\n" +
                                (notes?.trim()?.takeIf { it.isNotEmpty() }?.let { "\n更新说明：\n$it\n" } ?: "") +
                                "\n是否立即下载并安装？"
                        MaterialAlertDialogBuilder(this)
                            .setTitle("发现新版本")
                            .setMessage(msg)
                            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(apkUrl!!) }
                            .setNeutralButton("稍后再说", null)
                            .show()
                    }
                    else -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("检查更新")
                            .setMessage("发现新版本 $latestTag，但未找到下载链接\n请到 GitHub Releases 下载")
                            .setPositiveButton("去下载", { _, _ ->
                                try {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$packageName/releases")))
                                } catch (_: Exception) {}
                            })
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun currentVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
        } catch (_: Exception) { "未知" }
    }

    // 下载 APK 并唤起安装
    

    // 用 FileProvider 唤起安装
    /** 在 App 内下载更新，完成后拉起安装 */
    private fun downloadAndInstall(apkUrl: String) {
        val dlg = android.app.ProgressDialog(this).apply {
            setTitle("正在下载更新")
            setMessage("请稍候...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            setCanceledOnTouchOutside(false)
            max = 100
            show()
        }
        downloadProgress = dlg
        DownloadManager.downloadApk(
            this,
            apkUrl,
            onProgress = { p ->
                handler.post { try { dlg.progress = p } catch (_: Exception) {} }
            },
            onFinish = { success, path ->
                handler.post {
                    try { dlg.dismiss() } catch (_: Exception) {}
                    downloadProgress = null
                    if (success && !path.isNullOrEmpty()) {
                        installApk(path)
                    } else {
                        Snackbar.make(root, "下载失败：${path ?: "未知错误"}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    /** 安装 APK */
    private fun installApk(apkPath: String) {
        try {
            val file = File(apkPath)
            if (!file.exists()) {
                Snackbar.make(root, "APK 文件不存在", Snackbar.LENGTH_SHORT).show()
                return
            }
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Snackbar.make(root, "安装失败：${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        try { downloadProgress?.dismiss() } catch (_: Exception) {}
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try { unregisterReceiver(tunnelReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(statsReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(ddnsReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
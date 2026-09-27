package app.floatphone.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import kotlin.math.abs

/**
 * Float 小手机安卓壳：全屏 WebView 直接加载线上站点。
 * 网页每次部署即时生效，本壳只负责原生能力（推送长连接、文件上下行、外链）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        val SITE_URL: String = BuildConfig.SITE_URL
        const val VERSION = "1.0.0"
        /** 来电接听等场景的站内深链（必须以 SITE_URL 开头，否则忽略） */
        const val EXTRA_OPEN_URL = "open_url"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ---------------------------------------------------------------------
    // 本地导出服务：备份文件可能有几十上百 MB 且还在变大，不能再走
    // JavascriptInterface 传字符串那条路（无论是一次性传还是切片传，
    // 本质都要先在网页 JS 内存里攒出一份完整的 base64 文本，大文件很容易
    // 把 WebView 渲染进程内存撑爆、静默崩溃重载，表现就是"点了导出，
    // 转几下又恢复正常，什么都没发生"）。
    //
    // 改成在 App 内起一个只监听 127.0.0.1（本机回环地址，外部设备连不到）
    // 的小型 HTTP 服务，网页那边直接把 Blob 用 fetch 流式 POST 过来，原生
    // 这边一边收一边写盘，全程不把整份文件放进内存，文件多大都不受影响。
    //
    // 配套改动（必须做，否则会被系统直接拦截，不是这个文件能解决的）：
    // 1) 在 res/xml/ 下新增 network_security_config.xml（本回复已提供内容），
    //    允许 App 对 127.0.0.1 发起明文 HTTP（Android 9+ 默认全局禁止明文流量）。
    // 2) 在 AndroidManifest.xml 的 <application> 标签上加一行：
    //    android:networkSecurityConfig="@xml/network_security_config"
    // ---------------------------------------------------------------------

    private lateinit var exportServerSocket: ServerSocket
    private var exportServerThread: Thread? = null
    private val exportToken: String = UUID.randomUUID().toString()
    private val exportPort: Int get() = exportServerSocket.localPort

    /** 注入到页面里的"blob 导出劫持"脚本；端口和 token 每次启动都不同，运行时拼接。 */
    private fun buildBlobDownloadBridgeJs(port: Int, token: String): String = """
        (function() {
            if (window.__floatShellDownloadPatched) return;
            window.__floatShellDownloadPatched = true;

            var EXPORT_URL = 'http://127.0.0.1:$port/export';
            var EXPORT_TOKEN = '$token';

            function findDownloadAnchor(el) {
                while (el && el !== document) {
                    if (el.tagName === 'A' && el.hasAttribute('download')) return el;
                    el = el.parentNode;
                }
                return null;
            }

            function exportBlob(anchor) {
                var filename = anchor.getAttribute('download') || ('backup_' + Date.now());
                var href = anchor.href;
                fetch(href).then(function(res) { return res.blob(); }).then(function(blob) {
                    return fetch(EXPORT_URL, {
                        method: 'POST',
                        headers: {
                            'X-Export-Token': EXPORT_TOKEN,
                            'X-Filename': encodeURIComponent(filename),
                            'X-Mime': blob.type || 'application/octet-stream',
                        },
                        body: blob,
                    });
                }).then(function(res) {
                    if (!res || !res.ok) console.error('FloatShell export upload failed', res && res.status);
                }).catch(function(e) {
                    console.error('FloatShell export fetch/upload failed', e);
                });
            }

            // 捕获阶段：抢在浏览器默认下载行为、以及页面自身的 click 监听之前拿到事件
            document.addEventListener('click', function(e) {
                var anchor = findDownloadAnchor(e.target);
                if (anchor && anchor.href && anchor.href.indexOf('blob:') === 0) {
                    e.preventDefault();
                    e.stopPropagation();
                    exportBlob(anchor);
                }
            }, true);
        })();
    """.trimIndent()

    /** 启动只监听本机回环地址的导出服务；绑定端口是同步的，很快，不会卡主线程。 */
    private fun startExportServer() {
        exportServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        exportServerThread = Thread {
            while (!exportServerSocket.isClosed) {
                val socket = try {
                    exportServerSocket.accept()
                } catch (e: Exception) {
                    break // socket 被关闭（onDestroy）时 accept() 会抛异常，正常退出循环
                }
                Thread { handleExportConnection(socket) }.start()
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopExportServer() {
        exportServerThread = null
        runCatching { exportServerSocket.close() }
    }

    private fun handleExportConnection(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream()
                val head = readHttpRequestHead(input) ?: return
                if (head.method == "OPTIONS") {
                    writeHttpResponse(s, 200, isPreflight = true)
                    return
                }
                val token = head.headers["x-export-token"]
                if (token != exportToken) {
                    writeHttpResponse(s, 403, textBody = "forbidden")
                    return
                }
                val filenameRaw = head.headers["x-filename"] ?: "backup_${System.currentTimeMillis()}"
                val filename = runCatching { URLDecoder.decode(filenameRaw, "UTF-8") }.getOrDefault(filenameRaw)
                val mime = head.headers["x-mime"]?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val contentLength = head.headers["content-length"]?.toLongOrNull()
                if (contentLength == null || contentLength < 0) {
                    writeHttpResponse(s, 411, textBody = "length required")
                    return
                }
                val saved = saveStreamToDownloads(filename, mime, input, contentLength)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        if (saved) "已保存到「下载」目录：$filename" else "保存失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                writeHttpResponse(s, if (saved) 200 else 500, textBody = if (saved) "ok" else "save failed")
            } catch (e: Exception) {
                runCatching { writeHttpResponse(s, 500, textBody = "error: ${e.message}") }
            }
        }
    }

    private class HttpHead(val method: String, val headers: Map<String, String>)

    /** 手动按字节读到 "\r\n\r\n" 为止，不能用 BufferedReader 包一层——那样会预读多余字节，
     *  把紧跟在头部后面的二进制正文也吞掉，后续按 Content-Length 读正文就会少字节。 */
    private fun readHttpRequestHead(input: InputStream): HttpHead? {
        val buffer = ByteArrayOutputStream()
        val last4 = IntArray(4) { -1 }
        while (true) {
            val b = input.read()
            if (b == -1) return null
            buffer.write(b)
            last4[0] = last4[1]; last4[1] = last4[2]; last4[2] = last4[3]; last4[3] = b
            if (last4[0] == '\r'.code && last4[1] == '\n'.code && last4[2] == '\r'.code && last4[3] == '\n'.code) break
            if (buffer.size() > 64 * 1024) return null // 头部异常大，防御性放弃
        }
        val lines = buffer.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val method = lines[0].substringBefore(' ')
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
            }
        }
        return HttpHead(method, headers)
    }

    private fun writeHttpResponse(socket: Socket, code: Int, textBody: String = "", isPreflight: Boolean = false) {
        val bodyBytes = textBody.toByteArray(Charsets.UTF_8)
        val statusText = if (code in 200..299) "OK" else "ERR"
        val extraCors = if (isPreflight) {
            "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: X-Export-Token, X-Filename, X-Mime, Content-Type\r\n" +
                "Access-Control-Max-Age: 600\r\n"
        } else ""
        val head = "HTTP/1.1 $code $statusText\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            extraCors +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bodyBytes)
            flush()
        }
    }

    /**
     * 按 Content-Length 从 socket 输入流里精确读取指定字节数，边读边写文件，
     * 缓冲区固定大小（64KB），内存占用跟文件大小无关，多大的文件都一样。
     */
    private fun saveStreamToDownloads(filename: String, mimeType: String, input: InputStream, length: Long): Boolean {
        val safeName = filename.ifBlank { "backup_${System.currentTimeMillis()}" }
        return try {
            val output = openDownloadsOutputStream(safeName, mimeType) ?: return false
            output.use { out ->
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val toRead = if (remaining < buffer.size) remaining.toInt() else buffer.size
                    val n = input.read(buffer, 0, toRead)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 打开系统"下载"目录下目标文件的写入流。
     * Android 10 (API 29) 及以上走 MediaStore，走 Scoped Storage，不需要额外的存储权限；
     * 低于 API 29 走传统公共目录直写，并通过 DownloadManager.addCompletedDownload
     * 让文件出现在系统"下载"App / 通知里。
     */
    private fun openDownloadsOutputStream(filename: String, mimeType: String): java.io.OutputStream? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            contentResolver.openOutputStream(uri)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            val stream = FileOutputStream(file)
            runCatching {
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).addCompletedDownload(
                    filename, filename, true, mimeType, file.absolutePath, 0L, true,
                )
            }
            stream
        }
    }

    // ---------------------------------------------------------------------

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val data = result.data?.data
        callback.onReceiveValue(if (data != null) arrayOf(data) else emptyArray())
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) PushService.start(this)
    }

    // 网页侧 getUserMedia（通话按住说话、语音条录音、视频通话摄像头）触发的
    // WebView 权限请求：先要系统运行时权限，拿到后再转授给页面。
    // 不实现 onPermissionRequest 时 WebView 会静默拒绝，页面永远拿不到麦克风。
    private var pendingWebPermissionRequest: android.webkit.PermissionRequest? = null

    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val request = pendingWebPermissionRequest ?: return@registerForActivityResult
        pendingWebPermissionRequest = null
        val granted = request.resources.filter { resource ->
            webResourcePermissions(resource).all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
    }

    private fun webResourcePermissions(resource: String): List<String> = when (resource) {
        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> listOf(Manifest.permission.RECORD_AUDIO)
        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> listOf(Manifest.permission.CAMERA)
        else -> emptyList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 音量键默认调媒体流：WebView 里的语音条/TTS 都走媒体流播放，
        // 不设的话短音频没在播时按键调的是铃声，用户感觉"音量键无效、声音巨大"
        volumeControlStream = AudioManager.STREAM_MUSIC

        // 临时开启 WebView 远程调试，方便用电脑 chrome://inspect 连上手机看控制台真实报错，
        // 排查完备份问题后可以把这行删掉/注释掉
        WebView.setWebContentsDebuggingEnabled(true)

        // 备份导出用的本地服务，端口是随机分配的，必须在注入导出脚本之前启动好
        startExportServer()

        webView = WebView(this)
        val rootLayout = EdgeSwipeBackLayout(this) {
            if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
        }
        rootLayout.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(rootLayout)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            userAgentString = "$userAgentString FloatShell/$VERSION"
            // 页面本身是 https，请求本机 127.0.0.1 的导出接口是明文 http，
            // 显式放行混合内容，避免被当成不安全资源拦截
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.addJavascriptInterface(ShellBridge(), "AndroidShell")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                // 站内导航留在壳里；http(s) 外链和自定义协议（shortcuts:// 等）交给系统
                if (scheme == "http" || scheme == "https") {
                    if (url.host == Uri.parse(SITE_URL).host) return false
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url)); true
                    }.getOrDefault(true)
                }
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                }.getOrDefault(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // 每次页面加载完成都重新注入一次：SPA 路由跳转、刷新都会重新触发
                // onPageFinished，脚本内部有 __floatShellDownloadPatched 标记防止重复劫持
                view.evaluateJavascript(buildBlobDownloadBridgeJs(exportPort, exportToken), null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val supported = request.resources.filter { webResourcePermissions(it).isNotEmpty() }
                if (supported.isEmpty()) { request.deny(); return }
                val missing = supported.flatMap { webResourcePermissions(it) }
                    .distinct()
                    .filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) { request.grant(supported.toTypedArray()); return }
                if (pendingWebPermissionRequest != null) { request.deny(); return }
                pendingWebPermissionRequest = request
                webPermissionLauncher.launch(missing.toTypedArray())
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                filePathCallback?.onReceiveValue(emptyArray())
                filePathCallback = callback
                return runCatching {
                    fileChooserLauncher.launch(params.createIntent()); true
                }.getOrElse {
                    filePathCallback = null; false
                }
            }
        }

        // 备份导出走上面的本地服务；这里的 DownloadListener 只处理真正的 http(s) 下载
        // （blob:/data: 现在由页面 JS 里注入的脚本在点击阶段直接拦截并转发给本地服务了，
        // 理论上不会再走到这里，保留一个兜底提示）
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                if (url.startsWith("blob:") || url.startsWith("data:")) {
                    Toast.makeText(this, "正在导出…", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    addRequestHeader("User-Agent", userAgent)
                    addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType),
                    )
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "已开始下载到「下载」目录", Toast.LENGTH_SHORT).show()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })

        // 冷启动带深链（如来电接听）直接加载目标；否则加载首页
        webView.loadUrl(consumeOpenUrl(intent) ?: SITE_URL)
        ensurePushService()
    }

    /** singleTask：App 已在运行时（如全屏来电页接听）通过 onNewIntent 送达深链 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = consumeOpenUrl(intent) ?: return
        // SPA 已加载：loadUrl 到同页 hash 只触发 hashchange，不会整页重载
        webView.loadUrl(target)
    }

    private fun consumeOpenUrl(intent: Intent?): String? {
        val target = intent?.getStringExtra(EXTRA_OPEN_URL) ?: return null
        intent.removeExtra(EXTRA_OPEN_URL)
        return target.takeIf { it.startsWith(SITE_URL) }
    }

    private fun ensurePushService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PushService.start(this)
        }
    }

    override fun onDestroy() {
        stopExportServer()
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    /** 暴露给网页的原生桥（网页侧可用 window.AndroidShell 特性检测壳环境）。 */
    inner class ShellBridge {
        @JavascriptInterface
        fun getVersion(): String = VERSION

        /** 打开本应用的系统设置页（引导用户关电池限制、开自启动）。 */
        @JavascriptInterface
        fun openAppSettings() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        /** 请求忽略电池优化（保活关键一步）。 */
        @SuppressLint("BatteryLife")
        @JavascriptInterface
        fun requestIgnoreBatteryOptimization() {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    /**
     * 左右两侧边缘滑动返回：从屏幕左边缘或右边缘的一小条区域起手，
     * 横向滑动超过阈值时触发返回（与返回键走同一套 canGoBack 逻辑）。
     * 只在贴边起手时接管触摸事件，其余区域完全不影响 WebView 正常的
     * 滚动、点击、长按等交互。
     */
    private class EdgeSwipeBackLayout(
        context: Context,
        private val onSwipeBack: () -> Unit,
    ) : FrameLayout(context) {

        private val edgeWidthPx = 24 * resources.displayMetrics.density // 边缘触发区宽度
        private val swipeThresholdPx = 60 * resources.displayMetrics.density // 判定为"滑动返回"的最小横向位移
        private var startX = 0f
        private var startY = 0f
        private var trackingEdge = false
        private var intercepted = false

        // 告诉系统：屏幕两侧这条边缘区域我们自己要用来做滑动返回，
        // 系统的全面屏手势（返回/回桌面）不要在这块区域抢先接管。
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && width > 0 && height > 0) {
                val edge = edgeWidthPx.toInt()
                systemGestureExclusionRects = listOf(
                    Rect(0, 0, edge, height),
                    Rect(width - edge, 0, width, height),
                )
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.x
                    startY = ev.y
                    trackingEdge = startX <= edgeWidthPx || startX >= width - edgeWidthPx
                    intercepted = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (trackingEdge && !intercepted) {
                        val dx = ev.x - startX
                        val dy = ev.y - startY
                        // 横向位移明显大于纵向位移，且已有一定幅度，才判定为边缘滑动手势，
                        // 此时才接管后续事件，避免误吞正常的纵向滚动
                        if (abs(dx) > swipeThresholdPx / 2 && abs(dx) > abs(dy) * 1.5f) {
                            intercepted = true
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    trackingEdge = false
                    intercepted = false
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!intercepted) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> return true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = ev.x - startX
                    val validSwipe =
                        (startX <= edgeWidthPx && dx > swipeThresholdPx) ||
                            (startX >= width - edgeWidthPx && dx < -swipeThresholdPx)
                    intercepted = false
                    trackingEdge = false
                    if (validSwipe) onSwipeBack()
                    return true
                }
            }
            return true
        }
    }
}

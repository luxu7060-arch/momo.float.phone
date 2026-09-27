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
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import java.io.File
import java.io.FileOutputStream
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

        /**
         * 注入到页面里的"blob 导出劫持"脚本。
         * 网页端备份/导出功能一般是：生成 Blob -> new URL.createObjectURL(blob)
         * -> 造一个 <a download> 元素 -> element.click()。
         * WebView 原生的 DownloadListener 拿到的只是 blob: 这个 URL 字符串，
         * 读不到 blob 真正的数据，所以之前的实现只能弹个"正在导出…"就结束了，
         * 文件根本没有落盘。
         *
         * 这里改成在页面 JS 上下文里拦截：只要是 a.download 且 href 以 blob: 开头
         * 的点击，就用 fetch 把 blob 内容读出来、转成 base64，再通过
         * AndroidShell.saveBase64File 传回原生真正写文件，同时阻止默认行为。
         */
        private const val BLOB_DOWNLOAD_BRIDGE_JS = """
            (function() {
                if (window.__floatShellDownloadPatched) return;
                window.__floatShellDownloadPatched = true;
                var originalClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    try {
                        if (this.download && this.href && this.href.indexOf('blob:') === 0) {
                            var filename = this.download || ('download_' + Date.now());
                            var href = this.href;
                            fetch(href).then(function(res) { return res.blob(); }).then(function(blob) {
                                var reader = new FileReader();
                                reader.onloadend = function() {
                                    try {
                                        var dataUrl = reader.result;
                                        var comma = dataUrl.indexOf(',');
                                        var meta = dataUrl.substring(5, comma);
                                        var mime = (meta.split(';')[0] || 'application/octet-stream');
                                        var base64 = dataUrl.substring(comma + 1);
                                        if (window.AndroidShell && window.AndroidShell.saveBase64File) {
                                            window.AndroidShell.saveBase64File(filename, base64, mime);
                                        }
                                    } catch (e) { console.error('FloatShell export encode failed', e); }
                                };
                                reader.readAsDataURL(blob);
                            }).catch(function(e) {
                                console.error('FloatShell export fetch failed', e);
                            });
                            return;
                        }
                    } catch (e) { console.error('FloatShell export intercept failed', e); }
                    return originalClick.apply(this, arguments);
                };
            })();
        """
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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
                view.evaluateJavascript(BLOB_DOWNLOAD_BRIDGE_JS, null)
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

        // 备份导出等下载：交给系统下载管理器，落到公共下载目录
        // 注意：blob:/data: 这一支理论上不会再走到（已在页面 JS 里被 BLOB_DOWNLOAD_BRIDGE_JS
        // 拦截并通过 AndroidShell.saveBase64File 落盘），这里保留作为兜底提示。
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

    /**
     * 把字节数组真正写到系统"下载"目录。
     * Android 10 (API 29) 及以上走 MediaStore，走 Scoped Storage，不需要额外的存储权限；
     * 低于 API 29 走传统公共目录直写，并通过 DownloadManager.addCompletedDownload
     * 让文件出现在系统"下载"App / 通知里。
     */
    private fun saveBytesToDownloads(filename: String, mimeType: String, bytes: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
            true
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { it.write(bytes) }
            runCatching {
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).addCompletedDownload(
                    filename, filename, true, mimeType, file.absolutePath, bytes.size.toLong(), true,
                )
            }
            true
        }
    }

    override fun onDestroy() {
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

        /**
         * 网页导出/备份用：把 base64 编码的文件内容真正写到系统"下载"目录。
         * 配合 [BLOB_DOWNLOAD_BRIDGE_JS] 劫持 blob 下载后调用。
         *
         * @param filename 文件名（网页侧 a.download 的值）
         * @param base64Data 不带 "data:xxx;base64," 前缀的纯 base64 内容
         * @param mimeType 文件的 MIME 类型
         * @return 是否保存成功
         */
        @JavascriptInterface
        fun saveBase64File(filename: String, base64Data: String, mimeType: String): Boolean {
            val safeName = filename.ifBlank { "backup_${System.currentTimeMillis()}" }
            val resolvedMime = mimeType.ifBlank { "application/octet-stream" }
            return try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val saved = saveBytesToDownloads(safeName, resolvedMime, bytes)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        if (saved) "已保存到「下载」目录：$safeName" else "保存失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                saved
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
                false
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

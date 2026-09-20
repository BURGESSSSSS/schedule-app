package com.burgess.schedule.pickup

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.burgess.schedule.data.PickupCodeEntity
import com.burgess.schedule.ui.theme.课程表Theme
import kotlinx.coroutines.delay
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// 校园菜鸟驿站的固定入口。不使用淘宝分享链接中的临时参数。
const val CAINIAO_PACKAGE_PAGE_URL =
    "https://pages-fast.m.taobao.com/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home"
private const val EXTRA_TARGET_URL = "com.burgess.schedule.pickup.TARGET_URL"

private const val CAINIAO_SCHOOL_ROUTE =
    "/wow/z/uniapp/1100333/last-mile-fe/m-end-school-tab/home"
private const val PICKUP_CODES_API =
    "mtop.cainiao.pickup.plus.queryMultiStaPackages4Xy"
private const val PICKUP_CODES_API_URL_PREFIX =
    "https://h5api.m.taobao.com/h5/$PICKUP_CODES_API"

private const val HOOK_JS =
    "(function(){" +
        "if(window.__pkHook){return;}window.__pkHook=true;" +
        "function send(t){try{if(t&&t.length>0&&t.length<300000){window.PickupBridge&&window.PickupBridge.onJson('" + PICKUP_CODES_API_URL_PREFIX + "',String(t));}}catch(e){}}" +
        "function hookMtop(){try{var m=window.lib&&window.lib.mtop;if(!m||typeof m.request!=='function'||m.__pkMtopHook){return;}m.__pkMtopHook=true;var r=m.request;m.request=function(o){var target=o&&o.api==='" + PICKUP_CODES_API + "';if(target){var old=o.onReceiveData;o.onReceiveData=function(x){try{send(JSON.stringify(x));}catch(e){}if(typeof old==='function'){return old.apply(this,arguments);}};}var p=r.apply(this,arguments);if(target&&p&&typeof p.then==='function'){p.then(function(x){try{send(JSON.stringify(x));}catch(e){}},function(){});}return p;};}catch(e){}}" +
        "hookMtop();setInterval(hookMtop,1200);" +
        "})();"

private const val CLEAR_WEB_DATA_JS =
    "(function(){try{window.localStorage.clear();}catch(e){}" +
        "try{window.sessionStorage.clear();}catch(e){}" +
        "try{if(window.indexedDB&&indexedDB.databases){indexedDB.databases().then(function(ds){ds.forEach(function(d){if(d&&d.name){indexedDB.deleteDatabase(d.name);}});});}}catch(e){}" +
        "return 'CLEARED';})();"

/** 通过校园菜鸟自己的输入框触发查询，不拼接私有接口、不打开全球物流页。 */
private fun buildTrackingEntryScript(trackingNumber: String): String {
    val safeNumber = JSONObject.quote(trackingNumber)
    return """
        (function(number) {
          function visible(element) {
            if (!element) return false;
            var style = window.getComputedStyle(element);
            var rect = element.getBoundingClientRect();
            return style.display !== 'none' && style.visibility !== 'hidden' &&
              rect.width > 0 && rect.height > 0;
          }
          function attempt(left) {
            var inputs = Array.prototype.slice.call(document.querySelectorAll('input,textarea'))
              .filter(visible);
            var input = inputs.find(function(element) {
              var hint = ((element.getAttribute('placeholder') || '') + ' ' +
                (element.getAttribute('aria-label') || '') + ' ' +
                (element.name || '')).toLowerCase();
              return /单号|运单|快递|包裹|waybill|tracking/.test(hint);
            }) || inputs[0];
            if (!input) {
              var entries = Array.prototype.slice.call(
                document.querySelectorAll('[data-role],button,[role="button"],a,div,span')
              ).filter(visible);
              var entry = entries.find(function(element) {
                var text = (element.innerText || element.textContent || '').trim();
                return /查询取件码|添加包裹|查找包裹|搜索包裹|查快递/.test(text) && text.length < 30;
              });
              if (entry) entry.click();
              if (left > 0) setTimeout(function() { attempt(left - 1); }, 700);
              return;
            }
            var setter = Object.getOwnPropertyDescriptor(
              window.HTMLInputElement && window.HTMLInputElement.prototype, 'value'
            );
            if (setter && setter.set) setter.set.call(input, number);
            else input.value = number;
            ['input', 'change'].forEach(function(type) {
              input.dispatchEvent(new Event(type, {bubbles: true}));
            });
            input.focus();
            var scope = input.closest('form') || input.parentElement && input.parentElement.parentElement;
            var buttons = Array.prototype.slice.call(
              (scope || document).querySelectorAll('button,[role="button"],a,div,span')
            ).filter(visible);
            var button = buttons.find(function(element) {
              var text = (element.innerText || element.textContent || '').trim();
              return text === '查询' || text === '搜索' || text === '添加包裹' ||
                text === '查取件码' || text === '查快递';
            });
            if (button) {
              button.click();
              return;
            }
            input.dispatchEvent(new KeyboardEvent('keydown', {
              key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true
            }));
          }
          attempt(8);
          return 'STARTED';
        })(""" + safeNumber + ");"
}

// 抓包数据的本地存储：追加写、总量封顶，只在需要时导出。
class PickupCaptureStore(
    context: Context,
    private val onCodes: (List<PickupCodeEntity>) -> Unit = {}
) {
    private val lock = Any()
    private val file = File(context.filesDir, "pickup_capture.log")

    fun append(url: String, body: String) {
        synchronized(lock) {
            try {
                if (file.length() > 2_000_000L) return
                file.appendText("###URL### $url\n$body\n\n")
                Log.d("PickupCapture", "captured url=$url bytes=${body.length}")
                if (body.contains("\"pickupCode\"")) {
                    parsePickupCodes(body).takeIf { it.isNotEmpty() }?.let(onCodes)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun hasData(): Boolean = file.exists() && file.length() > 0L

    fun fileForExport(): File? = file.takeIf { it.exists() && it.length() > 0L }

    fun clear() {
        synchronized(lock) { file.delete() }
    }
}

/** 从校园菜鸟首页的 JSON 响应中提取取件码，避免 OCR 和 HTML 文本猜测。 */
private fun parsePickupCodes(body: String): List<PickupCodeEntity> {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
    val result = linkedMapOf<String, PickupCodeEntity>()

    fun walk(value: Any?, inheritedStation: String = "", inheritedTime: Long = System.currentTimeMillis()) {
        when (value) {
            is JSONObject -> {
                val stationFromGroup = value.optJSONObject("staInfo")?.optString("stationName").orEmpty()
                val station = value.optString("stationName")
                    .ifBlank { stationFromGroup }
                    .ifBlank { inheritedStation }
                val time = value.optLong("collectionTime", inheritedTime)
                    .takeIf { it > 0L } ?: inheritedTime
                val code = value.optString("pickupCode").trim()
                if (code.isNotBlank() && code != "null") {
                    val status = value.optString("packageStatus")
                        .ifBlank { value.optString("status") }
                        .ifBlank { "待取件" }
                    result["$station\u0000$code"] = PickupCodeEntity(
                        code = code,
                        station = station,
                        status = status,
                        picked = false,
                        createdAt = time
                    )
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    walk(value.opt(key), station, time)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    walk(value.opt(index), inheritedStation, inheritedTime)
                }
            }
            is String -> {
                if (value.contains("\"pickupCode\"")) {
                    runCatching { JSONObject(value) }.getOrNull()?.let {
                        walk(it, inheritedStation, inheritedTime)
                    }
                }
            }
        }
    }

    walk(root)
    return result.values.toList()
}

class PickupWebViewActivity : ComponentActivity() {
    companion object {
        fun createIntent(context: Context, targetUrl: String? = null): Intent =
            Intent(context, PickupWebViewActivity::class.java).apply {
                targetUrl?.let { putExtra(EXTRA_TARGET_URL, it) }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        val store = PickupCaptureStore(this)
        val targetUrl = supportedInitialUrl(intent.getStringExtra(EXTRA_TARGET_URL))
        setContent {
            课程表Theme {
                PickupWebViewPage(
                    store = store,
                    targetUrl = targetUrl,
                    onClose = { finish() }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}

private fun supportedInitialUrl(candidate: String?): String {
    val url = candidate?.trim().orEmpty()
    if (url.isBlank()) return CAINIAO_PACKAGE_PAGE_URL

    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return CAINIAO_PACKAGE_PAGE_URL
    if (uri.scheme != "https") return CAINIAO_PACKAGE_PAGE_URL
    return when (uri.host?.lowercase()) {
        "pages-fast.m.taobao.com" ->
            if (uri.path?.trimEnd('/') == CAINIAO_SCHOOL_ROUTE) url
            else CAINIAO_PACKAGE_PAGE_URL
        else -> CAINIAO_PACKAGE_PAGE_URL
    }
}

private fun isOfficialPickupHost(host: String?): Boolean {
    val normalized = host?.lowercase().orEmpty()
    return normalized != "global.cainiao.com" &&
        !normalized.endsWith(".global.cainiao.com") &&
        (normalized == "taobao.com" || normalized.endsWith(".taobao.com") ||
            normalized == "cainiao.com" || normalized.endsWith(".cainiao.com"))
}

private fun isAllowedPickupNavigation(url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    return uri.scheme == "https" && isOfficialPickupHost(uri.host)
}

@Composable
fun PickupWebViewPanel(
    targetUrl: String,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    trackingNumber: String? = null,
    onTrackingRequestHandled: () -> Unit = {},
    onTrackingFeedback: (String) -> Unit = {},
    onLogout: () -> Unit = {},
    onPickupCodes: (List<PickupCodeEntity>) -> Unit = {}
) {
    val context = LocalContext.current
    val store = remember { PickupCaptureStore(context, onPickupCodes) }
    PickupWebViewContent(
        store = store,
        targetUrl = targetUrl,
        modifier = modifier,
        embedded = true,
        onClose = onClose,
        trackingNumber = trackingNumber,
        onTrackingRequestHandled = onTrackingRequestHandled,
        onTrackingFeedback = onTrackingFeedback,
        onLogout = onLogout
    )
}

@Composable
private fun PickupWebViewPage(
    store: PickupCaptureStore,
    targetUrl: String,
    onClose: () -> Unit
) {
    PickupWebViewContent(
        store = store,
        targetUrl = targetUrl,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        embedded = false,
        onClose = onClose
    )
}

@Composable
private fun PickupWebViewContent(
    store: PickupCaptureStore,
    targetUrl: String,
    modifier: Modifier,
    embedded: Boolean,
    onClose: () -> Unit,
    trackingNumber: String? = null,
    onTrackingRequestHandled: () -> Unit = {},
    onTrackingFeedback: (String) -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(100) }
    var activeTargetUrl by remember { mutableStateOf(targetUrl) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var lastRequestedUrl by remember { mutableStateOf(targetUrl) }
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onJson(url: String?, body: String?) {
                        if (body.isNullOrBlank() || url.orEmpty() != PICKUP_CODES_API_URL_PREFIX) return
                        store.append(url.orEmpty(), body)
                    }
                },
                "PickupBridge"
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    return !isAllowedPickupNavigation(url)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    lastRequestedUrl = url
                    loadError = null
                    // evaluateJavascript 不受页面 CSP 限制，作为内联注入被拦时的第二保险。
                    view.evaluateJavascript(HOOK_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // 不接管主文档请求，避免破坏 UniApp 的登录、重定向和资源加载；
                    // 这里仅作为可选的后续 JSON 捕获补注入点。
                    view.evaluateJavascript(HOOK_JS, null)
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        loadError = "页面暂时无法打开，请检查网络后重试"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        loadError = "页面返回异常（${errorResponse.statusCode}），请稍后重试"
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
        }
    }
    LaunchedEffect(targetUrl) {
        activeTargetUrl = targetUrl
    }
    LaunchedEffect(activeTargetUrl, trackingNumber) {
        val number = trackingNumber?.trim().orEmpty()
        if (number.isBlank()) return@LaunchedEffect
        delay(900)
        webView.evaluateJavascript(buildTrackingEntryScript(number)) { rawResult ->
            val result = rawResult.orEmpty().trim('"')
            onTrackingFeedback(
                when (result) {
                    "STARTED" -> "已在校园菜鸟页面打开查询入口，正在提交单号"
                    else -> "已在校园菜鸟页面尝试查询该单号"
                }
            )
        }
        onTrackingRequestHandled()
    }
    LaunchedEffect(activeTargetUrl) {
        if (webView.url != activeTargetUrl) {
            progress = 0
            loadError = null
            lastRequestedUrl = activeTargetUrl
            webView.loadUrl(activeTargetUrl)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            CookieManager.getInstance().flush()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (embedded) {
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            )
    ) {
        fun logout() {
            webView.evaluateJavascript(CLEAR_WEB_DATA_JS, null)
            webView.clearCache(true)
            webView.clearHistory()
            store.clear()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                onLogout()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) {
                Text("‹ 返回")
            }
            TextButton(onClick = { activeTargetUrl = CAINIAO_PACKAGE_PAGE_URL }) {
                Text("驿站首页")
            }
            Text(
                text = "校园菜鸟驿站",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = ::logout) {
                Text("退出账号")
            }
        }
        loadError?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    loadError = null
                    progress = 0
                    webView.loadUrl(lastRequestedUrl)
                }) {
                    Text("重试")
                }
            }
        }
        if (progress < 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        AndroidView(
            factory = { webView },
            modifier = if (embedded) {
                Modifier
                    .fillMaxWidth()
                    .height(440.dp)
            } else {
                Modifier.fillMaxSize()
            }
        )
    }
}

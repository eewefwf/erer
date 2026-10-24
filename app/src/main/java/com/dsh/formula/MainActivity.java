package com.dsh.formula;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * WebView 外壳：
 * - 用 WebViewAssetLoader 通过 https://appassets.androidplatform.net/ 提供 assets 页面（安全上下文）
 * - 接管 <input type="file" capture> → 系统相机
 * - 接收其它 App「分享」过来的文本（白描/布丁识别结果）→ 自动填进页面
 */
public class MainActivity extends AppCompatActivity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> fileCallback;
    private Uri pendingCameraUri;

    /** 页面还没加载好时，先把分享来的文字存着 */
    private String pendingShareText = null;
    private boolean pageReady = false;

    private final ActivityResultLauncher<Intent> filePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri[] uris = null;
                Intent data = result.getData();

                if (result.getResultCode() == RESULT_OK) {
                    if (data != null && data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    } else if (pendingCameraUri != null) {
                        uris = new Uri[]{pendingCameraUri};
                    } else if (data != null && data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        uris = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            uris[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }

                if (fileCallback != null) {
                    fileCallback.onReceiveValue(uris);
                    fileCallback = null;
                }
                pendingCameraUri = null;
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
                Uri url = request.getUrl();
                String host = url.getHost();
                String scheme = url.getScheme() == null ? "" : url.getScheme();

                if ("appassets.androidplatform.net".equals(host)) return false;

                if (scheme.equals("tel") || scheme.equals("sms") || scheme.equals("mailto")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (ActivityNotFoundException ignored) {
                        Toast.makeText(MainActivity.this, "没有可处理该链接的应用", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                pageReady = true;
                if (pendingShareText != null) {
                    injectText(pendingShareText);
                    pendingShareText = null;
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Toast.makeText(MainActivity.this, "页面加载失败：" + description, Toast.LENGTH_LONG).show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;

                boolean wantCamera = params != null && params.isCaptureEnabled();
                Intent intent;

                if (wantCamera) {
                    try {
                        File photo = createImageFile();
                        pendingCameraUri = FileProvider.getUriForFile(
                                MainActivity.this,
                                getPackageName() + ".fileprovider",
                                photo);
                        intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (IOException | IllegalArgumentException e) {
                        pendingCameraUri = null;
                        intent = buildGalleryIntent(params);
                    }
                } else {
                    intent = buildGalleryIntent(params);
                }

                try {
                    filePicker.launch(intent);
                    return true;
                } catch (ActivityNotFoundException e) {
                    if (fileCallback != null) {
                        fileCallback.onReceiveValue(null);
                        fileCallback = null;
                    }
                    pendingCameraUri = null;
                    Toast.makeText(MainActivity.this, "没有找到可用的相机/相册应用", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.loadUrl(START_URL);
        WebView.setWebContentsDebuggingEnabled(true);

        // 页面可以通过 window.AndroidApp.shareText(...) 调起系统分享
        webView.addJavascriptInterface(new ShareBridge(), "AndroidApp");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    /** 从「分享」里取出文字，交给页面 */
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;

        String type = intent.getType();
        if (type == null || !type.startsWith("text/")) return;

        CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String text = cs == null ? null : cs.toString();
        if (text == null || text.trim().isEmpty()) {
            Toast.makeText(this, "分享内容为空", Toast.LENGTH_SHORT).show();
            return;
        }

        if (pageReady && webView != null) {
            injectText(text);
        } else {
            pendingShareText = text;
        }
    }

    /** 把文字注入页面：window.receiveOCRResult("...") */
    private void injectText(String text) {
        if (webView == null) return;
        String quoted = JSONObject.quote(text);
        String js = "window.receiveOCRResult(" + quoted + ");";
        webView.evaluateJavascript(js, null);
        Toast.makeText(this, "已接收分享的文字", Toast.LENGTH_SHORT).show();
    }

    /** 页面 → 原生：分享文本 */
    private class ShareBridge {
        @android.webkit.JavascriptInterface
        public void shareText(final String text) {
            if (text == null || text.trim().isEmpty()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(i, "分享到"));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(MainActivity.this, "没有可分享的应用", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    private Intent buildGalleryIntent(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (params != null) {
            String[] accept = params.getAcceptTypes();
            if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty()) {
                String t = accept[0];
                if (t.startsWith("image/")) intent.setType(t);
            }
        }
        return intent;
    }

    private File createImageFile() throws IOException {
        File dir = new File(getCacheDir(), "camera");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录");
        }
        return File.createTempFile("shot_", ".jpg", dir);
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

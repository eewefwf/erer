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
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.File;
import java.io.IOException;

/**
 * 一个最小的 WebView 外壳：
 * - 用 WebViewAssetLoader 通过 https://appassets.androidplatform.net/ 提供 assets 里的页面
 *   → 页面处于“安全上下文”，navigator.clipboard 可用，CDN 请求也不会被拦
 * - 接管 <input type="file" capture> 的请求，直接调系统相机
 * - 返回键先退回网页历史，再退出 App
 */
public class MainActivity extends AppCompatActivity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private WebViewAssetLoader assetLoader;

    /** 网页 <input type="file"> 的回调，等用户拍完/选完图再回填 */
    private ValueCallback<Uri[]> fileCallback;
    /** 拍照时预先生成的目标文件 Uri */
    private Uri pendingCameraUri;

    private final ActivityResultLauncher<Intent> filePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri[] uris = null;
                Intent data = result.getData();

                if (result.getResultCode() == RESULT_OK) {
                    if (data != null && data.getData() != null) {
                        // 相册选择
                        uris = new Uri[]{data.getData()};
                    } else if (pendingCameraUri != null) {
                        // 拍照：相机 App 把图写进了 EXTRA_OUTPUT，这里没有 data
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
        s.setDomStorageEnabled(true);        // localStorage 存草稿，必须开
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);         // 页面走 assets，不需要文件访问
        s.setAllowContentAccess(true);       // 相册返回的 content:// 需要
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);                  // 不跟随系统字体放大，避免表格挤爆
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

                // 自己的页面：正常加载
                if ("appassets.androidplatform.net".equals(host)) return false;

                // 电话/短信/邮件交给系统
                if (scheme.equals("tel") || scheme.equals("sms") || scheme.equals("mailto")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (ActivityNotFoundException ignored) {
                        Toast.makeText(MainActivity.this, "没有可处理该链接的应用", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }

                // 其它外链不开在壳里，避免跑偏
                return true;
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                Toast.makeText(MainActivity.this,
                        "页面加载失败：" + description, Toast.LENGTH_LONG).show();
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
                        // 建不了临时文件就退回相册
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

        // 方便用 Chrome 的 chrome://inspect 调试；个人使用无需关闭
        WebView.setWebContentsDebuggingEnabled(true);

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
    }

    private Intent buildGalleryIntent(WebChromeClient.FileChooserParams params) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (params != null) {
            String[] accept = params.getAcceptTypes();
            if (accept != null && accept.length > 0 && accept[0] != null && !accept[0].isEmpty()) {
                String type = accept[0];
                // 页面里是 image/*，这里做个白名单保护，其它类型统一按图片处理
                if (type.startsWith("image/")) intent.setType(type);
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
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

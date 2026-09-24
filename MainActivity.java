package com.gestionstock.android;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_BT = 9001;
    private static final int REQ_CAMERA = 9002;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int PRINT_WIDTH = 576; // XP-P810: 72mm at 203dpi

    private FrameLayout root;
    private WebView webView;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("printer", MODE_PRIVATE);
        root = new FrameLayout(this);
        webView = new WebView(this);
        setupWebView(webView);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        addPrinterButton();
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        wv.addJavascriptInterface(new PrinterBridge(), "AndroidPrinter");
        wv.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestCameraPermission();
                    } else {
                        request.grant(request.getResources());
                    }
                });
            }
        });
    }

    private void addPrinterButton() {
        Button b = new Button(this);
        b.setText("🖨️");
        b.setTextSize(20);
        b.setContentDescription("إعدادات الطابعة");
        b.setOnClickListener(v -> openPrinterDialog());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(62, 62, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, 16, 24);
        root.addView(b, lp);
    }

    private void requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT) openPrinterDialog();
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureBtPermission(Runnable after) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else after.run();
    }

    private void openPrinterDialog() {
        ensureBtPermission(() -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) { toast("الهاتف لا يدعم Bluetooth"); return; }
            if (!adapter.isEnabled()) {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                toast("فعّل Bluetooth ثم افتح إعدادات الطابعة مرة أخرى");
                return;
            }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            List<BluetoothDevice> devices = new ArrayList<>(bonded);
            if (devices.isEmpty()) {
                new AlertDialog.Builder(this)
                    .setTitle("طابعة Bluetooth")
                    .setMessage("لا توجد طابعة مقترنة. افتح إعدادات Bluetooth في الهاتف واقترن بـ XP-P810A (غالباً الرمز 0000)، ثم ارجع إلى التطبيق.")
                    .setPositiveButton("إعدادات Bluetooth", (d,w) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("إلغاء", null).show();
                return;
            }
            String[] names = new String[devices.size()];
            for (int i=0;i<devices.size();i++) {
                BluetoothDevice d = devices.get(i);
                names[i] = (d.getName() == null ? "Bluetooth printer" : d.getName()) + "\n" + d.getAddress();
            }
            String saved = prefs.getString("address", "");
            int checked = -1;
            for (int i=0;i<devices.size();i++) if (devices.get(i).getAddress().equals(saved)) checked = i;
            new AlertDialog.Builder(this)
                .setTitle("اختيار طابعة XP-P810A")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    BluetoothDevice d = devices.get(which);
                    prefs.edit().putString("address", d.getAddress()).putString("name", d.getName()).apply();
                    dialog.dismiss();
                    toast("تم حفظ الطابعة: " + d.getName());
                })
                .setPositiveButton("اختبار الطباعة", (dialog, which) -> testPrint())
                .setNeutralButton("إعدادات Bluetooth", (dialog, which) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("إلغاء", null).show();
        });
    }

    private void testPrint() {
        String saved = prefs.getString("address", "");
        if (saved.isEmpty()) { openPrinterDialog(); return; }
        String html = "<html dir='rtl'><head><meta name='viewport' content='width=576'><style>body{font-family:Arial,sans-serif;width:576px;margin:0;padding:20px;box-sizing:border-box;text-align:center}h1{font-size:34px;margin:10px}p{font-size:26px;margin:8px}</style></head><body><h1>GESTION STOCK</h1><p>اختبار الطباعة</p><p>XP-P810A</p><p>Bluetooth OK</p><p>----------------</p><p>شكراً</p></body></html>";
        printHtml(html);
    }

    private void printHtml(String invoiceHtml) {
        ensureBtPermission(() -> {
            final String address = prefs.getString("address", "");
            if (address.isEmpty()) { toast("اختر طابعة أولاً من زر 🖨️"); openPrinterDialog(); return; }
            runOnUiThread(() -> renderAndPrint(invoiceHtml, address));
        });
    }

    private void renderAndPrint(String bodyHtml, String address) {
        final WebView printView = new WebView(this);
        WebSettings s = printView.getSettings();
        s.setJavaScriptEnabled(false);
        s.setDefaultTextEncodingName("UTF-8");
        printView.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(PRINT_WIDTH, 1200);
        lp.leftMargin = -2000;
        lp.topMargin = 0;
        root.addView(printView, lp);

        String full = "<html><head><meta name='viewport' content='width=576,initial-scale=1'><style>html,body{margin:0;padding:0;background:#fff;color:#000}body{width:576px;font-family:Arial,sans-serif;font-size:20px;line-height:1.25}.invoice{width:576px;box-sizing:border-box;padding:18px}.invoice table{width:100%;border-collapse:collapse;table-layout:fixed}.invoice th,.invoice td{padding:7px 4px;border-bottom:1px solid #999;font-size:18px;word-wrap:break-word}.invoice h1{font-size:30px}.invoice h2{font-size:26px}.center{text-align:center}.invoice-meta{font-size:18px;margin:10px 0}.totalrow{font-weight:bold;font-size:22px}</style></head><body>" + bodyHtml + "</body></html>";
        printView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("Math.ceil(document.body.scrollHeight)", value -> {
                    int h = 1000;
                    try { h = Math.max(200, Integer.parseInt(value.replaceAll("\\D", ""))); } catch (Exception ignored) {}
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) view.getLayoutParams();
                    p.height = Math.min(h + 30, 12000);
                    view.setLayoutParams(p);
                    view.postDelayed(() -> {
                        try {
                            Bitmap bitmap = Bitmap.createBitmap(PRINT_WIDTH, p.height, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(bitmap);
                            canvas.drawColor(Color.WHITE);
                            view.draw(canvas);
                            new Thread(() -> {
                                try {
                                    byte[] data = EscPos.image(bitmap);
                                    sendToPrinter(address, data);
                                    runOnUiThread(() -> {
                                        root.removeView(view);
                                        toast("تم إرسال الفاتورة إلى الطابعة");
                                        webView.evaluateJavascript("window.onPrinterResult && window.onPrinterResult(true,'تمت الطباعة')", null);
                                    });
                                } catch (Exception e) {
                                    runOnUiThread(() -> {
                                        root.removeView(view);
                                        toast("فشل الطباعة: " + e.getMessage());
                                        webView.evaluateJavascript("window.onPrinterResult && window.onPrinterResult(false," + JSONObject.quote(e.getMessage()) + ")", null);
                                    });
                                } finally { bitmap.recycle(); }
                            }).start();
                        } catch (Exception e) {
                            root.removeView(view);
                            toast("تعذر تجهيز الفاتورة للطباعة: " + e.getMessage());
                        }
                    }, 350);
                });
            }
        });
        printView.loadDataWithBaseURL(null, full, "text/html", "UTF-8", null);
    }

    private void sendToPrinter(String address, byte[] data) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IOException("Bluetooth غير متاح");
        if (!hasBtPermission()) throw new IOException("لم يتم منح إذن Bluetooth");
        BluetoothDevice device = adapter.getRemoteDevice(address);
        BluetoothSocket socket = null;
        OutputStream out = null;
        try {
            adapter.cancelDiscovery();
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            out = socket.getOutputStream();
            out.write(data);
            out.flush();
            Thread.sleep(250);
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }

    public class PrinterBridge {
        @android.webkit.JavascriptInterface public void printHtml(String html) { MainActivity.this.printHtml(html); }
        @android.webkit.JavascriptInterface public void openPrinterSettings() { runOnUiThread(MainActivity.this::openPrinterDialog); }
        @android.webkit.JavascriptInterface public boolean hasPrinter() { return !prefs.getString("address", "").isEmpty(); }
    }

    public static class EscPos {
        static byte[] image(Bitmap bmp) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{0x1B,0x40}); // init
            out.write(new byte[]{0x1B,0x61,0x01}); // center
            int widthBytes = (bmp.getWidth() + 7) / 8;
            int maxRows = 256;
            for (int y0 = 0; y0 < bmp.getHeight(); y0 += maxRows) {
                int rows = Math.min(maxRows, bmp.getHeight() - y0);
                out.write(new byte[]{0x1D,0x76,0x30,0x00,(byte)(widthBytes & 0xFF),(byte)((widthBytes >> 8)&0xFF),(byte)(rows & 0xFF),(byte)((rows >> 8)&0xFF)});
                byte[] row = new byte[widthBytes];
                for (int y=0;y<rows;y++) {
                    int yy=y0+y;
                    for (int xb=0;xb<widthBytes;xb++) {
                        int bits=0;
                        for(int bit=0;bit<8;bit++) {
                            int x=xb*8+bit;
                            if(x>=bmp.getWidth()) continue;
                            int c=bmp.getPixel(x,yy);
                            int r=Color.red(c), g=Color.green(c), b=Color.blue(c);
                            int lum=(299*r+587*g+114*b)/1000;
                            if(lum<180) bits |= (1 << (7-bit));
                        }
                        row[xb]=(byte)bits;
                    }
                    out.write(row);
                }
            }
            out.write(new byte[]{0x1B,0x61,0x01});
            out.write("\n\n\n".getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        }
    }
}

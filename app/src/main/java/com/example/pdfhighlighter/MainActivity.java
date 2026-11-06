package com.example.pdfhighlighter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends Activity implements PdfView.Listener {

    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE_AS = 2;

    private static final int[] HL_COLORS = {0xFFFFEB3B, 0xFF69F0AE, 0xFFFF80AB, 0xFF80D8FF, 0xFFFFAB40};
    private static final int[] PEN_COLORS = {0xFF212121, 0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00};
    private static final float[] HL_W = {0.022f, 0.034f, 0.05f};
    private static final float[] PEN_W = {0.0035f, 0.007f, 0.012f};
    private static final String[] SIZE_NAMES = {"باریک", "متوسط", "ضخیم"};

    private PdfView pdfView;
    private TextView indicator;
    private LinearLayout emptyView;
    private LinearLayout toolbar;
    private LinearLayout colorRow;
    private TextView btnHand, btnHl, btnPen, btnErase, btnUndo, btnSize;
    private final View[] dots = new View[5];

    private SharedPreferences prefs;
    private HighlightStore store;
    private String docKey;
    private File currentFile;
    private int pageCount = 0;

    // ---- ذخیره داخل PDF
    private Uri currentUri;
    private String currentName;
    private volatile boolean saving;
    private boolean clearStoreOnLoad;
    private int restorePage = -1;
    private File pendingOut;
    private boolean pendingReload;
    private Runnable pendingAfter;
    private int pendingPage;

    private int tool = PdfView.TOOL_HAND;
    private int hlColor, penColor, hlSize, penSize;
    private boolean straighten;
    private boolean hintShown;

    // ------------------------------------------------------------------ چرخه‌ی عمر

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        hlColor = prefs.getInt("hl_color", 0);
        penColor = prefs.getInt("pen_color", 0);
        hlSize = prefs.getInt("hl_size", 1);
        penSize = prefs.getInt("pen_size", 1);
        straighten = prefs.getBoolean("straighten", true);
        hintShown = prefs.getBoolean("hint_shown", false);
        buildUi();
        pdfView.setStraighten(straighten);
        applyBrush();
        updateToolbar();
        handleIntent(getIntent(), true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, false);
    }

    private void handleIntent(Intent in, boolean allowLast) {
        Uri uri = (in != null && Intent.ACTION_VIEW.equals(in.getAction())) ? in.getData() : null;
        if (uri != null) {
            openUri(uri, false);
        } else if (allowLast) {
            String last = prefs.getString("last_uri", null);
            if (last != null) openUri(Uri.parse(last), true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveScrollPos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pdfView.release();
        if (currentFile != null) currentFile.delete();
    }

    @Override
    public void onBackPressed() {
        if (tool != PdfView.TOOL_HAND) {
            setTool(PdfView.TOOL_HAND);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onPageChanged(int page, int count) {
        indicator.setText("صفحه " + (page + 1) + " از " + count);
    }

    // ------------------------------------------------------------------ رابط کاربری

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(0xFF757575);

        pdfView = new PdfView(this);
        pdfView.setListener(this);
        content.addView(pdfView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        indicator = new TextView(this);
        indicator.setTextColor(Color.WHITE);
        indicator.setBackgroundColor(0xB0000000);
        indicator.setPadding(dp(12), dp(6), dp(12), dp(6));
        indicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ilp.topMargin = dp(8);
        content.addView(indicator, ilp);

        emptyView = new LinearLayout(this);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        TextView tv = new TextView(this);
        tv.setText("برای شروع یک فایل PDF باز کنید");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(18);
        tv.setGravity(Gravity.CENTER);
        emptyView.addView(tv);
        Button btn = new Button(this);
        btn.setText("باز کردن PDF");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickPdf();
            }
        });
        emptyView.addView(btn);
        content.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- نوار ابزار پایین
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setBackgroundColor(0xFFFAFAFA);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));
        toolbar.setVisibility(View.GONE);

        colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.setMargins(dp(6), dp(2), dp(6), dp(6));
            d.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickColor(idx);
                }
            });
            colorRow.addView(d, lp);
            dots[i] = d;
        }
        btnSize = makeButton("ضخامت", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSize();
            }
        });
        colorRow.addView(btnSize, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        toolbar.addView(colorRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnHand = makeButton("دست", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTool(PdfView.TOOL_HAND);
            }
        });
        btnHl = makeButton("هایلایت", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTool(PdfView.TOOL_HIGHLIGHT);
            }
        });
        btnPen = makeButton("قلم", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTool(PdfView.TOOL_PEN);
            }
        });
        btnErase = makeButton("پاک‌کن", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTool(PdfView.TOOL_ERASE);
            }
        });
        btnUndo = makeButton("برگشت", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pdfView.undo() == null) toast("چیزی برای برگرداندن نیست");
            }
        });
        TextView[] all = {btnHand, btnHl, btnPen, btnErase, btnUndo};
        for (TextView b : all) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            row.addView(b, lp);
        }
        toolbar.addView(row);

        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private TextView makeButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(14);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setClickable(true);
        b.setOnClickListener(l);
        styleButton(b, false);
        return b;
    }

    private void styleButton(TextView b, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(active ? 0xFF1976D2 : 0xFFE0E0E0);
        b.setBackground(g);
        b.setTextColor(active ? Color.WHITE : 0xFF212121);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ ابزارها

    private void toggleTool(int t) {
        setTool(tool == t ? PdfView.TOOL_HAND : t);
    }

    private void setTool(int t) {
        if (pageCount == 0) {
            toast("ابتدا یک فایل PDF باز کنید");
            return;
        }
        tool = t;
        applyBrush();
        updateToolbar();
        if ((t == PdfView.TOOL_HIGHLIGHT || t == PdfView.TOOL_PEN) && !hintShown) {
            hintShown = true;
            prefs.edit().putBoolean("hint_shown", true).apply();
            Toast.makeText(this,
                    "با یک انگشت روی متن بکشید. با دو انگشت صفحه را جابه‌جا و زوم کنید.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void applyBrush() {
        pdfView.setTool(tool);
        if (tool == PdfView.TOOL_PEN) {
            pdfView.setBrush(PEN_COLORS[penColor], PEN_W[penSize]);
        } else {
            pdfView.setBrush(HL_COLORS[hlColor], HL_W[hlSize]);
        }
    }

    private void pickColor(int idx) {
        if (tool == PdfView.TOOL_PEN) {
            penColor = idx;
            prefs.edit().putInt("pen_color", idx).apply();
        } else {
            hlColor = idx;
            prefs.edit().putInt("hl_color", idx).apply();
        }
        applyBrush();
        updateToolbar();
    }

    private void cycleSize() {
        if (tool == PdfView.TOOL_PEN) {
            penSize = (penSize + 1) % 3;
            prefs.edit().putInt("pen_size", penSize).apply();
        } else {
            hlSize = (hlSize + 1) % 3;
            prefs.edit().putInt("hl_size", hlSize).apply();
        }
        applyBrush();
        updateToolbar();
    }

    private void updateToolbar() {
        if (btnHand == null) return;
        styleButton(btnHand, tool == PdfView.TOOL_HAND);
        styleButton(btnHl, tool == PdfView.TOOL_HIGHLIGHT);
        styleButton(btnPen, tool == PdfView.TOOL_PEN);
        styleButton(btnErase, tool == PdfView.TOOL_ERASE);
        styleButton(btnUndo, false);
        boolean brush = tool == PdfView.TOOL_HIGHLIGHT || tool == PdfView.TOOL_PEN;
        colorRow.setVisibility(brush ? View.VISIBLE : View.GONE);
        if (!brush) return;
        int[] palette = (tool == PdfView.TOOL_PEN) ? PEN_COLORS : HL_COLORS;
        int sel = (tool == PdfView.TOOL_PEN) ? penColor : hlColor;
        for (int i = 0; i < 5; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(palette[i]);
            g.setStroke(i == sel ? dp(4) : dp(1), i == sel ? 0xFF1976D2 : 0xFF9E9E9E);
            dots[i].setBackground(g);
        }
        int size = (tool == PdfView.TOOL_PEN) ? penSize : hlSize;
        btnSize.setText("ضخامت: " + SIZE_NAMES[size]);
    }

    // ------------------------------------------------------------------ باز و بسته کردن فایل

    private void pickPdf() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_OPEN);
        } catch (ActivityNotFoundException e) {
            toast("برنامه‌ای برای انتخاب فایل پیدا نشد");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            persistPermission(uri, data);
            openUri(uri, false);
            return;
        }
        if (requestCode == REQ_SAVE_AS) {
            final File out = pendingOut;
            final boolean reload = pendingReload;
            final Runnable after = pendingAfter;
            final int page = pendingPage;
            pendingOut = null;
            pendingAfter = null;
            if (out == null) return;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final Uri uri = data.getData();
                persistPermission(uri, data);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = copyToUri(out, uri);
                        out.delete();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) afterSaved(uri, reload, after, page);
                                else toast("ذخیره ممکن نشد. هایلایت‌ها داخل برنامه باقی ماندند.");
                            }
                        });
                    }
                }).start();
            } else {
                out.delete();
                toast("ذخیره لغو شد؛ هایلایت‌ها داخل برنامه باقی ماندند.");
            }
        }
    }

    private void persistPermission(Uri uri, Intent data) {
        int fl = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, fl);
        } catch (Exception e) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }
    }

    private void fail(final boolean silent, final String msg) {
        clearStoreOnLoad = false;
        restorePage = -1;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!silent) Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void closeQuietly(PdfRenderer r) {
        if (r != null) {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void openUri(final Uri uri, final boolean silent) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                PdfRenderer r = null;
                try {
                    String name = null;
                    Cursor c = null;
                    try {
                        c = getContentResolver().query(uri, null, null, null, null);
                        if (c != null && c.moveToFirst()) {
                            int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            if (ni >= 0) name = c.getString(ni);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (c != null) c.close();
                    }
                    if (name == null) name = uri.getLastPathSegment();
                    if (name == null) name = "document.pdf";

                    // کپی فایل به حافظه‌ی موقت برنامه تا PdfRenderer بتواند آن را بخواند
                    File out = new File(getCacheDir(), "pdf_" + System.currentTimeMillis() + ".pdf");
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("no stream");
                    FileOutputStream fos = new FileOutputStream(out);
                    try {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    } finally {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                        }
                        fos.close();
                    }

                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY);
                    r = new PdfRenderer(pfd);
                    final int count = r.getPageCount();
                    if (count <= 0) throw new IOException("empty");
                    final float[] w = new float[count];
                    final float[] h = new float[count];
                    for (int i = 0; i < count; i++) {
                        PdfRenderer.Page p = r.openPage(i);
                        try {
                            w[i] = Math.max(1, p.getWidth());
                            h[i] = Math.max(1, p.getHeight());
                        } finally {
                            p.close();
                        }
                    }
                    final PdfRenderer fr = r;
                    final String fname = name;
                    final File ffile = out;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onLoaded(uri, fname, ffile, fr, w, h);
                        }
                    });
                } catch (SecurityException e) {
                    closeQuietly(r);
                    fail(silent, "این PDF با رمز محافظت شده و پشتیبانی نمی‌شود.");
                } catch (Throwable e) {
                    closeQuietly(r);
                    fail(silent, "باز کردن فایل ممکن نشد.");
                }
            }
        }).start();
    }

    private void onLoaded(Uri uri, String name, File file, PdfRenderer r, float[] w, float[] h) {
        saveScrollPos();
        if (clearStoreOnLoad && store != null) store.clear();   // هایلایت‌ها حالا داخل خود PDF هستند
        clearStoreOnLoad = false;
        docKey = "doc_" + name + "_" + file.length();
        store = new HighlightStore(this, docKey);
        pageCount = w.length;
        currentUri = uri;
        currentName = name;
        int start = (restorePage >= 0) ? restorePage : prefs.getInt("pos_" + docKey, 0);
        restorePage = -1;
        start = Math.max(0, Math.min(start, pageCount - 1));

        pdfView.setDocument(r, w, h, store, start);   // فایل قبلی را خودش می‌بندد
        if (currentFile != null) currentFile.delete();
        currentFile = file;

        setTitle(name);
        prefs.edit().putString("last_uri", uri.toString()).apply();
        emptyView.setVisibility(View.GONE);
        indicator.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        tool = PdfView.TOOL_HAND;
        applyBrush();
        updateToolbar();
        invalidateOptionsMenu();
    }

    /** بستن فایل فعال؛ هایلایت‌ها ذخیره شده‌اند و دفعه‌ی بعد که همین فایل را باز کنید برمی‌گردند. */
    private void closeDoc() {
        if (pageCount == 0) return;
        saveScrollPos();
        pdfView.closeDocument();
        if (currentFile != null) {
            currentFile.delete();
            currentFile = null;
        }
        store = null;
        docKey = null;
        currentUri = null;
        currentName = null;
        pageCount = 0;
        tool = PdfView.TOOL_HAND;
        prefs.edit().remove("last_uri").apply();
        setTitle(R.string.app_name);
        indicator.setVisibility(View.GONE);
        toolbar.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        invalidateOptionsMenu();
    }

    /**
     * هایلایت‌ها را به‌صورت حاشیه‌نویسی داخل خود فایل PDF می‌نویسد.
     * اگر فایل قابل بازنویسی نباشد، محل ذخیره‌ی یک نسخه‌ی جدید پرسیده می‌شود.
     * after بعد از ذخیره‌ی موفق (یا اگر چیزی برای ذخیره نبود) اجرا می‌شود.
     */
    private void saveToPdf(final boolean reload, final Runnable after) {
        if (store == null || pageCount == 0) {
            if (after != null) after.run();
            return;
        }
        saveScrollPos();
        store.flush();
        final List<HighlightStore.H> strokes = store.all();
        if (strokes.isEmpty()) {
            toast("هایلایت جدیدی برای ذخیره نیست");
            if (after != null) after.run();
            return;
        }
        if (saving) return;
        saving = true;
        toast("در حال ذخیره در فایل PDF…");
        final File src = currentFile;
        final Uri dest = currentUri;
        final int page = pdfView.getCurrentPage();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final File out = new File(getCacheDir(), "out_" + System.currentTimeMillis() + ".pdf");
                try {
                    PdfExporter.embed(getApplicationContext(), src, out, strokes);
                } catch (Throwable t) {
                    out.delete();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            saving = false;
                            Toast.makeText(MainActivity.this,
                                    "ذخیره در PDF ممکن نشد. هایلایت‌ها داخل برنامه ذخیره‌اند.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
                final boolean wrote = dest != null && copyToUri(out, dest);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        saving = false;
                        if (wrote) {
                            out.delete();
                            afterSaved(dest, reload, after, page);
                        } else {
                            pendingOut = out;
                            pendingReload = reload;
                            pendingAfter = after;
                            pendingPage = page;
                            startSaveAs();
                        }
                    }
                });
            }
        }).start();
    }

    private void afterSaved(Uri dest, boolean reload, Runnable after, int page) {
        toast("هایلایت‌ها داخل فایل PDF ذخیره شد");
        if (reload) {
            clearStoreOnLoad = true;
            restorePage = page;
            openUri(dest, false);
        } else {
            if (store != null) store.clear();
            if (after != null) after.run();
        }
    }

    private void startSaveAs() {
        String base = (currentName == null) ? "document" : currentName;
        if (base.toLowerCase().endsWith(".pdf")) base = base.substring(0, base.length() - 4);
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
            i.putExtra(Intent.EXTRA_TITLE, base + "_highlighted.pdf");
            Toast.makeText(this, "این فایل قابل بازنویسی نیست؛ محل ذخیره‌ی نسخه‌ی جدید را انتخاب کنید",
                    Toast.LENGTH_LONG).show();
            startActivityForResult(i, REQ_SAVE_AS);
        } catch (ActivityNotFoundException e) {
            if (pendingOut != null) pendingOut.delete();
            pendingOut = null;
            pendingAfter = null;
            toast("برنامه‌ای برای انتخاب محل ذخیره پیدا نشد");
        }
    }

    private boolean copyToUri(File f, Uri uri) {
        InputStream in = null;
        OutputStream os = null;
        try {
            os = getContentResolver().openOutputStream(uri, "wt");
            if (os == null) return false;
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (os != null) os.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void saveScrollPos() {
        if (docKey != null && pageCount > 0) {
            prefs.edit().putInt("pos_" + docKey, pdfView.getCurrentPage()).apply();
        }
    }

    // ------------------------------------------------------------------ منو

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean has = pageCount > 0;
        int[] ids = {R.id.action_save, R.id.action_close, R.id.action_goto, R.id.action_list, R.id.action_fit,
                R.id.action_straighten, R.id.action_clear};
        for (int id : ids) {
            MenuItem it = menu.findItem(id);
            if (it != null) it.setVisible(has);
        }
        MenuItem st = menu.findItem(R.id.action_straighten);
        if (st != null) st.setChecked(straighten);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save) {      // ذخیره‌ی هایلایت‌ها داخل خود PDF
            saveToPdf(true, null);
            return true;
        }
        if (id == R.id.action_open) {      // «+» : اول ذخیره، بعد انتخاب فایل جدید
            saveToPdf(false, new Runnable() {
                @Override
                public void run() {
                    pickPdf();
                }
            });
            return true;
        }
        if (id == R.id.action_close) {     // «−» : اول ذخیره، بعد بستن فایل
            saveToPdf(false, new Runnable() {
                @Override
                public void run() {
                    closeDoc();
                }
            });
            return true;
        }
        if (id == R.id.action_goto) {
            showGoto();
            return true;
        }
        if (id == R.id.action_list) {
            showHighlightList();
            return true;
        }
        if (id == R.id.action_fit) {
            pdfView.resetZoom();
            return true;
        }
        if (id == R.id.action_straighten) {
            straighten = !straighten;
            item.setChecked(straighten);
            prefs.edit().putBoolean("straighten", straighten).apply();
            pdfView.setStraighten(straighten);
            return true;
        }
        if (id == R.id.action_clear) {
            confirmClear();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showGoto() {
        if (pageCount == 0) return;
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 - " + pageCount);
        new AlertDialog.Builder(this)
                .setTitle("رفتن به صفحه")
                .setView(et)
                .setPositiveButton("برو", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            int p = Integer.parseInt(et.getText().toString().trim());
                            pdfView.goToPage(Math.max(1, Math.min(pageCount, p)) - 1);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void showHighlightList() {
        if (store == null) return;
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        for (HighlightStore.H h : store.all()) {
            Integer c = counts.get(h.page);
            counts.put(h.page, c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) {
            toast("هنوز چیزی ذخیره نشده");
            return;
        }
        final int[] pages = new int[counts.size()];
        String[] labels = new String[counts.size()];
        int i = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            pages[i] = e.getKey();
            labels[i] = "صفحه " + (e.getKey() + 1) + " — " + e.getValue() + " مورد";
            i++;
        }
        new AlertDialog.Builder(this)
                .setTitle("هایلایت‌های ذخیره‌شده")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pdfView.goToPage(pages[which]);
                    }
                })
                .show();
    }

    private void confirmClear() {
        if (store == null) return;
        new AlertDialog.Builder(this)
                .setTitle("پاک کردن همه‌ی هایلایت‌ها")
                .setMessage("همه‌ی هایلایت‌ها و نوشته‌های این فایل حذف شود؟")
                .setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.clear();
                        pdfView.refreshHighlights();
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }
}

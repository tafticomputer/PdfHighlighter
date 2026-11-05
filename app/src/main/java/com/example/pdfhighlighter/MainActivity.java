package com.example.pdfhighlighter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements PageView.Listener {

    private static final int REQ_OPEN = 1;
    private static final int[] COLORS = {0xFFFFEB3B, 0xFF69F0AE, 0xFFFF80AB, 0xFF80D8FF, 0xFFFFAB40};
    private static final String[] COLOR_NAMES = {"زرد", "سبز", "صورتی", "آبی", "نارنجی"};
    private static final int MAX_RENDER_WIDTH = 1600;

    private final Object lock = new Object();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private PdfRenderer renderer;
    private File currentFile;
    private float[] pageW = new float[0];
    private float[] pageH = new float[0];
    private int pageCount = 0;
    private volatile int generation = 0;
    private LruCache<String, Bitmap> cache;

    private HighlightStore store;
    private String docKey;
    private int mode = PageView.MODE_VIEW;
    private int colorIndex = 0;
    private float zoom = 1f;
    private boolean hintShown = false;

    private SharedPreferences prefs;
    private HorizontalScrollView hsv;
    private ListView listView;
    private TextView indicator;
    private LinearLayout emptyView;
    private PageAdapter adapter;

    // ------------------------------------------------------------------ چرخه‌ی عمر

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("app", MODE_PRIVATE);
        int mem = (int) (Runtime.getRuntime().maxMemory() / 6);
        cache = new LruCache<String, Bitmap>(Math.max(mem, 4 * 1024 * 1024)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        buildUi();
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
        exec.shutdownNow();
        synchronized (lock) {
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (Throwable ignored) {
                }
                renderer = null;
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (pageCount > 0) {
            int first = listView.getFirstVisiblePosition();
            applyZoomLayout();
            adapter.notifyDataSetChanged();
            listView.setSelection(first);
        }
    }

    @Override
    public void onBackPressed() {
        if (mode != PageView.MODE_VIEW) {
            mode = PageView.MODE_VIEW;
            applyMode();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ رابط کاربری

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int pageWidth() {
        return Math.max(1, (int) (getResources().getDisplayMetrics().widthPixels * zoom));
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF757575);

        hsv = new HorizontalScrollView(this);
        hsv.setFillViewport(true);

        listView = new ListView(this);
        listView.setDivider(new ColorDrawable(0xFF757575));
        listView.setDividerHeight(dp(8));
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        adapter = new PageAdapter();
        listView.setAdapter(adapter);
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                updateIndicator();
            }
        });
        hsv.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(hsv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        indicator = new TextView(this);
        indicator.setTextColor(Color.WHITE);
        indicator.setBackgroundColor(0xB0000000);
        indicator.setPadding(dp(12), dp(6), dp(12), dp(6));
        indicator.setVisibility(View.GONE);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ilp.bottomMargin = dp(16);
        root.addView(indicator, ilp);

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
        root.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        setContentView(root);
    }

    private void applyZoomLayout() {
        listView.setLayoutParams(new FrameLayout.LayoutParams(pageWidth(), ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void updateIndicator() {
        if (pageCount == 0 || indicator == null) {
            return;
        }
        int first = listView.getFirstVisiblePosition();
        String m = "";
        if (mode == PageView.MODE_HIGHLIGHT) {
            m = "  •  هایلایت: " + COLOR_NAMES[colorIndex];
        } else if (mode == PageView.MODE_ERASE) {
            m = "  •  پاک‌کن";
        }
        indicator.setText("صفحه " + (first + 1) + " از " + pageCount + m);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ باز کردن فایل

    private void pickPdf() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
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
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            openUri(uri, false);
        }
    }

    private void fail(final boolean silent, final String msg) {
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
        generation++;
        synchronized (lock) {
            if (renderer != null) {
                try {
                    renderer.close();
                } catch (Throwable ignored) {
                }
            }
            renderer = r;
        }
        if (currentFile != null) currentFile.delete();
        currentFile = file;
        cache.evictAll();

        pageW = w;
        pageH = h;
        pageCount = w.length;
        docKey = "doc_" + name + "_" + file.length();
        store = new HighlightStore(this, docKey);
        mode = PageView.MODE_VIEW;
        setTitle(name);
        prefs.edit().putString("last_uri", uri.toString()).apply();

        emptyView.setVisibility(View.GONE);
        indicator.setVisibility(View.VISIBLE);
        applyZoomLayout();
        adapter.notifyDataSetChanged();
        final int pos = Math.min(prefs.getInt("pos_" + docKey, 0), pageCount - 1);
        listView.post(new Runnable() {
            @Override
            public void run() {
                listView.setSelection(pos);
                updateIndicator();
            }
        });
        invalidateOptionsMenu();
        updateIndicator();
    }

    private void saveScrollPos() {
        if (docKey != null && pageCount > 0 && listView != null) {
            prefs.edit().putInt("pos_" + docKey, listView.getFirstVisiblePosition()).apply();
        }
    }

    // ------------------------------------------------------------------ رندر صفحه‌ها

    private Bitmap renderPage(int index, int w, int h, int gen) {
        synchronized (lock) {
            if (renderer == null || gen != generation) return null;
            PdfRenderer.Page page = null;
            try {
                page = renderer.openPage(index);
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } catch (Throwable t) {
                return null;
            } finally {
                if (page != null) {
                    try {
                        page.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void loadBitmap(final PageView pv, final int page, int w, int h) {
        final int rw = Math.max(1, Math.min(w, MAX_RENDER_WIDTH));
        final int rh = Math.max(1, Math.round(rw * (float) h / w));
        final int gen = generation;
        final String key = gen + "_" + page + "_" + rw;
        pv.setRenderKey(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            pv.setBitmap(cached);
            return;
        }
        pv.setBitmap(null);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                if (gen != generation || !key.equals(pv.getRenderKey())) return;
                final Bitmap b = renderPage(page, rw, rh, gen);
                if (b == null) return;
                cache.put(key, b);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(pv.getRenderKey())) pv.setBitmap(b);
                    }
                });
            }
        });
    }

    private class PageAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return pageCount;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            PageView pv = (convertView instanceof PageView) ? (PageView) convertView : new PageView(MainActivity.this);
            int w = pageWidth();
            int h = Math.max(1, Math.round(w * pageH[position] / pageW[position]));
            pv.setLayoutParams(new AbsListView.LayoutParams(w, h));
            pv.setListener(MainActivity.this);
            pv.bindPage(position, store.forPage(position), mode, COLORS[colorIndex]);
            loadBitmap(pv, position, w, h);
            return pv;
        }
    }

    // ------------------------------------------------------------------ هایلایت (PageView.Listener)

    @Override
    public void onAdd(PageView v, int page, float l, float t, float r, float b) {
        if (store == null) return;
        store.add(page, l, t, r, b, COLORS[colorIndex]);
        v.setHighlights(store.forPage(page));
    }

    @Override
    public void onRemove(PageView v, int page, HighlightStore.H h) {
        if (store == null) return;
        store.remove(h);
        v.setHighlights(store.forPage(page));
    }

    private void applyMode() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            View c = listView.getChildAt(i);
            if (c instanceof PageView) {
                ((PageView) c).setMode(mode);
                ((PageView) c).setColor(COLORS[colorIndex]);
            }
        }
        invalidateOptionsMenu();
        updateIndicator();
        if (mode == PageView.MODE_HIGHLIGHT && !hintShown) {
            hintShown = true;
            Toast.makeText(this,
                    "انگشت را روی متن بکشید تا هایلایت شود. برای حرکت بین صفحه‌ها، دوباره روی دکمه‌ی هایلایت بزنید.",
                    Toast.LENGTH_LONG).show();
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
        MenuItem hi = menu.findItem(R.id.action_highlight);
        if (hi != null) {
            hi.setTitle(mode == PageView.MODE_HIGHLIGHT ? "پایان هایلایت" : "هایلایت");
        }
        MenuItem er = menu.findItem(R.id.action_erase);
        if (er != null) {
            er.setTitle(mode == PageView.MODE_ERASE ? "پایان پاک‌کن" : "پاک‌کن هایلایت");
        }
        return super.onPrepareOptionsMenu(menu);
    }

    private boolean needDoc() {
        if (pageCount == 0 || store == null) {
            toast("ابتدا یک فایل PDF باز کنید");
            return false;
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_open) {
            pickPdf();
            return true;
        }
        if (id == R.id.action_highlight) {
            if (!needDoc()) return true;
            mode = (mode == PageView.MODE_HIGHLIGHT) ? PageView.MODE_VIEW : PageView.MODE_HIGHLIGHT;
            applyMode();
            return true;
        }
        if (id == R.id.action_erase) {
            if (!needDoc()) return true;
            mode = (mode == PageView.MODE_ERASE) ? PageView.MODE_VIEW : PageView.MODE_ERASE;
            applyMode();
            if (mode == PageView.MODE_ERASE) toast("روی هایلایت بزنید تا پاک شود");
            return true;
        }
        if (id == R.id.action_color) {
            new AlertDialog.Builder(this)
                    .setTitle("رنگ هایلایت")
                    .setItems(COLOR_NAMES, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            colorIndex = which;
                            if (pageCount > 0) mode = PageView.MODE_HIGHLIGHT;
                            applyMode();
                        }
                    })
                    .show();
            return true;
        }
        if (id == R.id.action_undo) {
            if (!needDoc()) return true;
            HighlightStore.H h = store.removeLast();
            if (h == null) {
                toast("هایلایتی برای برگرداندن نیست");
            } else {
                adapter.notifyDataSetChanged();
                listView.setSelection(h.page);
            }
            return true;
        }
        if (id == R.id.action_zoom_in) {
            changeZoom(0.5f);
            return true;
        }
        if (id == R.id.action_zoom_out) {
            changeZoom(-0.5f);
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
        if (id == R.id.action_clear) {
            confirmClear();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void changeZoom(float delta) {
        if (!needDoc()) return;
        float nz = Math.max(1f, Math.min(2.5f, zoom + delta));
        if (nz == zoom) return;
        zoom = nz;
        int first = listView.getFirstVisiblePosition();
        applyZoomLayout();
        adapter.notifyDataSetChanged();
        listView.setSelection(first);
        toast("بزرگ‌نمایی: " + Math.round(zoom * 100) + "٪");
    }

    private void showGoto() {
        if (!needDoc()) return;
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
                            p = Math.max(1, Math.min(pageCount, p));
                            listView.setSelection(p - 1);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void showHighlightList() {
        if (!needDoc()) return;
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        for (HighlightStore.H h : store.all()) {
            Integer c = counts.get(h.page);
            counts.put(h.page, c == null ? 1 : c + 1);
        }
        if (counts.isEmpty()) {
            toast("هنوز هایلایتی ندارید");
            return;
        }
        final int[] pages = new int[counts.size()];
        String[] labels = new String[counts.size()];
        int i = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            pages[i] = e.getKey();
            labels[i] = "صفحه " + (e.getKey() + 1) + " — " + e.getValue() + " هایلایت";
            i++;
        }
        new AlertDialog.Builder(this)
                .setTitle("هایلایت‌های ذخیره‌شده")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        listView.setSelection(pages[which]);
                    }
                })
                .show();
    }

    private void confirmClear() {
        if (!needDoc()) return;
        new AlertDialog.Builder(this)
                .setTitle("پاک کردن همه‌ی هایلایت‌ها")
                .setMessage("همه‌ی هایلایت‌های این فایل حذف شود؟")
                .setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        store.clear();
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }
}

package com.example.pdfhighlighter;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ذخیره‌ی هایلایت‌ها و نوشته‌های قلم برای هر فایل PDF.
 * هر خط یک مسیر از نقطه‌هاست که مختصاتش نسبی (بین ۰ و ۱) نسبت به اندازه‌ی صفحه است،
 * پس با زوم و چرخش صفحه هم در جای درست می‌ماند.
 * نسخه‌ی قبلی برنامه مستطیل ذخیره می‌کرد؛ آن‌ها هم هنوز خوانده و نمایش داده می‌شوند.
 */
public class HighlightStore {

    public static class H {
        public int page;
        public int color;     // رنگ ARGB
        public float width;   // ضخامت، به‌صورت کسری از عرض صفحه
        public float[] pts;   // x0,y0,x1,y1,... (null یعنی مستطیل نسخه‌ی قبلی)
        public float l, t, r, b; // فقط برای مستطیل نسخه‌ی قبلی
    }

    private final SharedPreferences prefs;
    private final String key;
    private final List<H> items = new ArrayList<>();
    private final SparseArray<List<H>> pageCache = new SparseArray<>();

    public HighlightStore(Context ctx, String docKey) {
        prefs = ctx.getSharedPreferences("highlights", Context.MODE_PRIVATE);
        key = docKey;
        load();
    }

    private void load() {
        items.clear();
        pageCache.clear();
        String s = prefs.getString(key, null);
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                H h = new H();
                h.page = o.getInt("p");
                h.color = o.getInt("c");
                if (o.has("pts")) {
                    JSONArray pa = o.getJSONArray("pts");
                    int n = pa.length() - (pa.length() % 2);
                    h.pts = new float[n];
                    for (int k = 0; k < n; k++) h.pts[k] = (float) pa.getDouble(k);
                    h.width = (float) o.getDouble("w");
                    if (n < 4) continue;
                } else {
                    h.l = (float) o.getDouble("l");
                    h.t = (float) o.getDouble("t");
                    h.r = (float) o.getDouble("r");
                    h.b = (float) o.getDouble("b");
                }
                items.add(h);
            }
        } catch (JSONException ignored) {
        }
    }

    private static double r4(float v) {
        return Math.round(v * 10000d) / 10000d;
    }

    private void save() {
        pageCache.clear();
        JSONArray a = new JSONArray();
        try {
            for (H h : items) {
                JSONObject o = new JSONObject();
                o.put("p", h.page);
                o.put("c", h.color);
                if (h.pts != null) {
                    o.put("w", r4(h.width));
                    JSONArray pa = new JSONArray();
                    for (float v : h.pts) pa.put(r4(v));
                    o.put("pts", pa);
                } else {
                    o.put("l", r4(h.l));
                    o.put("t", r4(h.t));
                    o.put("r", r4(h.r));
                    o.put("b", r4(h.b));
                }
                a.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(key, a.toString()).apply();
    }

    /** فهرست موردهای یک صفحه (نباید از بیرون تغییر داده شود). */
    public List<H> forPage(int page) {
        List<H> l = pageCache.get(page);
        if (l == null) {
            l = new ArrayList<>();
            for (H h : items) {
                if (h.page == page) l.add(h);
            }
            pageCache.put(page, l);
        }
        return l;
    }

    public List<H> all() {
        return new ArrayList<>(items);
    }

    public void add(int page, int color, float width, float[] pts) {
        H h = new H();
        h.page = page;
        h.color = color;
        h.width = width;
        h.pts = pts;
        items.add(h);
        save();
    }

    public void remove(H h) {
        if (items.remove(h)) save();
    }

    public H removeLast() {
        if (items.isEmpty()) return null;
        H h = items.remove(items.size() - 1);
        save();
        return h;
    }

    public void clear() {
        items.clear();
        save();
    }
}

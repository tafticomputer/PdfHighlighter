package com.example.pdfhighlighter;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ذخیره‌ی هایلایت‌های هر فایل PDF.
 * مختصات هر هایلایت نسبی (بین ۰ و ۱) نسبت به اندازه‌ی صفحه است، پس با زوم و چرخش صفحه هم درست می‌ماند.
 * داده‌ها در SharedPreferences و به‌صورت JSON نگهداری می‌شوند و بعد از بستن برنامه هم می‌مانند.
 */
public class HighlightStore {

    public static class H {
        public int page;
        public float l, t, r, b;
        public int color;
    }

    private final SharedPreferences prefs;
    private final String key;
    private final List<H> items = new ArrayList<>();

    public HighlightStore(Context ctx, String docKey) {
        prefs = ctx.getSharedPreferences("highlights", Context.MODE_PRIVATE);
        key = docKey;
        load();
    }

    private void load() {
        items.clear();
        String s = prefs.getString(key, null);
        if (s == null) return;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                H h = new H();
                h.page = o.getInt("p");
                h.l = (float) o.getDouble("l");
                h.t = (float) o.getDouble("t");
                h.r = (float) o.getDouble("r");
                h.b = (float) o.getDouble("b");
                h.color = o.getInt("c");
                items.add(h);
            }
        } catch (JSONException ignored) {
        }
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (H h : items) {
                JSONObject o = new JSONObject();
                o.put("p", h.page);
                o.put("l", (double) h.l);
                o.put("t", (double) h.t);
                o.put("r", (double) h.r);
                o.put("b", (double) h.b);
                o.put("c", h.color);
                a.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(key, a.toString()).apply();
    }

    public List<H> forPage(int page) {
        List<H> out = new ArrayList<>();
        for (H h : items) {
            if (h.page == page) out.add(h);
        }
        return out;
    }

    public List<H> all() {
        return new ArrayList<>(items);
    }

    public void add(int page, float l, float t, float r, float b, int color) {
        H h = new H();
        h.page = page;
        h.l = l;
        h.t = t;
        h.r = r;
        h.b = b;
        h.color = color;
        items.add(h);
        save();
    }

    public void remove(H h) {
        items.remove(h);
        save();
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

package com.example.pdfhighlighter;

import java.util.ArrayList;
import java.util.List;

/**
 * «تصحیح خودکار هایلایت» مثل OneDrive: خطی که کاربر تقریبی روی متن کشیده را به خط متنِ زیرش می‌چسباند
 * (ارتفاع دقیق خط، شروع و پایان روی لبه‌ی کلمه‌ها).
 *
 * ابزار نمایش PDF اندروید متن را در اختیار برنامه نمی‌گذارد، پس موقعیت خط‌ها و کلمه‌ها از روی خود تصویر صفحه
 * تشخیص داده می‌شود (تصویر برش‌خورده‌ی اطراف خطی که کاربر کشیده). برای PDFهای اسکن‌شده هم کار می‌کند و به زبان
 * متن (فارسی، انگلیسی، ...) وابسته نیست. این کلاس به اندروید وابسته نیست.
 */
public final class TextSnap {

    private TextSnap() {
    }

    public static final class Result {
        public float xMin, xMax;   // لبه‌های چپ و راست (پیکسل، در مختصات تصویر برش‌خورده)
        public float yTop, yBottom; // لبه‌های بالا و پایین خط متن
    }

    /**
     * @param px   پیکسل‌های ARGB تصویر برش‌خورده (اندازه‌ی w×h)
     * @param x0   ابتدای افقیِ خطی که کاربر کشیده
     * @param x1   انتهای افقی (x0 ≤ x1)
     * @param yc   ارتفاع میانگین خطی که کاربر کشیده
     * @param maxBand بیشینه ارتفاع مجاز برای یک خط متن (برای رد کردن عکس و نمودار)
     * @return null اگر خط متنی نزدیک پیدا نشد
     */
    public static Result snap(int[] px, int w, int h, float x0, float x1, float yc, int maxBand) {
        if (w < 8 || h < 8) return null;

        // ---- روشنایی هر پیکسل و رنگ پس‌زمینه (پرتکرارترین روشنایی)
        int[] lum = new int[w * h];
        int[] hist = new int[32];
        for (int i = 0; i < lum.length; i++) {
            int c = px[i];
            int l = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
            lum[i] = l;
            hist[l >> 3]++;
        }
        int peak = 0;
        for (int i = 1; i < hist.length; i++) if (hist[i] > hist[peak]) peak = i;
        int bg = peak * 8 + 4;
        boolean[] ink = new boolean[lum.length];
        for (int i = 0; i < lum.length; i++) ink[i] = Math.abs(lum[i] - bg) > 70;

        // ---- پروفایل سطری، فقط روی ستون‌هایی که کاربر روی آن‌ها کشیده
        int cA = Math.max(0, Math.round(x0));
        int cB = Math.min(w - 1, Math.round(x1));
        if (cB - cA < 10) {
            int mid = (cA + cB) / 2;
            cA = Math.max(0, mid - 6);
            cB = Math.min(w - 1, mid + 6);
        }
        int span = cB - cA + 1;
        int[] rowInk = new int[h];
        for (int r = 0; r < h; r++) {
            int cnt = 0;
            int base = r * w;
            for (int c = cA; c <= cB; c++) if (ink[base + c]) cnt++;
            rowInk[r] = cnt;
        }

        // ---- خط‌های متن: بخش‌های پشت‌سرهم از سطرهای پرحبر
        int core = Math.max(2, Math.round(span * 0.04f));
        List<int[]> bands = detect(rowInk, 0, h, core, 3);
        // اگر چند خط به‌خاطر فاصله‌ی کم به هم چسبیده‌اند، با آستانه‌ی بالاتر دوباره جدایشان می‌کنیم
        List<int[]> refined = new ArrayList<>();
        for (int[] b : bands) {
            if (b[1] - b[0] + 1 <= maxBand) {
                refined.add(b);
                continue;
            }
            int mx = 0;
            for (int r = b[0]; r <= b[1]; r++) mx = Math.max(mx, rowInk[r]);
            List<int[]> parts = detect(rowInk, b[0], b[1] + 1, Math.max(core, Math.round(mx * 0.35f)), 1);
            refined.addAll(parts);
        }
        bands = refined;

        // ---- نزدیک‌ترین خط به خطی که کاربر کشیده
        int[] best = null;
        float bestDist = Float.MAX_VALUE;
        for (int[] b : bands) {
            int bh = b[1] - b[0] + 1;
            if (bh < 5 || bh > maxBand) continue;
            float d = (yc < b[0]) ? b[0] - yc : (yc > b[1] ? yc - b[1] : 0f);
            if (d < bestDist) {
                bestDist = d;
                best = b;
            }
        }
        if (best == null) return null;
        int bandH = best[1] - best[0] + 1;
        if (bestDist > Math.max(8f, bandH * 1.0f)) return null;

        // ---- کلمه‌ها: ستون‌های پرحبرِ این خط که با فاصله‌ی کافی از هم جدا شده‌اند
        boolean[] col = new boolean[w];
        for (int c = 0; c < w; c++) {
            for (int r = best[0]; r <= best[1]; r++) {
                if (ink[r * w + c]) {
                    col[c] = true;
                    break;
                }
            }
        }
        int gapMin = Math.max(3, Math.round(bandH * 0.30f));
        List<int[]> words = new ArrayList<>();
        int ws = -1, we = -1;
        for (int c = 0; c < w; c++) {
            if (col[c]) {
                if (ws < 0) ws = c;
                we = c;
            } else if (ws >= 0 && c - we >= gapMin) {
                words.add(new int[]{ws, we});
                ws = -1;
            }
        }
        if (ws >= 0) words.add(new int[]{ws, we});
        if (words.isEmpty()) return null;

        // ---- از اولین کلمه‌ی تحت‌تأثیر تا آخرین کلمه (کلمه‌ی نیمه‌انتخاب‌شده کامل می‌شود)
        float lo = Math.min(x0, x1), hi = Math.max(x0, x1);
        int first = -1, last = -1;
        for (int i = 0; i < words.size(); i++) {
            int[] wd = words.get(i);
            if (first < 0 && wd[1] >= lo) first = i;
            if (wd[0] <= hi) last = i;
        }
        if (first < 0 || last < 0 || first > last) return null;

        int padX = Math.max(1, Math.round(bandH * 0.08f));
        int padY = Math.max(1, Math.round(bandH * 0.08f));
        Result res = new Result();
        res.xMin = Math.max(0, words.get(first)[0] - padX);
        res.xMax = Math.min(w, words.get(last)[1] + 1 + padX);
        res.yTop = Math.max(0, best[0] - padY);
        res.yBottom = Math.min(h, best[1] + 1 + padY);
        return res;
    }

    /** خط‌های متن را در بازه‌ی [from,to) سطرها پیدا می‌کند و برای شاخک و دُم حرف‌ها به بالا و پایین گسترش می‌دهد. */
    private static List<int[]> detect(int[] rowInk, int from, int to, int core, int gapTol) {
        List<int[]> bands = new ArrayList<>();
        int start = -1, lastText = -1;
        for (int r = from; r < to; r++) {
            if (rowInk[r] >= core) {
                if (start < 0) start = r;
                lastText = r;
            } else if (start >= 0 && r - lastText > gapTol) {
                bands.add(new int[]{start, lastText});
                start = -1;
            }
        }
        if (start >= 0) bands.add(new int[]{start, lastText});
        for (int i = 0; i < bands.size(); i++) {
            int[] b = bands.get(i);
            int bh = b[1] - b[0] + 1;
            int lim = Math.max(2, Math.round(bh * 0.45f));
            int prevEnd = (i > 0) ? bands.get(i - 1)[1] : from - 1;
            int nextStart = (i + 1 < bands.size()) ? bands.get(i + 1)[0] : to;
            int top = b[0];
            for (int k = 0; k < lim && top - 1 > prevEnd && rowInk[top - 1] >= 1; k++) top--;
            int bot = b[1];
            for (int k = 0; k < lim && bot + 1 < nextStart && rowInk[bot + 1] >= 1; k++) bot++;
            b[0] = top;
            b[1] = bot;
        }
        return bands;
    }
}

package com.example.pdfhighlighter;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * پل بین هایلایت‌های برنامه و نویسنده‌ی PDF (PdfInk).
 *  - embed: همه‌ی هایلایت‌ها را به‌صورت حاشیه‌نویسی داخل یک نسخه‌ی تمیز از PDF می‌نویسد
 *  - containsMarker / stripAndImport: هایلایت‌هایی که قبلاً با این برنامه داخل فایل نوشته شده را
 *    دوباره به‌صورت قابل‌ویرایش وارد برنامه می‌کند و نسخه‌ی تمیز صفحه‌ها را می‌سازد
 */
public final class PdfExporter {

    private PdfExporter() {
    }

    public static void embed(Context ctx, File src, File out, List<HighlightStore.H> strokes) throws IOException {
        List<PdfInk.Stroke> list = new ArrayList<>();
        for (HighlightStore.H h : strokes) {
            PdfInk.Stroke s = new PdfInk.Stroke();
            s.page = h.page;
            s.argb = h.color;
            s.width = h.width;
            s.pts = h.pts;
            s.l = h.l;
            s.t = h.t;
            s.r = h.r;
            s.b = h.b;
            list.add(s);
        }
        PdfInk.embed(src, out, list);
    }

    /** آیا این فایل هایلایتِ ساخته‌شده با همین برنامه دارد؟ */
    public static boolean containsMarker(File f) {
        return PdfInk.containsMarker(f);
    }

    /**
     * هایلایت‌های این برنامه را از in برمی‌دارد و در out اضافه می‌کند؛ نسخه‌ی بدون آن‌ها را در stripped می‌نویسد.
     * اگر چیزی برای وارد کردن نبود false برمی‌گرداند.
     */
    public static boolean stripAndImport(Context ctx, File in, File stripped, List<HighlightStore.H> out)
            throws IOException {
        List<PdfInk.Stroke> list = new ArrayList<>();
        if (!PdfInk.strip(in, stripped, list)) return false;
        for (PdfInk.Stroke s : list) {
            HighlightStore.H h = new HighlightStore.H();
            h.page = s.page;
            h.color = s.argb;
            h.width = s.width;
            h.pts = s.pts;
            out.add(h);
        }
        return true;
    }

    /** متن خطا برای نمایش به کاربر (نوع خطا، پیام و چند خط اول محل خطا). */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(4, st.length); i++) sb.append("\n  at ").append(st[i]);
        Throwable c = t.getCause();
        if (c != null && c != t) {
            sb.append("\nسبب: ").append(c.getClass().getName());
            if (c.getMessage() != null) sb.append(": ").append(c.getMessage());
        }
        return sb.toString();
    }
}

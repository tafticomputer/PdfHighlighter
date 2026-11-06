package com.example.pdfhighlighter;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSFloat;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor;
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * هایلایت‌ها و نوشته‌های قلم را به‌صورت حاشیه‌نویسی «Ink» (برداری و قابل‌حذف) داخل خود فایل PDF می‌نویسد.
 * هر حاشیه‌نویسی یک «ظاهر» (Appearance) هم دارد تا در همه‌ی برنامه‌های PDF (Adobe، Chrome، Drive و ...) دیده شود.
 */
public final class PdfExporter {

    private PdfExporter() {
    }

    public static void embed(Context ctx, File src, File out, List<HighlightStore.H> strokes) throws IOException {
        PDFBoxResourceLoader.init(ctx);
        PDDocument doc = PDDocument.load(src);
        try {
            int pages = doc.getNumberOfPages();
            long stamp = System.currentTimeMillis();
            int idx = 0;
            for (HighlightStore.H h : strokes) {
                if (h.page < 0 || h.page >= pages) continue;
                addInk(doc, doc.getPage(h.page), h, "pdfhl-" + stamp + "-" + (idx++));
            }
            doc.save(out);
        } finally {
            doc.close();
        }
    }

    private static void addInk(PDDocument doc, PDPage page, HighlightStore.H h, String name) throws IOException {
        PDRectangle crop = page.getCropBox();
        int rot = ((page.getRotation() % 360) + 360) % 360;
        float x0 = crop.getLowerLeftX();
        float y0 = crop.getLowerLeftY();
        float cw = crop.getWidth();
        float ch = crop.getHeight();
        // اندازه‌ی صفحه همان‌طور که نمایش داده می‌شود (بعد از چرخش)
        float wd = (rot == 90 || rot == 270) ? ch : cw;
        float hd = (rot == 90 || rot == 270) ? cw : ch;

        float[] src;
        float widthFrac;
        int argb;
        if (h.pts == null) {            // مستطیل نسخه‌ی قبلی برنامه → خط ضخیم افقی
            float my = (h.t + h.b) / 2f;
            src = new float[]{h.l, my, h.r, my};
            widthFrac = Math.max(0.005f, (h.b - h.t) * hd / wd);
            argb = (h.color & 0x00FFFFFF) | 0x70000000;
        } else {
            src = h.pts;
            widthFrac = h.width;
            argb = h.color;
        }
        int n = src.length / 2;
        if (n < 2) return;

        float[] px = new float[n];
        float[] py = new float[n];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            float u = src[2 * k] * wd;      // مختصات روی صفحه‌ی نمایش‌داده‌شده (مبدأ بالا-چپ)
            float v = src[2 * k + 1] * hd;
            float x, y;
            switch (rot) {
                case 90:
                    x = x0 + v;
                    y = y0 + u;
                    break;
                case 180:
                    x = x0 + cw - u;
                    y = y0 + v;
                    break;
                case 270:
                    x = x0 + cw - v;
                    y = y0 + ch - u;
                    break;
                default:
                    x = x0 + u;
                    y = y0 + ch - v;
                    break;
            }
            px[k] = x;
            py[k] = y;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }

        float lw = Math.max(0.5f, widthFrac * wd);
        float pad = lw / 2f + 1f;
        PDRectangle rect = new PDRectangle(minX - pad, minY - pad, (maxX - minX) + 2 * pad, (maxY - minY) + 2 * pad);

        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float alpha = ((argb >>> 24) & 0xFF) / 255f;

        // ---- خود حاشیه‌نویسی (Ink)
        COSDictionary d = new COSDictionary();
        d.setItem(COSName.TYPE, COSName.ANNOT);
        d.setName(COSName.SUBTYPE, "Ink");
        PDAnnotation annot = PDAnnotation.createAnnotation(d);
        annot.setRectangle(rect);
        annot.setAnnotationFlags(4); // Print
        annot.setColor(new PDColor(new float[]{r, g, b}, PDDeviceRGB.INSTANCE));
        d.setString(COSName.NM, name);
        d.setFloat(COSName.CA, alpha);

        COSArray stroke = new COSArray();
        for (int k = 0; k < n; k++) {
            stroke.add(new COSFloat(px[k]));
            stroke.add(new COSFloat(py[k]));
        }
        COSArray inkList = new COSArray();
        inkList.add(stroke);
        d.setItem(COSName.getPDFName("InkList"), inkList);

        COSDictionary bs = new COSDictionary();
        bs.setFloat(COSName.W, lw);
        d.setItem(COSName.BS, bs);

        // ---- ظاهر حاشیه‌نویسی تا همه‌ی نمایشگرها آن را رسم کنند
        PDAppearanceStream ap = new PDAppearanceStream(doc);
        ap.setBBox(rect);
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setStrokingAlphaConstant(alpha);
        gs.setNonStrokingAlphaConstant(alpha);
        PDPageContentStream cs = new PDPageContentStream(doc, ap);
        try {
            cs.setGraphicsStateParameters(gs);
            cs.setStrokingColor(r, g, b);
            cs.setLineWidth(lw);
            cs.setLineCapStyle(1);
            cs.setLineJoinStyle(1);
            cs.moveTo(px[0], py[0]);
            for (int k = 1; k < n; k++) cs.lineTo(px[k], py[k]);
            cs.stroke();
        } finally {
            cs.close();
        }
        PDAppearanceDictionary apd = new PDAppearanceDictionary();
        apd.setNormalAppearance(ap);
        annot.setAppearance(apd);

        List<PDAnnotation> list = page.getAnnotations();
        list.add(annot);
        page.setAnnotations(list);
    }
}

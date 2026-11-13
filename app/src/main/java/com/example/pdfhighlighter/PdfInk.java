package com.example.pdfhighlighter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * نوشتن هایلایت و قلم داخل خود فایل PDF به‌صورت حاشیه‌نویسی «Ink» (برداری، نه تصویر).
 *
 * روش: «به‌روزرسانی افزایشی» (Incremental Update) — محتوای اصلی فایل بایت‌به‌بایت دست‌نخورده می‌ماند و
 * فقط در انتهای فایل چند شیء تازه (حاشیه‌نویسی‌ها + نسخه‌ی جدید صفحه‌های تغییرکرده + جدول xref) اضافه می‌شود.
 * این کلاس هیچ وابستگی به اندروید یا کتابخانه‌ی بیرونی ندارد.
 * پشتیبانی: xref کلاسیک، xref stream، object stream، فایل‌های به‌روزرسانی‌شده‌ی چندباره، صفحه‌های چرخیده.
 * پشتیبانی نمی‌شود: فایل‌های رمزگذاری‌شده.
 */
public final class PdfInk {

    public static final class Stroke {
        public int page;
        public int argb;      // رنگ همراه با شفافیت
        public float width;   // کسری از عرض صفحه
        public float[] pts;   // x0,y0,x1,y1,... نسبی (۰ تا ۱) روی صفحه‌ی نمایش‌داده‌شده؛ null یعنی مستطیل قدیمی
        public float l, t, r, b; // فقط برای مستطیل قدیمی
    }


    // ---------------------------------------------------------------- منبع داده (خواندن تصادفی، بدون بارگذاری کل فایل)

    public interface Source {
        long length() throws IOException;

        /** تا len بایت از آفست pos می‌خواند؛ تعداد خوانده‌شده یا -1 در پایان فایل. */
        int read(long pos, byte[] buf, int off, int len) throws IOException;

        void close();
    }

    public static Source fileSource(File f) throws IOException {
        final RandomAccessFile raf = new RandomAccessFile(f, "r");
        return new Source() {
            public long length() throws IOException {
                return raf.length();
            }

            public synchronized int read(long pos, byte[] buf, int off, int len) throws IOException {
                raf.seek(pos);
                return raf.read(buf, off, len);
            }

            public void close() {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        };
    }

    /** کانال را نمی‌بندد (مالک آن بیرون است). */
    public static Source channelSource(final FileChannel ch) {
        return new Source() {
            public long length() throws IOException {
                return ch.size();
            }

            public synchronized int read(long pos, byte[] buf, int off, int len) throws IOException {
                return ch.read(ByteBuffer.wrap(buf, off, len), pos);
            }

            public void close() {
            }
        };
    }

    /** فقط بخشِ افزوده‌شده‌ی انتهای فایل را می‌سازد (اصل فایل را بازنویسی نمی‌کند). */
    public static byte[] buildEmbed(Source s, List<Stroke> strokes) throws IOException {
        return new PdfInk(s).run(strokes);
    }

    /** بخش افزوده‌ی «حذف حاشیه‌نویسی‌های خودمان»؛ اگر چیزی نبود null. هایلایت‌های یافت‌شده در imported ریخته می‌شود. */
    public static byte[] buildStrip(Source s, List<Stroke> imported) throws IOException {
        return new PdfInk(s).runStrip(imported);
    }

    private static void copyPlus(File src, File out, byte[] tail) throws IOException {
        FileOutputStream fos = new FileOutputStream(out);
        try {
            FileInputStream in = new FileInputStream(src);
            try {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                in.close();
            }
            fos.write(tail);
        } finally {
            fos.close();
        }
    }

    public static void embed(File src, File out, List<Stroke> strokes) throws IOException {
        Source s = fileSource(src);
        byte[] tail;
        try {
            tail = buildEmbed(s, strokes);
        } finally {
            s.close();
        }
        copyPlus(src, out, tail);
    }

    /** نشانه‌ی حاشیه‌نویسی‌های ساخته‌شده با این برنامه (برای شناسایی، وارد کردن دوباره و حذف). */
    public static final String MARKER = "(pdfhl1)";

    /** آیا در این فایل حاشیه‌نویسی ساخته‌شده با این برنامه هست؟ (جستجوی سریع، بدون تحلیل فایل) */
    public static boolean containsMarker(File f) {
        Source s = null;
        try {
            s = fileSource(f);
            return containsMarker(s, Long.MAX_VALUE);
        } catch (IOException e) {
            return false;
        } finally {
            if (s != null) s.close();
        }
    }

    /** جستجو فقط در maxTail بایتِ آخر (حاشیه‌نویسی‌های ما همیشه به انتهای فایل افزوده می‌شوند). */
    public static boolean containsMarker(Source s, long maxTail) {
        try {
            byte[] pat = ("/NM " + MARKER).getBytes(ISO);
            long len = s.length();
            long pos = Math.max(0, len - Math.max(0, maxTail));
            byte[] buf = new byte[256 * 1024 + pat.length];
            int keep = 0;
            while (pos < len) {
                int want = (int) Math.min(buf.length - keep, len - pos);
                int n = s.read(pos, buf, keep, want);
                if (n <= 0) return false;
                pos += n;
                int total = keep + n;
                for (int i = 0; i + pat.length <= total; i++) {
                    int k = 0;
                    while (k < pat.length && buf[i + k] == pat[k]) k++;
                    if (k == pat.length) return true;
                }
                keep = Math.min(pat.length - 1, total);
                System.arraycopy(buf, total - keep, buf, 0, keep);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /**
     * حاشیه‌نویسی‌های ساخته‌شده با این برنامه را از صفحه‌ها برمی‌دارد و در imported می‌ریزد (قابل‌ویرایش).
     * خروجی out همان فایل است بدون آن حاشیه‌نویسی‌ها. اگر چیزی پیدا نشد false برمی‌گردد و out ساخته نمی‌شود.
     */
    public static boolean strip(File src, File out, List<Stroke> imported) throws IOException {
        Source s = fileSource(src);
        byte[] tail;
        try {
            tail = buildStrip(s, imported);
        } finally {
            s.close();
        }
        if (tail == null) return false;
        copyPlus(src, out, tail);
        return true;
    }

    // ================================================================== مدل مقدارها

    private static final Charset ISO = Charset.forName("ISO-8859-1");

    private static final class Ref {
        final int num, gen;

        Ref(int n, int g) {
            num = n;
            gen = g;
        }
    }

    private static final class Name {
        final String s;

        Name(String s) {
            this.s = s;
        }
    }

    private static final class Raw {   // عدد، رشته، true/false/null؛ همان‌طور که نوشته شده نگه داشته می‌شود
        final String s;

        Raw(String s) {
            this.s = s;
        }
    }

    private static final class Dict extends LinkedHashMap<String, Object> {
        Dict() {
            super();
        }

        Dict(Map<String, Object> m) {
            super(m);
        }
    }

    private static final class Stream {
        final Dict dict;
        final long start;

        Stream(Dict d, long s) {
            dict = d;
            start = s;
        }
    }

    private static final class XEntry {
        final int type;   // 0 آزاد، 1 آفست، 2 داخل object stream
        final long a;
        final int b;

        XEntry(int t, long a, int b) {
            type = t;
            this.a = a;
            this.b = b;
        }
    }

    private static final class ObjStm {
        byte[] bytes;
        int first;
        int[] nums;
        int[] offs;
    }

    private static final class PageInfo {
        Ref ref;
        Dict dict;
        Dict inh;
    }

    // ================================================================== تحلیل‌گر

    private static final class Parser {
        final byte[] d;
        int p;

        Parser(byte[] d, int p) {
            this.d = d;
            this.p = p;
        }

        static boolean ws(int c) {
            return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32;
        }

        static boolean delim(int c) {
            return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '/' || c == '%';
        }

        void skipWs() {
            while (p < d.length) {
                int c = d[p] & 0xFF;
                if (ws(c)) {
                    p++;
                } else if (c == '%') {
                    while (p < d.length && d[p] != 10 && d[p] != 13) p++;
                } else {
                    break;
                }
            }
        }

        boolean starts(String s) {
            if (p + s.length() > d.length) return false;
            for (int i = 0; i < s.length(); i++) {
                if (d[p + i] != (byte) s.charAt(i)) return false;
            }
            return true;
        }

        String token() {
            skipWs();
            int s = p;
            while (p < d.length) {
                int c = d[p] & 0xFF;
                if (ws(c) || delim(c)) break;
                p++;
            }
            return new String(d, s, p - s, ISO);
        }

        int readInt() throws IOException {
            String t = token();
            try {
                return Integer.parseInt(t);
            } catch (NumberFormatException e) {
                throw new IOException("عدد انتظار می‌رفت: " + t);
            }
        }

        static boolean isUInt(String t) {
            if (t.isEmpty() || t.length() > 10) return false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            return true;
        }

        Object parseValue() throws IOException {
            skipWs();
            if (p >= d.length) throw new IOException("پایان ناگهانی فایل");
            int c = d[p] & 0xFF;
            if (c == '<') {
                if (p + 1 < d.length && d[p + 1] == '<') return parseDict();
                int s = p;
                while (p < d.length && d[p] != '>') p++;
                p++;
                return new Raw(new String(d, s, Math.min(p, d.length) - s, ISO));
            }
            if (c == '(') {
                int s = p;
                int depth = 0;
                while (p < d.length) {
                    int ch = d[p] & 0xFF;
                    if (ch == '\\') {
                        p += 2;
                        continue;
                    }
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                        if (depth == 0) {
                            p++;
                            break;
                        }
                    }
                    p++;
                }
                return new Raw(new String(d, s, Math.min(p, d.length) - s, ISO));
            }
            if (c == '[') {
                p++;
                List<Object> l = new ArrayList<>();
                while (true) {
                    skipWs();
                    if (p >= d.length) throw new IOException("آرایه ناقص");
                    if (d[p] == ']') {
                        p++;
                        break;
                    }
                    l.add(parseValue());
                }
                return l;
            }
            if (c == '/') {
                p++;
                int s = p;
                while (p < d.length) {
                    int ch = d[p] & 0xFF;
                    if (ws(ch) || delim(ch)) break;
                    p++;
                }
                return new Name(new String(d, s, p - s, ISO));
            }
            String t = token();
            if (t.isEmpty()) throw new IOException("نویسه‌ی نامنتظر در " + p);
            if (isUInt(t)) {
                int save = p;
                String t2 = token();
                if (isUInt(t2)) {
                    skipWs();
                    if (p < d.length && d[p] == 'R'
                            && (p + 1 >= d.length || ws(d[p + 1] & 0xFF) || delim(d[p + 1] & 0xFF))) {
                        p++;
                        return new Ref(Integer.parseInt(t), Integer.parseInt(t2));
                    }
                }
                p = save;
            }
            return new Raw(t);
        }

        Dict parseDict() throws IOException {
            p += 2;
            Dict m = new Dict();
            while (true) {
                skipWs();
                if (p >= d.length) throw new IOException("دیکشنری ناقص");
                if (d[p] == '>' && p + 1 < d.length && d[p + 1] == '>') {
                    p += 2;
                    break;
                }
                Object k = parseValue();
                if (!(k instanceof Name)) throw new IOException("نام انتظار می‌رفت");
                Object v = parseValue();
                m.put(((Name) k).s, v);
            }
            return m;
        }
    }

    private static void ser(Object o, StringBuilder sb) {
        if (o instanceof Ref) {
            Ref r = (Ref) o;
            sb.append(r.num).append(' ').append(r.gen).append(" R");
        } else if (o instanceof Name) {
            sb.append('/').append(((Name) o).s);
        } else if (o instanceof Raw) {
            sb.append(((Raw) o).s);
        } else if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object e : (List<?>) o) {
                if (!first) sb.append(' ');
                first = false;
                ser(e, sb);
            }
            sb.append(']');
        } else if (o instanceof Dict) {
            sb.append("<<");
            for (Map.Entry<String, Object> e : ((Dict) o).entrySet()) {
                sb.append('/').append(e.getKey()).append(' ');
                ser(e.getValue(), sb);
                sb.append('\n');
            }
            sb.append(">>");
        } else {
            sb.append("null");
        }
    }

    // ================================================================== وضعیت سند

    private final Source src;
    private final long len;
    private final Map<Integer, XEntry> xref = new HashMap<>();
    private final Map<Integer, Object> cache = new HashMap<>();
    private final Map<Integer, ObjStm> objStms = new LinkedHashMap<Integer, ObjStm>(8, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Integer, ObjStm> e) {
            return size() > 6;
        }
    };
    private final List<PageInfo> pages = new ArrayList<>();
    private final Map<Integer, PageInfo> pageCache = new HashMap<>();
    private Ref pagesRoot;
    private int total = -1;
    private boolean fullPages;
    private Dict trailer;
    private boolean xrefIsStream;
    private long startXref;

    private static final int MAX_WIN = 32 * 1024 * 1024;
    private static final int MAX_DECODE = 64 * 1024 * 1024;

    private static final class Fatal extends IOException {
        Fatal(String m) {
            super(m);
        }
    }

    private interface Fn<T> {
        T run(byte[] w, boolean truncated) throws IOException;
    }

    private PdfInk(Source src) throws IOException {
        this.src = src;
        this.len = src.length();
    }

    /** تا n بایت از pos (کمتر در پایان فایل). */
    private byte[] readAt(long pos, int n) throws IOException {
        if (pos < 0) pos = 0;
        n = (int) Math.max(0, Math.min((long) n, len - pos));
        byte[] b = new byte[n];
        int got = fill(pos, b, n);
        return got == n ? b : Arrays.copyOf(b, got);
    }

    private int fill(long pos, byte[] b, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = src.read(pos + got, b, got, n - got);
            if (r <= 0) break;
            got += r;
        }
        return got;
    }

    /** fn را روی پنجره‌ای از فایل اجرا می‌کند؛ اگر پنجره کوتاه بود، بزرگ‌تر (×۴) تکرار می‌کند. */
    private <T> T atWindow(long off, Fn<T> fn) throws IOException {
        int size = 64 * 1024;
        while (true) {
            byte[] w = readAt(off, size);
            boolean trunc = off + w.length < len;
            try {
                return fn.run(w, trunc);
            } catch (Fatal e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                if (!trunc || size >= MAX_WIN) {
                    if (e instanceof IOException) throw (IOException) e;
                    throw new IOException(e.toString());
                }
                size = Math.min(MAX_WIN, size * 4);
            }
        }
    }

    private boolean needNl() throws IOException {
        if (len <= 0) return false;
        int c = readAt(len - 1, 1)[0];
        return c != 10 && c != 13;
    }

    private static int lastIdx(byte[] b, String s) {
        byte[] pat = s.getBytes(ISO);
        outer:
        for (int i = b.length - pat.length; i >= 0; i--) {
            for (int k = 0; k < pat.length; k++) {
                if (b[i + k] != pat[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** اولین رخداد pat از آفست from (جستجوی تکه‌تکه)؛ -1 اگر نبود. */
    private long scanFor(String pat, long from) throws IOException {
        byte[] p = pat.getBytes(ISO);
        byte[] buf = new byte[1024 * 1024 + p.length];
        long pos = Math.max(0, from);
        while (pos < len) {
            int n = (int) Math.min(buf.length, len - pos);
            int got = fill(pos, buf, n);
            if (got < p.length) return -1;
            outer:
            for (int i = 0; i + p.length <= got; i++) {
                for (int k = 0; k < p.length; k++) {
                    if (buf[i + k] != p[k]) continue outer;
                }
                return pos + i;
            }
            if (pos + got >= len) return -1;
            pos += got - (p.length - 1);
        }
        return -1;
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Raw) {
            try {
                return (int) Math.round(Double.parseDouble(((Raw) o).s));
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static long longOf(Object o, long def) {
        if (o instanceof Raw) {
            try {
                return (long) Double.parseDouble(((Raw) o).s);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private Object resolve(Object o) throws IOException {
        int guard = 0;
        while (o instanceof Ref && guard++ < 32) o = getObj(((Ref) o).num);
        return o;
    }

    // ================================================================== xref

    private static final class XT {
        Dict tr;
        final List<long[]> ents = new ArrayList<>();
    }

    private void readXref() throws IOException {
        int tailN = (int) Math.min(len, 256 * 1024);
        byte[] tail = readAt(len - tailN, tailN);
        int idx = lastIdx(tail, "startxref");
        if (idx < 0) throw new IOException("startxref پیدا نشد");
        Parser sp = new Parser(tail, idx + 9);
        startXref = Long.parseLong(sp.token());

        long off = startXref;
        Set<Long> seen = new HashSet<>();
        boolean first = true;
        while (off >= 0 && seen.add(off)) {
            if (off >= len) throw new IOException("آفست xref نامعتبر");
            byte[] pk = readAt(off, 32);
            Parser pp = new Parser(pk, 0);
            pp.skipWs();
            Dict tr;
            if (pp.starts("xref")) {
                XT xt = atWindow(off, (w, trunc) -> {
                    XT r = new XT();
                    Parser q = new Parser(w, 0);
                    q.skipWs();
                    q.p += 4;
                    while (true) {
                        q.skipWs();
                        if (q.starts("trailer")) {
                            q.p += 7;
                            break;
                        }
                        int start = q.readInt();
                        int cnt = q.readInt();
                        for (int i = 0; i < cnt; i++) {
                            long o = Long.parseLong(q.token());
                            int g = Integer.parseInt(q.token());
                            String t = q.token();
                            boolean used = t.equals("n");
                            r.ents.add(new long[]{start + i, used ? 1 : 0, used ? o : 0, g});
                        }
                    }
                    Object t = q.parseValue();
                    if (!(t instanceof Dict)) throw new Fatal("trailer نامعتبر");
                    if (trunc && q.p > w.length - 32) throw new IOException("پنجره کوتاه است");
                    r.tr = (Dict) t;
                    return r;
                });
                for (long[] e : xt.ents) {
                    int num = (int) e[0];
                    if (!xref.containsKey(num)) xref.put(num, new XEntry((int) e[1], e[2], (int) e[3]));
                }
                tr = xt.tr;
                if (first) xrefIsStream = false;
                Object xs = tr.get("XRefStm");
                if (xs instanceof Raw) readXrefStreamAt(longOf(xs, -1));
            } else {
                tr = readXrefStreamAt(off);
                if (first) xrefIsStream = true;
            }
            if (first) {
                trailer = tr;
                first = false;
            }
            off = longOf(tr.get("Prev"), -1);
        }
        if (trailer == null) throw new IOException("trailer پیدا نشد");
    }

    private Dict readXrefStreamAt(long off) throws IOException {
        Object o = readObjectAt(off, -1);
        if (!(o instanceof Stream)) throw new IOException("xref stream نامعتبر");
        Stream s = (Stream) o;
        Dict dd = s.dict;
        byte[] b = decode(s);
        Object wo = dd.get("W");
        if (!(wo instanceof List) || ((List<?>) wo).size() < 3) throw new IOException("W نامعتبر");
        List<?> wl = (List<?>) wo;
        int w0 = intOf(wl.get(0), 0), w1 = intOf(wl.get(1), 0), w2 = intOf(wl.get(2), 0);
        List<Object> index = new ArrayList<>();
        Object io = dd.get("Index");
        if (io instanceof List) {
            index.addAll((List<?>) io);
        } else {
            index.add(new Raw("0"));
            index.add(dd.get("Size"));
        }
        int pos = 0;
        int rowLen = w0 + w1 + w2;
        for (int k = 0; k + 1 < index.size(); k += 2) {
            int start = intOf(index.get(k), 0);
            int cnt = intOf(index.get(k + 1), 0);
            for (int i = 0; i < cnt; i++) {
                if (pos + rowLen > b.length) break;
                long t = (w0 == 0) ? 1 : be(b, pos, w0);
                long f2 = be(b, pos + w0, w1);
                long f3 = be(b, pos + w0 + w1, w2);
                pos += rowLen;
                int num = start + i;
                if (!xref.containsKey(num)) {
                    xref.put(num, new XEntry((int) t, f2, (int) f3));
                }
            }
        }
        return dd;
    }

    private static long be(byte[] b, int off, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    // ================================================================== خواندن شیءها

    private Object readObjectAt(final long off, final int expect) throws IOException {
        if (off < 0 || off >= len) throw new Fatal("آفست شیء نامعتبر");
        return atWindow(off, (w, trunc) -> {
            Parser ps = new Parser(w, 0);
            int num = ps.readInt();
            ps.readInt();
            String kw = ps.token();
            if (!kw.equals("obj")) throw new Fatal("obj پیدا نشد در " + off);
            if (expect >= 0 && num != expect) throw new Fatal("شماره‌ی شیء نمی‌خواند");
            Object v = ps.parseValue();
            if (v instanceof Dict) {
                ps.skipWs();
                if (ps.starts("stream")) {
                    int s = ps.p + 6;
                    if (s < w.length && w[s] == 13) s++;
                    if (s < w.length && w[s] == 10) s++;
                    if (trunc && s > w.length - 8) throw new IOException("پنجره کوتاه است");
                    return new Stream((Dict) v, off + s);
                }
            }
            if (trunc && ps.p > w.length - 32) throw new IOException("پنجره کوتاه است");
            return v;
        });
    }

    private Object getObj(int num) throws IOException {
        if (cache.containsKey(num)) return cache.get(num);
        XEntry e = xref.get(num);
        Object v = null;
        if (e != null && e.type == 1) {
            v = readObjectAt(e.a, num);
        } else if (e != null && e.type == 2) {
            v = fromObjStm((int) e.a, num);
        }
        cache.put(num, v);
        return v;
    }

    private Object fromObjStm(int stmNum, int num) throws IOException {
        ObjStm os = objStms.get(stmNum);
        if (os == null) {
            Object so = getObj(stmNum);
            if (!(so instanceof Stream)) throw new IOException("object stream پیدا نشد");
            Stream s = (Stream) so;
            os = new ObjStm();
            os.bytes = decode(s);
            int n = intOf(resolve(s.dict.get("N")), 0);
            os.first = intOf(resolve(s.dict.get("First")), 0);
            os.nums = new int[n];
            os.offs = new int[n];
            Parser ps = new Parser(os.bytes, 0);
            for (int i = 0; i < n; i++) {
                os.nums[i] = ps.readInt();
                os.offs[i] = ps.readInt();
            }
            objStms.put(stmNum, os);
        }
        for (int i = 0; i < os.nums.length; i++) {
            if (os.nums[i] == num) {
                return new Parser(os.bytes, os.first + os.offs[i]).parseValue();
            }
        }
        return null;
    }

    private int streamLength(Stream s) throws IOException {
        long l0 = -1;
        Object l = s.dict.get("Length");
        if (l instanceof Raw) {
            l0 = longOf(l, -1);
        } else if (l instanceof Ref) {
            try {
                l0 = longOf(getObj(((Ref) l).num), -1);
            } catch (IOException ignored) {
                l0 = -1;
            }
        }
        if (l0 >= 0 && s.start + l0 <= len) {
            Parser ps = new Parser(readAt(s.start + l0, 32), 0);
            ps.skipWs();
            if (ps.starts("endstream")) {
                if (l0 > Integer.MAX_VALUE) throw new IOException("جریان خیلی بزرگ است");
                return (int) l0;
            }
        }
        long e = scanFor("endstream", s.start);
        if (e < 0) throw new IOException("endstream پیدا نشد");
        long end = e;
        if (end > s.start && readAt(end - 1, 1)[0] == 10) end--;
        if (end > s.start && readAt(end - 1, 1)[0] == 13) end--;
        if (end - s.start > Integer.MAX_VALUE) throw new IOException("جریان خیلی بزرگ است");
        return (int) (end - s.start);
    }

    private byte[] decode(Stream s) throws IOException {
        int n = streamLength(s);
        if (n > MAX_DECODE) throw new IOException("جریان خیلی بزرگ است");
        Object f = resolve(s.dict.get("Filter"));
        if (f instanceof List) {
            List<?> l = (List<?>) f;
            if (l.isEmpty()) f = null;
            else if (l.size() == 1) f = l.get(0);
            else throw new IOException("فیلتر چندگانه پشتیبانی نمی‌شود");
        }
        byte[] rawIn = readAt(s.start, n);
        if (rawIn.length != n) throw new IOException("خواندن ناقص جریان");
        if (f == null) return rawIn;
        if (!(f instanceof Name) || !(((Name) f).s.equals("FlateDecode") || ((Name) f).s.equals("Fl"))) {
            throw new IOException("فیلتر پشتیبانی‌نشده");
        }
        byte[] raw = inflate(rawIn, 0, n);
        Object parms = resolve(s.dict.containsKey("DecodeParms") ? s.dict.get("DecodeParms") : s.dict.get("DP"));
        if (parms instanceof List && ((List<?>) parms).size() == 1) parms = resolve(((List<?>) parms).get(0));
        if (parms instanceof Dict) {
            Dict pd = (Dict) parms;
            int pred = intOf(resolve(pd.get("Predictor")), 1);
            if (pred >= 10) {
                int colors = intOf(resolve(pd.get("Colors")), 1);
                int bpc = intOf(resolve(pd.get("BitsPerComponent")), 8);
                int cols = intOf(resolve(pd.get("Columns")), 1);
                raw = pngUnpredict(raw, colors, bpc, cols);
            } else if (pred == 2) {
                throw new IOException("Predictor 2 پشتیبانی نمی‌شود");
            }
        }
        return raw;
    }

    private static byte[] inflate(byte[] buf, int off, int len) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(buf, off, len);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, len * 3));
        byte[] tmp = new byte[8192];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(tmp);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                bo.write(tmp, 0, n);
            }
        } catch (DataFormatException e) {
            if (bo.size() == 0) throw new IOException("داده‌ی فشرده خراب است");
        } finally {
            inf.end();
        }
        return bo.toByteArray();
    }

    private static byte[] pngUnpredict(byte[] raw, int colors, int bpc, int columns) {
        int rowLen = (colors * bpc * columns + 7) / 8;
        int bpp = Math.max(1, (colors * bpc + 7) / 8);
        int rows = raw.length / (rowLen + 1);
        byte[] out = new byte[rows * rowLen];
        for (int r = 0; r < rows; r++) {
            int ft = raw[r * (rowLen + 1)] & 0xFF;
            int so = r * (rowLen + 1) + 1;
            int ro = r * rowLen;
            for (int i = 0; i < rowLen; i++) {
                int x = raw[so + i] & 0xFF;
                int a = i >= bpp ? out[ro + i - bpp] & 0xFF : 0;
                int b = r > 0 ? out[ro - rowLen + i] & 0xFF : 0;
                int c = (r > 0 && i >= bpp) ? out[ro - rowLen + i - bpp] & 0xFF : 0;
                int v;
                switch (ft) {
                    case 1:
                        v = x + a;
                        break;
                    case 2:
                        v = x + b;
                        break;
                    case 3:
                        v = x + ((a + b) >> 1);
                        break;
                    case 4: {
                        int pp = a + b - c;
                        int pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                        v = x + ((pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c));
                        break;
                    }
                    default:
                        v = x;
                }
                out[ro + i] = (byte) v;
            }
        }
        return out;
    }

    // ================================================================== صفحه‌ها

    private void buildPages() throws IOException {
        Object root = resolve(trailer.get("Root"));
        if (!(root instanceof Dict)) throw new IOException("Root نامعتبر");
        Object pr = ((Dict) root).get("Pages");
        if (!(pr instanceof Ref)) throw new IOException("Pages نامعتبر");
        walk((Ref) pr, new Dict(), new HashSet<Integer>(), 0);
    }

    private void walk(Ref r, Dict inh, Set<Integer> seen, int depth) throws IOException {
        if (depth > 64 || !seen.add(r.num)) return;
        Object o = getObj(r.num);
        if (!(o instanceof Dict)) return;
        Dict d = (Dict) o;
        Dict ni = new Dict(inh);
        String[] keys = {"Rotate", "CropBox", "MediaBox"};
        for (String k : keys) {
            if (d.containsKey(k)) ni.put(k, d.get(k));
        }
        Object kids = resolve(d.get("Kids"));
        if (kids instanceof List) {
            for (Object k : (List<?>) kids) {
                if (k instanceof Ref) walk((Ref) k, ni, seen, depth + 1);
            }
        } else {
            PageInfo pi = new PageInfo();
            pi.ref = r;
            pi.dict = d;
            pi.inh = ni;
            pages.add(pi);
        }
    }

    private void preparePages() throws IOException {
        Object root = resolve(trailer.get("Root"));
        if (!(root instanceof Dict)) throw new IOException("Root نامعتبر");
        Object pr = ((Dict) root).get("Pages");
        if (!(pr instanceof Ref)) throw new IOException("Pages نامعتبر");
        pagesRoot = (Ref) pr;
        Object pd = getObj(pagesRoot.num);
        total = (pd instanceof Dict) ? intOf(resolve(((Dict) pd).get("Count")), -1) : -1;
        if (total <= 0) ensureFull();
    }

    private void ensureFull() throws IOException {
        if (fullPages) return;
        pages.clear();
        buildPages();
        fullPages = true;
        total = pages.size();
    }

    /** صفحه‌ی idx را بدون پیمایش همه‌ی صفحه‌ها پیدا می‌کند (با /Count). */
    private PageInfo pageInfo(int idx) throws IOException {
        if (idx < 0 || (total >= 0 && idx >= total)) return null;
        if (fullPages) return idx < pages.size() ? pages.get(idx) : null;
        PageInfo pi = pageCache.get(idx);
        if (pi != null) return pi;
        try {
            pi = findPage(pagesRoot, new Dict(), idx, 0);
        } catch (IOException e) {
            pi = null;
        }
        if (pi == null) {
            ensureFull();
            return idx < pages.size() ? pages.get(idx) : null;
        }
        pageCache.put(idx, pi);
        return pi;
    }

    private PageInfo findPage(Ref r, Dict inh, int idx, int depth) throws IOException {
        if (depth > 64) return null;
        Object o = getObj(r.num);
        if (!(o instanceof Dict)) return null;
        Dict d = (Dict) o;
        Dict ni = new Dict(inh);
        String[] keys = {"Rotate", "CropBox", "MediaBox"};
        for (String k : keys) {
            if (d.containsKey(k)) ni.put(k, d.get(k));
        }
        Object kids = resolve(d.get("Kids"));
        if (!(kids instanceof List)) {
            if (idx != 0) return null;
            PageInfo pi = new PageInfo();
            pi.ref = r;
            pi.dict = d;
            pi.inh = ni;
            return pi;
        }
        List<?> kl = (List<?>) kids;
        int cnt = intOf(resolve(d.get("Count")), -1);
        if (cnt == kl.size()) {            // درخت تخت: همه‌ی بچه‌ها برگ‌اند
            if (idx >= kl.size()) return null;
            Object k = kl.get(idx);
            return (k instanceof Ref) ? findPage((Ref) k, ni, 0, depth + 1) : null;
        }
        for (Object k : kl) {
            if (!(k instanceof Ref)) continue;
            Object ko = getObj(((Ref) k).num);
            if (!(ko instanceof Dict)) continue;
            int c = 1;
            if (((Dict) ko).containsKey("Kids")) {
                c = intOf(resolve(((Dict) ko).get("Count")), -1);
                if (c < 0) throw new IOException("Count ندارد");
            }
            if (idx < c) return findPage((Ref) k, ni, idx, depth + 1);
            idx -= c;
        }
        return null;
    }

    private double[] box(PageInfo pi) throws IOException {
        Object b = resolve(pi.inh.containsKey("CropBox") ? pi.inh.get("CropBox") : pi.inh.get("MediaBox"));
        if (b instanceof List && ((List<?>) b).size() >= 4) {
            double[] v = new double[4];
            for (int i = 0; i < 4; i++) {
                Object e = resolve(((List<?>) b).get(i));
                if (!(e instanceof Raw)) return new double[]{0, 0, 612, 792};
                try {
                    v[i] = Double.parseDouble(((Raw) e).s);
                } catch (NumberFormatException ex) {
                    return new double[]{0, 0, 612, 792};
                }
            }
            return new double[]{Math.min(v[0], v[2]), Math.min(v[1], v[3]), Math.max(v[0], v[2]), Math.max(v[1], v[3])};
        }
        return new double[]{0, 0, 612, 792};
    }

    // ================================================================== نوشتن

    private static String f(double v) {
        String s = String.format(Locale.US, "%.3f", v);
        if (s.indexOf('.') >= 0) {
            int e = s.length();
            while (e > 0 && s.charAt(e - 1) == '0') e--;
            if (e > 0 && s.charAt(e - 1) == '.') e--;
            s = s.substring(0, e);
        }
        if (s.equals("-0") || s.isEmpty()) s = "0";
        return s;
    }

    private static final class Out {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final long base;
        final TreeMap<Integer, long[]> offsets = new TreeMap<>();

        Out(long base, boolean nl) {
            this.base = base;
            if (nl) bo.write(10);
        }

        long pos() {
            return base + bo.size();
        }

        void str(String s) {
            byte[] b = s.getBytes(ISO);
            bo.write(b, 0, b.length);
        }

        void bytes(byte[] b) {
            bo.write(b, 0, b.length);
        }

        void begin(int num, int gen) {
            offsets.put(num, new long[]{pos(), gen});
            str(num + " " + gen + " obj\n");
        }

        void end() {
            str("\nendobj\n");
        }
    }

    private byte[] run(List<Stroke> strokes) throws IOException {
        readXref();
        if (trailer.containsKey("Encrypt")) throw new IOException("فایل رمزگذاری شده است");
        preparePages();
        if (total <= 0) throw new IOException("صفحه‌ای پیدا نشد");

        int size = intOf(trailer.get("Size"), 0);
        for (int k : xref.keySet()) size = Math.max(size, k + 1);
        int next = size;

        Out out = new Out(len, needNl());

        TreeMap<Integer, List<Stroke>> byPage = new TreeMap<>();
        for (Stroke s : strokes) {
            if (s.page < 0 || s.page >= total) continue;
            List<Stroke> l = byPage.get(s.page);
            if (l == null) {
                l = new ArrayList<>();
                byPage.put(s.page, l);
            }
            l.add(s);
        }
        if (byPage.isEmpty()) throw new IOException("چیزی برای نوشتن نیست");

        for (Map.Entry<Integer, List<Stroke>> pe : byPage.entrySet()) {
            PageInfo pi = pageInfo(pe.getKey());
            if (pi == null) continue;
            double[] bx = box(pi);
            int rot = intOf(resolve(pi.inh.get("Rotate")), 0);
            rot = ((rot % 360) + 360) % 360;
            double cw = bx[2] - bx[0], ch = bx[3] - bx[1];
            double wd = (rot == 90 || rot == 270) ? ch : cw;
            double hd = (rot == 90 || rot == 270) ? cw : ch;

            List<Object> newRefs = new ArrayList<>();
            for (Stroke st : pe.getValue()) {
                float[] src2;
                double widthFrac;
                int argb;
                if (st.pts == null) {
                    float my = (st.t + st.b) / 2f;
                    src2 = new float[]{st.l, my, st.r, my};
                    widthFrac = Math.max(0.005, (st.b - st.t) * hd / wd);
                    argb = (st.argb & 0x00FFFFFF) | 0x70000000;
                } else {
                    src2 = st.pts;
                    widthFrac = st.width;
                    argb = st.argb;
                }
                int n = src2.length / 2;
                if (n < 2) continue;
                double[] px = new double[n], py = new double[n];
                double minX = 1e18, minY = 1e18, maxX = -1e18, maxY = -1e18;
                for (int k = 0; k < n; k++) {
                    double u = src2[2 * k] * wd;
                    double v = src2[2 * k + 1] * hd;
                    double x, y;
                    switch (rot) {
                        case 90:
                            x = bx[0] + v;
                            y = bx[1] + u;
                            break;
                        case 180:
                            x = bx[0] + cw - u;
                            y = bx[1] + v;
                            break;
                        case 270:
                            x = bx[0] + cw - v;
                            y = bx[1] + ch - u;
                            break;
                        default:
                            x = bx[0] + u;
                            y = bx[1] + ch - v;
                            break;
                    }
                    px[k] = x;
                    py[k] = y;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
                double lw = Math.max(0.5, widthFrac * wd);
                double pad = lw / 2 + 1;
                String rect = f(minX - pad) + " " + f(minY - pad) + " " + f(maxX + pad) + " " + f(maxY + pad);
                String col = f(((argb >> 16) & 0xFF) / 255.0) + " " + f(((argb >> 8) & 0xFF) / 255.0)
                        + " " + f((argb & 0xFF) / 255.0);
                String alpha = f(((argb >>> 24) & 0xFF) / 255.0);

                // ---- ظاهر (Appearance stream)
                StringBuilder cs = new StringBuilder();
                cs.append("q\n/GS0 gs\n").append(col).append(" RG\n").append(f(lw)).append(" w\n1 J\n1 j\n");
                cs.append(f(px[0])).append(' ').append(f(py[0])).append(" m\n");
                for (int k = 1; k < n; k++) {
                    cs.append(f(px[k])).append(' ').append(f(py[k])).append(" l\n");
                }
                cs.append("S\nQ\n");
                byte[] csb = cs.toString().getBytes(ISO);

                int apNum = next++;
                int anNum = next++;
                out.begin(apNum, 0);
                out.str("<< /Type /XObject /Subtype /Form /BBox [" + rect + "] /Resources << /ExtGState << /GS0 << /Type /ExtGState /CA "
                        + alpha + " /ca " + alpha + " >> >> >> /Length " + csb.length + " >>\nstream\n");
                out.bytes(csb);
                out.str("\nendstream");
                out.end();

                // ---- خود حاشیه‌نویسی
                StringBuilder an = new StringBuilder();
                an.append("<< /Type /Annot /Subtype /Ink /NM "+MARKER+" /Rect [").append(rect).append("] /C [").append(col)
                        .append("] /CA ").append(alpha).append(" /F 4 /BS << /W ").append(f(lw))
                        .append(" >> /P ").append(pi.ref.num).append(' ').append(pi.ref.gen).append(" R")
                        .append(" /AP << /N ").append(apNum).append(" 0 R >> /InkList [[");
                for (int k = 0; k < n; k++) {
                    if (k > 0) an.append(' ');
                    an.append(f(px[k])).append(' ').append(f(py[k]));
                }
                an.append("]] >>");
                out.begin(anNum, 0);
                out.str(an.toString());
                out.end();
                newRefs.add(new Ref(anNum, 0));
            }
            if (newRefs.isEmpty()) continue;

            // ---- نسخه‌ی جدید صفحه با /Annots به‌روز
            Dict nd = new Dict(pi.dict);
            List<Object> annots = new ArrayList<>();
            Object ex = resolve(pi.dict.get("Annots"));
            if (ex instanceof List) annots.addAll((List<?>) ex);
            annots.addAll(newRefs);
            nd.put("Annots", annots);
            StringBuilder sb = new StringBuilder();
            ser(nd, sb);
            out.begin(pi.ref.num, pi.ref.gen);
            out.str(sb.toString());
            out.end();
        }
        if (out.offsets.isEmpty()) throw new IOException("چیزی برای نوشتن نیست");

        return finish(out, next);
    }

    private byte[] finish(Out out, int next) throws IOException {
        StringBuilder tail = new StringBuilder();
        if (trailer.get("Root") != null) tail.append("/Root ").append(sers(trailer.get("Root")));
        if (trailer.get("Info") != null) tail.append(" /Info ").append(sers(trailer.get("Info")));
        if (trailer.get("ID") != null) tail.append(" /ID ").append(sers(trailer.get("ID")));

        long xrefOff = out.pos();
        if (!xrefIsStream) {
            StringBuilder xb = new StringBuilder("xref\n");
            Integer[] nums = out.offsets.keySet().toArray(new Integer[0]);
            int i = 0;
            while (i < nums.length) {
                int j = i;
                while (j + 1 < nums.length && nums[j + 1] == nums[j] + 1) j++;
                xb.append(nums[i]).append(' ').append(j - i + 1).append('\n');
                for (int k = i; k <= j; k++) {
                    long[] e = out.offsets.get(nums[k]);
                    xb.append(String.format(Locale.US, "%010d %05d n \n", e[0], (int) e[1]));
                }
                i = j + 1;
            }
            xb.append("trailer\n<< /Size ").append(next).append(' ').append(tail)
                    .append(" /Prev ").append(startXref).append(" >>\nstartxref\n").append(xrefOff).append("\n%%EOF\n");
            out.str(xb.toString());
        } else {
            int xnum = next++;
            out.offsets.put(xnum, new long[]{xrefOff, 0});
            Integer[] nums = out.offsets.keySet().toArray(new Integer[0]);
            StringBuilder idx = new StringBuilder();
            ByteArrayOutputStream rows = new ByteArrayOutputStream();
            int i = 0;
            while (i < nums.length) {
                int j = i;
                while (j + 1 < nums.length && nums[j + 1] == nums[j] + 1) j++;
                idx.append(nums[i]).append(' ').append(j - i + 1).append(' ');
                for (int k = i; k <= j; k++) {
                    long[] e = out.offsets.get(nums[k]);
                    rows.write(1);
                    rows.write((int) (e[0] >> 24) & 0xFF);
                    rows.write((int) (e[0] >> 16) & 0xFF);
                    rows.write((int) (e[0] >> 8) & 0xFF);
                    rows.write((int) e[0] & 0xFF);
                    rows.write((int) (e[1] >> 8) & 0xFF);
                    rows.write((int) e[1] & 0xFF);
                }
                i = j + 1;
            }
            byte[] rb = rows.toByteArray();
            out.str(xnum + " 0 obj\n<< /Type /XRef /Size " + next + " /W [1 4 2] /Index [" + idx.toString().trim() + "] "
                    + tail + " /Prev " + startXref + " /Length " + rb.length + " >>\nstream\n");
            out.bytes(rb);
            out.str("\nendstream\nendobj\nstartxref\n" + xrefOff + "\n%%EOF\n");
        }

        return out.bo.toByteArray();
    }

    private double num(Object o) throws IOException {
        Object r = resolve(o);
        if (r instanceof Raw) {
            try {
                return Double.parseDouble(((Raw) r).s);
            } catch (NumberFormatException ignored) {
            }
        }
        return Double.NaN;
    }

    private boolean isMine(Dict d) {
        Object nm = d.get("NM");
        return nm instanceof Raw && ((Raw) nm).s.equals(MARKER);
    }

    /** حاشیه‌نویسی خودمان را به هایلایت نسبی (روی صفحه‌ی نمایش‌داده‌شده) برمی‌گرداند. */
    private Stroke toStroke(int pageIndex, PageInfo pi, Dict a) throws IOException {
        Object il = resolve(a.get("InkList"));
        if (!(il instanceof List) || ((List<?>) il).isEmpty()) return null;
        Object first = resolve(((List<?>) il).get(0));
        if (!(first instanceof List)) return null;
        List<?> pl = (List<?>) first;
        int n = pl.size() / 2;
        if (n < 2) return null;

        double[] bx = box(pi);
        int rot = ((intOf(resolve(pi.inh.get("Rotate")), 0) % 360) + 360) % 360;
        double cw = bx[2] - bx[0], ch = bx[3] - bx[1];
        double wd = (rot == 90 || rot == 270) ? ch : cw;
        double hd = (rot == 90 || rot == 270) ? cw : ch;

        float[] pts = new float[n * 2];
        for (int k = 0; k < n; k++) {
            double x = num(pl.get(2 * k)), y = num(pl.get(2 * k + 1));
            if (Double.isNaN(x) || Double.isNaN(y)) return null;
            double u, v;
            switch (rot) {
                case 90:
                    v = x - bx[0];
                    u = y - bx[1];
                    break;
                case 180:
                    u = bx[0] + cw - x;
                    v = y - bx[1];
                    break;
                case 270:
                    v = bx[0] + cw - x;
                    u = bx[1] + ch - y;
                    break;
                default:
                    u = x - bx[0];
                    v = bx[1] + ch - y;
                    break;
            }
            pts[2 * k] = (float) Math.max(0, Math.min(1, u / wd));
            pts[2 * k + 1] = (float) Math.max(0, Math.min(1, v / hd));
        }
        int r = 255, g = 235, b = 59;
        Object c = resolve(a.get("C"));
        if (c instanceof List && ((List<?>) c).size() >= 3) {
            double cr = num(((List<?>) c).get(0)), cg = num(((List<?>) c).get(1)), cb = num(((List<?>) c).get(2));
            if (!Double.isNaN(cr) && !Double.isNaN(cg) && !Double.isNaN(cb)) {
                r = (int) Math.round(cr * 255);
                g = (int) Math.round(cg * 255);
                b = (int) Math.round(cb * 255);
            }
        }
        double alpha = num(a.get("CA"));
        if (Double.isNaN(alpha)) alpha = 1.0;
        double lw = 1.0;
        Object bs = resolve(a.get("BS"));
        if (bs instanceof Dict) {
            double w = num(((Dict) bs).get("W"));
            if (!Double.isNaN(w)) lw = w;
        }
        Stroke s = new Stroke();
        s.page = pageIndex;
        s.pts = pts;
        s.width = (float) (lw / wd);
        s.argb = ((int) Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24)
                | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
        return s;
    }

    private byte[] runStrip(List<Stroke> imported) throws IOException {
        readXref();
        if (trailer.containsKey("Encrypt")) throw new IOException("فایل رمزگذاری شده است");
        buildPages();
        int size = intOf(trailer.get("Size"), 0);
        for (int k : xref.keySet()) size = Math.max(size, k + 1);
        Out out = new Out(len, needNl());
        boolean any = false;
        for (int i = 0; i < pages.size(); i++) {
            PageInfo pi = pages.get(i);
            Object ex = resolve(pi.dict.get("Annots"));
            if (!(ex instanceof List)) continue;
            List<Object> keep = new ArrayList<>();
            boolean removed = false;
            for (Object e : (List<?>) ex) {
                Object a = resolve(e);
                if (a instanceof Dict && isMine((Dict) a)) {
                    Stroke s = toStroke(i, pi, (Dict) a);
                    if (s != null) imported.add(s);
                    removed = true;
                } else {
                    keep.add(e);
                }
            }
            if (!removed) continue;
            any = true;
            Dict nd = new Dict(pi.dict);
            nd.put("Annots", keep);
            StringBuilder sb = new StringBuilder();
            ser(nd, sb);
            out.begin(pi.ref.num, pi.ref.gen);
            out.str(sb.toString());
            out.end();
        }
        if (!any) return null;
        return finish(out, size);
    }

    private static String sers(Object o) {
        StringBuilder sb = new StringBuilder();
        ser(o, sb);
        return sb.toString();
    }
}

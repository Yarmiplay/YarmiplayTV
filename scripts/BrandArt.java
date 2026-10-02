import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * Draws the YarmiplayTV artwork: the Android TV launcher banner and the Google Play store graphics.
 * The logo is the two play triangles of shared/src/androidMain/res/drawable/ic_logo.xml on the
 * banner gradient (colors.xml). Also turns reference screenshots into Play screenshots: Play only takes
 * 16:9 or 9:16, so phone (20:9) and tablet (16:10) ones are fitted onto a 16:9 / 9:16 canvas. JDK only:
 *
 *   java scripts/BrandArt.java [repo root]   # writes app/src/main/res/drawable-xhdpi/banner.png and docs/play/
 */
public class BrandArt {
    static final Color GRADIENT_START = new Color(0x1B2A41);
    static final Color GRADIENT_END = new Color(0x0E1116);
    static final Color ACCENT = new Color(0x3DA5F4);
    static final Color TEXT = new Color(0xE8EAED);
    static final Color TEXT_DIM = new Color(0x9AA0A6);

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".").getAbsoluteFile();
        write(banner(1), new File(root, "app/src/main/res/drawable-xhdpi/banner.png"));
        File play = new File(root, "docs/play");
        write(icon(512), new File(play, "icon-512.png"));
        write(banner(2), new File(play, "tv-banner-1280x720.png"));
        write(featureGraphic(), new File(play, "feature-graphic-1024x500.png"));

        File shots = new File(root, "app/src/androidTest/screenshots");
        String[] touch = {"home_full", "room_chat", "player_sheet_chat", "browse_series", "local_files", "search_results"};
        storeScreenshots(new File(shots, "phone-1080x2400"), touch, 1920, new File(play, "screenshots/phone"));
        storeScreenshots(new File(shots, "tablet-1600x2560"), touch, 2560, new File(play, "screenshots/tablet"));
        storeScreenshots(new File(shots, "tv-1920x1080"),
                new String[] {"home_full", "player_panel_chat", "connect_connected", "browse_series", "player_panel_playlist", "search"},
                1920, new File(play, "screenshots/tv"));
    }

    /** Each named screenshot, centred on a 16:9 (landscape) or 9:16 (portrait) canvas whose long side is [longSide]. */
    static void storeScreenshots(File dir, String[] names, int longSide, File out) throws Exception {
        for (int i = 0; i < names.length; i++) {
            BufferedImage src = ImageIO.read(new File(dir, names[i] + ".png"));
            boolean landscape = src.getWidth() >= src.getHeight();
            int w = landscape ? longSide : longSide * 9 / 16;
            int h = landscape ? longSide * 9 / 16 : longSide;
            double scale = Math.min((double) w / src.getWidth(), (double) h / src.getHeight());
            int sw = (int) Math.round(src.getWidth() * scale), sh = (int) Math.round(src.getHeight() * scale);
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = start(image);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(GRADIENT_END);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
            g.dispose();
            write(image, new File(out, String.format("%02d-%s.png", i + 1, names[i])));
        }
    }

    /** The launcher banner, 640x360 at scale 1 (320x180 dp at xhdpi). */
    static BufferedImage banner(int scale) {
        int w = 640 * scale, h = 360 * scale;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = start(image);
        background(g, w, h);
        logo(g, 125.0 * scale, h / 2.0, 2.7 * scale);
        g.setFont(font(true, 50f * scale));
        g.setColor(TEXT);
        g.drawString("Yarmiplay", 228 * scale, 170 * scale);
        g.setColor(ACCENT);
        g.drawString("TV", 228 * scale, 236 * scale);
        g.dispose();
        return image;
    }

    /** The Play Store icon: full square, Play applies its own corner mask. */
    static BufferedImage icon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = start(image);
        background(g, size, size);
        logo(g, size / 2.0, size / 2.0, size / 108.0 * 1.3);
        g.dispose();
        return image;
    }

    static BufferedImage featureGraphic() {
        int w = 1024, h = 500;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = start(image);
        background(g, w, h);
        logo(g, 230, h / 2.0, 4.2);
        g.setFont(font(true, 84f));
        g.setColor(TEXT);
        g.drawString("Yarmiplay", 410, 238);
        int tvX = 410 + g.getFontMetrics().stringWidth("Yarmiplay ");
        g.setColor(ACCENT);
        g.drawString("TV", tvX, 238);
        g.setFont(font(false, 38f));
        g.setColor(TEXT_DIM);
        g.drawString("Watch together, in sync.", 414, 300);
        g.drawString("TV, phone, tablet and desktop.", 414, 348);
        g.dispose();
        return image;
    }

    static Graphics2D start(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static void background(Graphics2D g, int w, int h) {
        g.setPaint(new GradientPaint(0f, 0f, GRADIENT_START, w, h, GRADIENT_END));
        g.fillRect(0, 0, w, h);
    }

    /** ic_logo.xml (108-unit viewport centred on 54, 54) centred on (cx, cy). */
    static void logo(Graphics2D g, double cx, double cy, double scale) {
        Graphics2D l = (Graphics2D) g.create();
        l.translate(cx, cy);
        l.scale(scale, scale);
        l.translate(-54.0, -54.0);
        l.setColor(ACCENT);
        l.fill(triangle(30, 28, 30, 80, 70, 54));
        l.setColor(new Color(255, 255, 255, 0xCC));
        l.fill(triangle(46, 34, 46, 74, 78, 54));
        l.dispose();
    }

    static Path2D triangle(double... p) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(p[0], p[1]);
        path.lineTo(p[2], p[3]);
        path.lineTo(p[4], p[5]);
        path.closePath();
        return path;
    }

    static Font font(boolean semibold, float size) {
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        if (semibold && Arrays.asList(families).contains("Segoe UI Semibold")) {
            return new Font("Segoe UI Semibold", Font.PLAIN, 1).deriveFont(size);
        }
        String family = Arrays.asList(families).contains("Segoe UI") ? "Segoe UI" : Font.SANS_SERIF;
        return new Font(family, semibold ? Font.BOLD : Font.PLAIN, 1).deriveFont(size);
    }

    static void write(BufferedImage image, File file) throws Exception {
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
        System.out.println("wrote " + file + " (" + image.getWidth() + "x" + image.getHeight() + ")");
    }
}

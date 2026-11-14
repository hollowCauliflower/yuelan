import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;

/**
 * 定制图标生成器（仅依赖 JDK，由 tools/icon_packager.js 调用）。
 * <p>
 * 两种模式：
 * <pre>
 *   launcher &lt;srcImage&gt; &lt;resDir&gt;
 *       把图片生成为桌面图标资源：自适应图标前景（432px，图片占 66% 安全区居中，
 *       画布填图片平均色）、ic_custom_bg 颜色、anydpi-v26 的两个 XML，
 *       以及 API 24-25 用的各密度方形/圆形位图。
 *   asset &lt;srcImage&gt; &lt;outFile&gt;
 *       把图片缩放（最长边 ≤ 512）后存为 PNG，打进 assets/custom_icons 图标集。
 * </pre>
 */
public final class IconResizer {

    /** 自适应图标前景画布边长（108dp @ xxxhdpi） */
    private static final int ADAPTIVE_FG_SIZE = 432;
    /** 图片在前景画布中的占比（自适应图标安全区约 66%） */
    private static final double ADAPTIVE_SAFE_RATIO = 0.66;
    /** 图标集图片最长边上限 */
    private static final int ASSET_MAX = 512;

    private IconResizer() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: launcher <srcImage> <resDir> | asset <srcImage> <outFile>"
                    + " | square <srcImage> <outFile> <size>");
            System.exit(1);
        }
        if ("launcher".equals(args[0])) {
            genLauncherIcons(Paths.get(args[1]), Paths.get(args[2]));
        } else if ("asset".equals(args[0])) {
            genAsset(Paths.get(args[1]), Paths.get(args[2]));
        } else if ("square".equals(args[0])) {
            int size = Integer.parseInt(args[3]);
            genSquare(Paths.get(args[1]), Paths.get(args[2]), size);
        } else {
            System.err.println("未知模式: " + args[0]);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------
    // square：正方形图片（替换向量图标资源用）
    // ------------------------------------------------------------------

    private static void genSquare(Path src, Path outFile, int size) throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("图片解码失败: " + src);
        }
        BufferedImage square = centerCropSquare(img);
        BufferedImage out = scale(square, size);
        Files.createDirectories(outFile.getParent());
        ImageIO.write(out, "PNG", outFile.toFile());
        System.out.println("square written: " + outFile);
    }

    // ------------------------------------------------------------------
    // launcher：桌面图标资源
    // ------------------------------------------------------------------

    private static void genLauncherIcons(Path src, Path resDir) throws IOException {
        BufferedImage square = centerCropSquare(ImageIO.read(src.toFile()));
        if (square == null) {
            throw new IOException("图片解码失败: " + src);
        }
        Color bg = averageColor(square);

        // 1) 自适应图标前景：画布填平均色，图片缩到安全区居中
        BufferedImage fg = new BufferedImage(ADAPTIVE_FG_SIZE, ADAPTIVE_FG_SIZE,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = fg.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, ADAPTIVE_FG_SIZE, ADAPTIVE_FG_SIZE);
        int inner = (int) (ADAPTIVE_FG_SIZE * ADAPTIVE_SAFE_RATIO);
        g.drawImage(scale(square, inner), (ADAPTIVE_FG_SIZE - inner) / 2,
                (ADAPTIVE_FG_SIZE - inner) / 2, null);
        g.dispose();
        Path nodpi = resDir.resolve("drawable-nodpi");
        Files.createDirectories(nodpi);
        ImageIO.write(fg, "PNG", nodpi.resolve("ic_custom_fg.png").toFile());

        // 2) 背景色 color 资源
        String colors = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<resources>\n"
                + String.format("    <color name=\"ic_custom_bg\">#%02X%02X%02X</color>\n",
                        bg.getRed(), bg.getGreen(), bg.getBlue())
                + "</resources>\n";
        Files.write(resDir.resolve("values").resolve("icon_custom_colors.xml"),
                colors.getBytes("UTF-8"));

        // 3) 自适应图标 XML（API 26+）
        String adaptiveXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
                + "    <background android:drawable=\"@color/ic_custom_bg\"/>\n"
                + "    <foreground android:drawable=\"@drawable/ic_custom_fg\"/>\n"
                + "</adaptive-icon>\n";
        Files.write(resDir.resolve("mipmap-anydpi-v26").resolve("ic_launcher.xml"),
                adaptiveXml.getBytes("UTF-8"));
        Files.write(resDir.resolve("mipmap-anydpi-v26").resolve("ic_launcher_round.xml"),
                adaptiveXml.getBytes("UTF-8"));

        // 4) 旧版位图（API 24-25）：方形 + 圆形
        String[] dpis = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] sizes = {48, 72, 96, 144, 192};
        for (int i = 0; i < dpis.length; i++) {
            Path dir = resDir.resolve("mipmap-" + dpis[i]);
            Files.createDirectories(dir);
            BufferedImage scaled = scale(square, sizes[i]);
            ImageIO.write(scaled, "PNG", dir.resolve("ic_launcher.png").toFile());
            ImageIO.write(applyCircleMask(scaled), "PNG",
                    dir.resolve("ic_launcher_round.png").toFile());
        }
        System.out.println("launcher icons generated, bg=" + String.format("#%02X%02X%02X",
                bg.getRed(), bg.getGreen(), bg.getBlue()));
    }

    // ------------------------------------------------------------------
    // asset：图标集图片
    // ------------------------------------------------------------------

    private static void genAsset(Path src, Path outFile) throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("图片解码失败: " + src);
        }
        int w = img.getWidth();
        int h = img.getHeight();
        int max = Math.max(w, h);
        BufferedImage out = max > ASSET_MAX
                ? scale(img, (int) ((long) w * ASSET_MAX / max), (int) ((long) h * ASSET_MAX / max))
                : img;
        Files.createDirectories(outFile.getParent());
        ImageIO.write(out, "PNG", outFile.toFile());
        System.out.println("asset written: " + outFile);
    }

    // ------------------------------------------------------------------
    // 图像工具
    // ------------------------------------------------------------------

    /** 居中裁剪为正方形 */
    private static BufferedImage centerCropSquare(BufferedImage img) {
        if (img == null) {
            return null;
        }
        int side = Math.min(img.getWidth(), img.getHeight());
        return img.getSubimage((img.getWidth() - side) / 2, (img.getHeight() - side) / 2,
                side, side);
    }

    /** 高质量缩放 */
    private static BufferedImage scale(BufferedImage img, int size) {
        return scale(img, size, size);
    }

    private static BufferedImage scale(BufferedImage img, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 图片平均色：按步长抽样所有像素求均值（直接缩 1x1 只会采到中心点附近的颜色） */
    private static Color averageColor(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int step = Math.max(1, Math.min(w, h) / 32);
        long r = 0, g = 0, b = 0, count = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int rgb = img.getRGB(x, y);
                r += (rgb >> 16) & 0xFF;
                g += (rgb >> 8) & 0xFF;
                b += rgb & 0xFF;
                count++;
            }
        }
        return new Color((int) (r / count), (int) (g / count), (int) (b / count));
    }

    /** 圆形透明遮罩 */
    private static BufferedImage applyCircleMask(BufferedImage img) {
        int size = img.getWidth();
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(new java.awt.geom.Ellipse2D.Float(0, 0, size, size));
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }
}

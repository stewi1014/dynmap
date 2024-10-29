package org.dynmap.utils;

import com.google.common.io.Files;
import org.dynmap.DynmapCore;
import org.dynmap.Log;
import org.dynmap.MapType.ImageEncoding;
import org.dynmap.MapType.ImageFormat;
import org.dynmap.storage.MapStorageTile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DirectColorModel;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Implements soft-locks for prevent concurrency issues with file updates
 */
public class ImageIOManager {
    public static String preUpdateCommand = null;
    public static String postUpdateCommand = null;
    private static Object imageioLock = new Object();
    public static DynmapCore core;    // Injected during enableCore

    private static boolean did_warning = false;

    private static ImageFormat validateFormat(ImageFormat fmt) {
        // If WEBP, see if supported
        if (fmt.getEncoding() == ImageEncoding.WEBP) {
            if (core.getCWEBPPath() == null) {    // No encoder?
                if (!did_warning) {
                    Log.warning("Attempt to use WEBP support when not usable: using JPEG");
                    did_warning = true;
                }
                fmt = ImageFormat.FORMAT_JPG;    // Switch to JPEN
            }
        }
        return fmt;
    }

    private static void doWEBPEncode(BufferedImage img, ImageFormat fmt, OutputStream out) throws IOException {
        BufferOutputStream bos = new BufferOutputStream();

        ImageIO.write(img, "png", bos); // Encode as PNG in buffere output stream
        // Write to a tmp file
        File tmpfile = File.createTempFile("pngToWebp", "png");
        FileOutputStream fos = new FileOutputStream(tmpfile);
        fos.write(bos.buf, 0, bos.len);
        fos.close();
        // Run encoder to new new temp file
        File tmpfile2 = File.createTempFile("pngToWebp", "webp");
        ArrayList<String> args = new ArrayList<String>();
        args.add(core.getCWEBPPath());
        if (fmt.getID().endsWith("-l")) {
            args.add("-lossless");
        }
        args.add("-q");
        args.add(Integer.toString((int) fmt.getQuality()));
        args.add(tmpfile.getAbsolutePath());
        args.add("-o");
        args.add(tmpfile2.getAbsolutePath());
        Process pr = Runtime.getRuntime().exec(args.toArray(new String[0]));
        try {
            pr.waitFor();
        } catch (InterruptedException ix) {
            throw new IOException("Error waiting for encoder");
        }
        // Read output file into output stream
        Files.copy(tmpfile2, out);
        out.flush();
        // Clean up temp files
        tmpfile.delete();
        tmpfile2.delete();
    }

    private static BufferedImage doWEBPDecode(BufferInputStream buf) throws IOException {
        // Write to a tmp file
        File tmpfile = File.createTempFile("webpToPng", "webp");
        Files.write(buf.buffer(), tmpfile);
        // Run encoder to new new temp file
        File tmpfile2 = File.createTempFile("webpToPng", "png");
        String args[] = {core.getDWEBPPath(), tmpfile.getAbsolutePath(), "-o", tmpfile2.getAbsolutePath()};
        Process pr = Runtime.getRuntime().exec(args);
        try {
            pr.waitFor();
        } catch (InterruptedException ix) {
            throw new IOException("Error waiting for encoder");
        }
        // Read file
        BufferedImage obuf = ImageIO.read(tmpfile2);
        // Clean up temp files
        tmpfile.delete();
        tmpfile2.delete();

        return obuf;
    }

    public static BufferOutputStream imageIOEncode(BufferedImage img, ImageFormat fmt) {
        if (isRequiredJDKVersion(17, -1, -1)) {
            return imageIOEncodeUnsafe(img, fmt); //we can skip Thread safety for more performance
        }
        synchronized (imageioLock) {
            return imageIOEncodeUnsafe(img, fmt);
        }
    }

    private static BufferOutputStream imageIOEncodeUnsafe(BufferedImage img, ImageFormat fmt) {
        BufferOutputStream bos = new BufferOutputStream();
        try {
            ImageIO.setUseCache(false); /* Don't use file cache - too small to be worth it */

            fmt = validateFormat(fmt);

            if (fmt.getEncoding() == ImageEncoding.JPG) {
                WritableRaster raster = img.getRaster();
                WritableRaster newRaster = raster.createWritableChild(0, 0, img.getWidth(),
                        img.getHeight(), 0, 0, new int[]{0, 1, 2});
                DirectColorModel cm = (DirectColorModel) img.getColorModel();
                DirectColorModel newCM = new DirectColorModel(cm.getPixelSize(),
                        cm.getRedMask(), cm.getGreenMask(), cm.getBlueMask());
                // now create the new buffer that is used ot write the image:
                BufferedImage rgbBuffer = new BufferedImage(newCM, newRaster, false, null);

                // Find a jpeg writer
                ImageWriter writer = null;
                Iterator<ImageWriter> iter = ImageIO.getImageWritersByFormatName("jpg");
                if (iter.hasNext()) {
                    writer = iter.next();
                }
                if (writer == null) {
                    Log.severe("No JPEG ENCODER - Java VM does not support JPEG encoding");
                    return null;
                }
                ImageWriteParam iwp = writer.getDefaultWriteParam();
                iwp.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                iwp.setCompressionQuality(fmt.getQuality());

                ImageOutputStream ios;
                ios = ImageIO.createImageOutputStream(bos);
                writer.setOutput(ios);

                writer.write(null, new IIOImage(rgbBuffer, null, null), iwp);
                writer.dispose();

                rgbBuffer.flush();
            } else if (fmt.getEncoding() == ImageEncoding.WEBP) {
                doWEBPEncode(img, fmt, bos);
            } else {
                ImageIO.write(img, fmt.getFileExt(), bos); /* Write to byte array stream - prevent bogus I/O errors */
            }
        } catch (IOException iox) {
            Log.info("Error encoding image - " + iox.getMessage());
            return null;
        }
        return bos;
    }

    public static BufferedImage imageIODecode(MapStorageTile.TileRead tr) throws IOException {
        if (isRequiredJDKVersion(17, -1, -1)) {
            return imageIODecodeUnsafe(tr); //we can skip Thread safety for more performance
        }
        synchronized (imageioLock) {
            return imageIODecodeUnsafe(tr);
        }
    }

    private static BufferedImage imageIODecodeUnsafe(MapStorageTile.TileRead tr) throws IOException {
        ImageIO.setUseCache(false); /* Don't use file cache - too small to be worth it */
        if (tr.format == ImageEncoding.WEBP) {
            return doWEBPDecode(tr.image);
        }
        return ImageIO.read(tr.image);
    }

    /**
     * Checks if the current JDK is running at least a specific version
     * targetMinor and targetBuild can be set to -1, if the java.version only provides a Major release this will then only check for the major release
     *
     * @param targetMajor the required minimum major version
     * @param targetMinor the required minimum minor version
     * @param targetBuild the required minimum build version
     * @return true if the current JDK version is the required minimum version
     */
    private static boolean isRequiredJDKVersion(int targetMajor, int targetMinor, int targetBuild) {
        String javaVersion = System.getProperty("java.version");
        String[] versionParts = javaVersion.split("\\.");
        if (versionParts.length < 3) {
            if (versionParts.length == 1
                    && targetMinor == -1
                    && targetBuild == -1
                    && parseInt(versionParts[0], -1) >= targetMajor) {
                return true;//we only have a major version and thats ok
            }
            return false; //can not evaluate
        }
        int major = parseInt(versionParts[0], -1);
        int minor = parseInt(versionParts[1], -1);
        int build = parseInt(versionParts[2], -1);
        if (major != -1 && major >= targetMajor &&
                minor != -1 && minor >= targetMinor &&
                build != -1 && build >= targetBuild
        ) {
            return true;
        }
        return false;
    }

    /**
     * Parses a string to int, with a dynamic fallback value if not parsable
     *
     * @param input    the String to parse
     * @param fallback the Fallback value to use
     * @return the parsed integer or the fallback value if unparsable
     */
    private static int parseInt(String input, int fallback) {
        int output = fallback;
        try {
            output = Integer.parseInt(input);
        } catch (NumberFormatException e) {
        }
        return output;
    }
}

package app.organicmaps.sdk.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class GzipUtils {
    private GzipUtils() {}

    public static byte[] compress(byte[] data) throws IOException {
        if (data == null || data.length == 0) return data;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        GZIPOutputStream gzip = new GZIPOutputStream(bos);
        gzip.write(data);
        gzip.close();
        byte[] compressed = bos.toByteArray();
        bos.close();
        return compressed;
    }

    public static byte[] decompress(byte[] data) throws IOException {
        if (data == null || data.length == 0) return data;
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        GZIPInputStream gzip = new GZIPInputStream(bis);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = gzip.read(buffer)) > 0) {
            bos.write(buffer, 0, len);
        }
        gzip.close();
        bis.close();
        byte[] decompressed = bos.toByteArray();
        bos.close();
        return decompressed;
    }
}

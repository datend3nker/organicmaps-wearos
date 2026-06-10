package app.organicmaps.sdk.util;

import androidx.annotation.NonNull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.CRC32;

public class ChecksumUtils {
    private static final int BUFFER_SIZE = 64 * 1024;

    public static long calculateCRC32(@NonNull File file) throws IOException {
        return calculateCRC32(file, file.length());
    }

    public static long calculateCRC32(@NonNull File file, long length) throws IOException {
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long totalRead = 0;
            int read;
            while (totalRead < length && (read = fis.read(buffer, 0, (int) Math.min(buffer.length, length - totalRead))) != -1) {
                crc.update(buffer, 0, read);
                totalRead += read;
            }
        }
        return crc.getValue();
    }
}

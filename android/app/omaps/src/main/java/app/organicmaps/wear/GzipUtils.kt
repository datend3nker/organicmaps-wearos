package app.organicmaps.wear

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

object GzipUtils {
    fun decompress(data: ByteArray): ByteArray {
        val bis = data.inputStream()
        val gis = GZIPInputStream(bis)
        val bos = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var len: Int
        while (gis.read(buffer).also { len = it } > 0) {
            bos.write(buffer, 0, len)
        }
        gis.close()
        return bos.toByteArray()
    }
}

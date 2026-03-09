package io.musicassistant.companion.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.InputStream

/**
 * A Media3 DataSource that reads from an InputStream. Used to feed WebSocket audio data (via
 * AudioStreamPipe) into ExoPlayer.
 */
class InputStreamDataSource(private val inputStream: InputStream) :
        BaseDataSource(/* isNetwork= */ false) {

    private var opened = false
    private var bytesRead = 0L

    override fun open(dataSpec: DataSpec): Long {
        opened = true
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened) return C.RESULT_END_OF_INPUT
        val read = inputStream.read(buffer, offset, length)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRead += read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri = Uri.EMPTY

    override fun close() {
        opened = false
        try {
            inputStream.close()
        } catch (_: Exception) {}
        transferEnded()
    }
}

package app.hermes.channels

import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import app.hermes.channels.core.Mp2

/**
 * Sound in MPEG audio Layer II, decoded by the app itself. Many broadcast channels carry it, and some TVs have no
 * decoder for it: there the channel would play without sound. It stands after the device's own decoders in the
 * list, so a device that has one keeps using its own.
 */
class Mp2Renderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<Mp2Renderer.Decoder>(handler, listener, sink) {

    override fun getName() = "Mp2Renderer"

    override fun supportsFormatInternal(format: Format): Int =
        // (the half-rate kind, 16 to 24 kHz, is another table set; it is left to whoever can play it)
        if (format.sampleMimeType == MimeTypes.AUDIO_MPEG_L2 && (format.sampleRate == Format.NO_VALUE || format.sampleRate >= 32000)) C.FORMAT_HANDLED
        else C.FORMAT_UNSUPPORTED_TYPE

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Decoder {
        Log.i("Channels", "the sound (MPEG Layer II) is decoded by the app: this device has no decoder for it")
        return Decoder(if (format.sampleRate > 0) format.sampleRate else 48000)
    }

    override fun getOutputFormat(decoder: Decoder): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(2).setSampleRate(decoder.rate).setPcmEncoding(C.ENCODING_PCM_16BIT).build()

    @Suppress("UNCHECKED_CAST")
    class Decoder(val rate: Int) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, DecoderException>(
        arrayOfNulls<DecoderInputBuffer>(16) as Array<DecoderInputBuffer>, arrayOfNulls<SimpleDecoderOutputBuffer>(16) as Array<SimpleDecoderOutputBuffer>) {

        private val mp2 = Mp2()
        private var bytes = ByteArray(4096)
        private var pcm = ShortArray(Mp2.SAMPLES * 2)

        init { setInitialInputBufferSize(4096) }

        override fun getName() = "mp2"
        override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }
        override fun createUnexpectedDecodeException(error: Throwable) = DecoderException("mp2", error)

        override fun decode(input: DecoderInputBuffer, output: SimpleDecoderOutputBuffer, reset: Boolean): DecoderException? {
            if (reset) mp2.reset()
            val data = input.data ?: run { output.shouldBeSkipped = true; return null }
            val n = data.remaining()
            if (bytes.size < n) bytes = ByteArray(n)
            data.duplicate().get(bytes, 0, n)
            // (a piece is one frame as a rule; more than one, and bytes that are no frame, are allowed for)
            var off = 0; var frames = 0
            while (off + 4 <= n) {
                if (Mp2.frameSize(bytes, off, n - off) == 0) { off++; continue }
                if (pcm.size < (frames + 1) * Mp2.SAMPLES * 2) pcm = pcm.copyOf(pcm.size * 2)
                val took = mp2.decode(bytes, off, n - off, pcm, frames * Mp2.SAMPLES * 2)
                if (took == 0) break
                off += took; frames++
            }
            if (frames == 0) { output.shouldBeSkipped = true; return null }
            val count = frames * Mp2.SAMPLES * 2
            val out = output.init(input.timeUs, count * 2)
            out.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, count)
            out.position(0); out.limit(count * 2)
            return null
        }
    }
}

package com.jakob.dmd.domain.compress

import com.jakob.dmd.domain.model.MediaKind
import com.jakob.dmd.util.MediaTypeDetector
import javax.inject.Inject
import javax.inject.Singleton

/** Picks the right Compressor based on file extension. */
@Singleton
class CompressorRouter @Inject constructor(
    private val image: ImageCompressor,
    private val video: VideoCompressor,
    private val audio: AudioCompressor,
    private val gif: GifCompressor,
) {
    fun forName(name: String): Compressor {
        val kind = MediaTypeDetector.kindOf(name)
            ?: throw IllegalArgumentException("Unsupported file type: $name")
        return when (kind) {
            MediaKind.IMAGE -> image
            MediaKind.GIF -> gif
            MediaKind.VIDEO -> video
            MediaKind.AUDIO -> audio
        }
    }
}

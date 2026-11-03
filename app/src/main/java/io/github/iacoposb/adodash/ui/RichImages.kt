package io.github.iacoposb.adodash.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The images of comments and descriptions: read with [load], decoded and kept in memory. */
class ImageLoader(private val load: suspend (String) -> ByteArray) {
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    /** The image at [url]; null if it cannot be read or is not an image. */
    suspend fun image(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        val bytes = try {
            load(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return withContext(Dispatchers.Default) { decode(bytes) }?.also { cache.put(url, it) }
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Halved until it fits: a screenshot is shown at most as wide as a comment
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }

    private companion object {
        const val CACHE_BYTES = 48 * 1024 * 1024
        const val MAX_SIDE = 2048
    }
}

/** How the screens read images; none (as in the screenshots) shows their description. */
val LocalImages = staticCompositionLocalOf<ImageLoader?> { null }

/** [image] as wide as it is, at most as wide as the text, opening its address when tapped. */
@Composable
internal fun RichImageView(image: RichImage, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    val loader = LocalImages.current
    var state by remember(image.url, loader) {
        mutableStateOf<ImageState>(loader?.cached(image.url)?.let(ImageState::Shown) ?: ImageState.Loading)
    }
    LaunchedEffect(image.url, loader) {
        if (state !is ImageState.Shown) state = loader?.image(image.url)?.let(ImageState::Shown) ?: ImageState.Failed
    }
    val open = image.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val shape = RoundedCornerShape(8.dp)
    val tap = if (open != null) Modifier.clickable { onLink(open) } else Modifier
    when (val s = state) {
        is ImageState.Shown -> {
            val bitmap = s.bitmap
            val width = with(LocalDensity.current) { bitmap.width.toDp() }
            Image(
                bitmap, image.alt.ifEmpty { null },
                modifier.widthIn(max = width).heightIn(max = MAX_HEIGHT.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height)
                    .clip(shape).border(1.dp, c.outline, shape).then(tap),
                contentScale = ContentScale.Fit,
            )
        }
        else -> Box(modifier.clip(shape).background(c.surfaceAlt).then(tap).padding(horizontal = 12.dp, vertical = 8.dp)) {
            val text = when {
                s == ImageState.Loading -> stringResource(R.string.image_loading)
                image.alt.isNotBlank() -> stringResource(R.string.image_failed_named, image.alt)
                else -> stringResource(R.string.image_failed)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}

private const val MAX_HEIGHT = 480

private sealed interface ImageState {
    data object Loading : ImageState
    data object Failed : ImageState
    data class Shown(val bitmap: ImageBitmap) : ImageState
}

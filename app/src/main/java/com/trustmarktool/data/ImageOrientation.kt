package com.trustmarktool.data

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * TrustMark works on pixel data. Android photos frequently carry an EXIF
 * orientation tag instead of physically rotated pixels, so we pass the tag to
 * Rust and normalize the image before inference/output.
 */
object ImageOrientation {

    fun read(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}

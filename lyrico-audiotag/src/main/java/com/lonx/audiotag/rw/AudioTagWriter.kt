package com.lonx.audiotag.rw

import com.lonx.audiotag.TagLib
import com.lonx.audiotag.internal.TagLog
import com.lonx.audiotag.model.AudioPicture
import com.lonx.audiotag.model.Picture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.HashMap

object AudioTagWriter {
    private const val TAG = "AudioTagWriter"

    suspend fun writeTags(
        path: Path,
        updates: Map<String, String>,
        preserveOldTags: Boolean = true
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val mapToSave = HashMap<String, Array<String>>()

                if (preserveOldTags) {
                    val oldMeta = TagLib.getMetadata(path, false)
                    if (oldMeta != null) {
                        mapToSave.putAll(oldMeta.propertyMap)
                    }
                }

                for ((k, v) in updates) {
                    mapToSave[k] = arrayOf(v)
                }

                mapToSave.forEach { (string, strings) ->
                    TagLog.d(TAG, "Write tag: $string = $strings")
                }
                return@withContext TagLib.savePropertyMap(path, mapToSave)
            } catch (e: Exception) {
                TagLog.e(TAG, "Write tags error", e)
                return@withContext false
            }
        }
    }


    suspend fun writePictures(path: Path, pictures: List<AudioPicture>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val libPics = ArrayList<Picture>()
                for (p in pictures) {
                    libPics.add(Picture(
                        data = p.data,
                        mimeType = p.mimeType,
                        description = p.description,
                        pictureType = p.pictureType
                    ))
                }
                val arr = libPics.toTypedArray()
                return@withContext TagLib.savePictures(path, arr)
            } catch (e: Exception) {
                TagLog.e(TAG, "Write pictures error", e)
                return@withContext false
            }
        }
    }
}

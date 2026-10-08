#include "tfilestream.h"
#include "utils.h"

#include <mpegfile.h>
#include <vorbisfile.h>
#include <flacfile.h>
#include <opusfile.h>
#include <mp4file.h>
#include <wavfile.h>
#include <apefile.h>

#include <memory>
#include <stdexcept>
#include <string>

#ifdef _WIN32
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  ifndef NOMINMAX
#    define NOMINMAX
#  endif
#  include <windows.h>
#endif

namespace {

// The desktop port addresses files by absolute path rather than by POSIX file
// descriptor: TagLib's FileStream(int) constructor is unavailable on Windows
// (see taglib/taglib/toolkit/tfilestream.cpp).
//
// On Windows the path is taken as UTF-16 straight from the JVM. Going through
// GetStringUTFChars would hand us *modified* UTF-8, which only coincides with
// real UTF-8 for the BMP - a supplementary character (an emoji in a file name,
// say) is encoded as a surrogate pair there and would be mangled. The JVM's
// internal representation is already the wide string TagLib wants, so we read it
// directly and let FileName copy it.
std::unique_ptr<TagLib::FileStream> openFileStream(JNIEnv *env, jstring path, bool readOnly) {
    if (path == nullptr) {
        return nullptr;
    }

    std::unique_ptr<TagLib::FileStream> stream;

#ifdef _WIN32
    static_assert(sizeof(wchar_t) == sizeof(jchar), "Windows wchar_t must be 16-bit for this cast");

    const jchar *chars = env->GetStringChars(path, nullptr);
    if (chars == nullptr) {
        return nullptr;
    }

    const std::wstring wide(reinterpret_cast<const wchar_t *>(chars),
                            static_cast<size_t>(env->GetStringLength(path)));
    env->ReleaseStringChars(path, chars);

    stream = std::make_unique<TagLib::FileStream>(TagLib::FileName(wide.c_str()), readOnly);
#else
    const char *utf8 = env->GetStringUTFChars(path, nullptr);
    if (utf8 == nullptr) {
        return nullptr;
    }
    stream = std::make_unique<TagLib::FileStream>(TagLib::FileName(utf8), readOnly);
    env->ReleaseStringUTFChars(path, utf8);
#endif

    return stream;
}

}  // namespace

template<typename FileType>
TagLib::File *createSupportedFile(TagLib::IOStream *stream,
                                  bool readAudioProperties,
                                  TagLib::AudioProperties::ReadStyle audioPropertiesStyle) {
    stream->seek(0);
    if (!FileType::isSupported(stream)) {
        return nullptr;
    }
    stream->seek(0);
    return new FileType(stream, readAudioProperties, audioPropertiesStyle);
}

TagLib::File* createFileFromContent(TagLib::IOStream *stream,
                                    bool readAudioProperties,
                                    TagLib::AudioProperties::ReadStyle audioPropertiesStyle) {
    TagLib::File *file = nullptr;

    // APE has a distinctive signature. Check it before MPEG's frame scan,
    // which can mistake compressed lossless data for an MPEG frame header.
    file = createSupportedFile<TagLib::APE::File>(
            stream, readAudioProperties, audioPropertiesStyle);
    if (!file) {
        file = createSupportedFile<TagLib::MPEG::File>(
                stream, readAudioProperties, audioPropertiesStyle);
    }
    if (!file) {
        file = createSupportedFile<TagLib::Ogg::Vorbis::File>(
                stream, readAudioProperties, audioPropertiesStyle);
    }
    if (!file) {
        file = createSupportedFile<TagLib::FLAC::File>(
                stream, readAudioProperties, audioPropertiesStyle);
    }
    if (!file) {
        file = createSupportedFile<TagLib::Ogg::Opus::File>(
                stream, readAudioProperties, audioPropertiesStyle);
    }
    if (!file) {
        file = createSupportedFile<TagLib::MP4::File>(
                stream, readAudioProperties, audioPropertiesStyle);
    }
    if (!file) {
        file = createSupportedFile<TagLib::RIFF::WAV::File>(
                stream, readAudioProperties, audioPropertiesStyle);
    }

    if (!file) {
        return nullptr;
    }

    if (file->isValid()) {
        return file;
    }
    bool hasTags = (file->tag() && !file->tag()->isEmpty()) || !file->properties().isEmpty();

    if (hasTags) {
        return file;
    }
    delete file;

    return nullptr;
}


extern "C" {

JNIEXPORT jobject JNICALL
Java_com_lonx_audiotag_internal_AudioTagNative_getAudioProperties(
        JNIEnv *env, jclass, jstring path, jint read_style) {
    try {
        auto stream = openFileStream(env, path, true);
        const auto style = static_cast<TagLib::AudioProperties::ReadStyle>(read_style);

        std::unique_ptr<TagLib::File> file(createFileFromContent(stream.get(), true, style));

        if (!file) {
            return emptyAudioProperties(env);
        }

        return getAudioProperties(env, file.get());
    } catch (const std::exception &e) {
        LOGE("Error reading audio properties: %s", e.what());
        return emptyAudioProperties(env);
    } catch (...) {
        LOGE("Unknown error reading audio properties");
        return emptyAudioProperties(env);
    }
}

JNIEXPORT jobject JNICALL
Java_com_lonx_audiotag_internal_AudioTagNative_getMetadata(
        JNIEnv *env, jclass, jstring path, jboolean read_pictures) {
    try {
        auto stream = openFileStream(env, path, true);
        std::unique_ptr<TagLib::File> file(createFileFromContent(stream.get(), false, TagLib::AudioProperties::Average));

        if (!file) {
            return nullptr;
        }

        jobject propertiesMap = getPropertyMap(env, file.get());
        jobjectArray pictures;
        if (read_pictures) {
            pictures = getPictures(env, file.get());
        } else {
            pictures = emptyPictureArray(env);
        }

        bool supportsPictureTypes = true;

        if (dynamic_cast<TagLib::MP4::File *>(file.get()) != nullptr) {
            supportsPictureTypes = false;
        }

        jboolean supports = supportsPictureTypes
                            ? JNI_TRUE
                            : JNI_FALSE;

        return env->NewObject(
                metadataClass,
                metadataConstructor,
                propertiesMap,
                pictures,
                supports
        );

    } catch (const std::exception &e) {
        LOGE("Error reading metadata: %s", e.what());
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_lonx_audiotag_internal_AudioTagNative_getMetadataPropertyValues(
        JNIEnv *env, jclass, jstring path, jstring property_name) {

    const char *propertyName = env->GetStringUTFChars(property_name, nullptr);
    if (propertyName == nullptr) return nullptr;

    try {
        auto stream = openFileStream(env, path, true);
        std::unique_ptr<TagLib::File> file(createFileFromContent(stream.get(), false, TagLib::AudioProperties::Average));

        if (!file) {
            env->ReleaseStringUTFChars(property_name, propertyName);
            return nullptr;
        }

        const auto propertyMap = file->properties();
        auto it = propertyMap.find(TagLib::String(propertyName));

        if (it == propertyMap.end()) {
            env->ReleaseStringUTFChars(property_name, propertyName);
            return nullptr;
        }

        const auto valueList = it->second;
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(valueList.size()), stringClass, nullptr);

        int i = 0;
        for (const auto &value: valueList) {
            jstring jValue = env->NewStringUTF(value.toCString(true));
            env->SetObjectArrayElement(result, i, jValue);
            env->DeleteLocalRef(jValue);
            i++;
        }

        env->ReleaseStringUTFChars(property_name, propertyName);
        return result;

    } catch (const std::exception &e) {
        LOGE("Error reading property values: %s", e.what());
        env->ReleaseStringUTFChars(property_name, propertyName);
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_lonx_audiotag_internal_AudioTagNative_getPictures(
        JNIEnv *env, jclass, jstring path) {
    try {
        auto stream = openFileStream(env, path, true);
        std::unique_ptr<TagLib::File> file(createFileFromContent(stream.get(), false, TagLib::AudioProperties::Average));

        if (!file) {
            return emptyPictureArray(env);
        }

        return getPictures(env, file.get());
    } catch (const std::exception &e) {
        LOGE("Error reading pictures: %s", e.what());
        return emptyPictureArray(env);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_lonx_audiotag_internal_AudioTagNative_savePropertyMap(
        JNIEnv *env, jclass, jstring path, jobject property_map) {
    try {
        auto stream = openFileStream(env, path, false);
        std::unique_ptr<TagLib::File> file(createFileFromContent(stream.get(), false, TagLib::AudioProperties::Average));

        if (!file) {
            return false;
        }

        TagLib::PropertyMap props = file->properties();

        const PropertyMap updates = JniHashMapToPropertyMap(env, property_map);

        for (const auto & update : updates) {
            const TagLib::String &key = update.first;
            const TagLib::StringList &values = update.second;

            if (values.isEmpty() || (values.size() == 1 && values.front().isEmpty())) {
                props.erase(key);
            } else {
                props.replace(key, values);
            }
        }

        file->setProperties(props);
        return file->save();

    } catch (const std::exception &e) {
        LOGE("Error saving property map: %s", e.what());
        return false;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_lonx_audiotag_internal_AudioTagNative_savePictures(
        JNIEnv *env, jclass, jstring path, jobjectArray pictures) {
    try {
        auto stream = openFileStream(env, path, false);
        std::unique_ptr<TagLib::File> file(createFileFromContent(stream.get(), false, TagLib::AudioProperties::Average));

        if (!file) {
            return false;
        }

        auto pictureList = JniPictureArrayToPictureList(env, pictures);
        file->setComplexProperties("PICTURE", pictureList);

        return file->save();
    } catch (const std::exception &e) {
        LOGE("Error saving pictures: %s", e.what());
        return false;
    }
}

} // extern "C"

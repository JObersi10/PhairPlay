/*
 * alac_jni.cpp — thin JNI bridge over Apple's open-source ALAC decoder (alac/).
 *
 * macOS sends system-audio AirPlay (Chrome, any app via "Sound output") as ALAC (codec type 2),
 * regardless of what /info advertises. This TV (and most Android TVs) has no hardware ALAC codec,
 * so we decode in software here: AES-CBC-decrypted ALAC frame → 16-bit interleaved PCM → AudioTrack.
 *
 * Backs com.phairplay.airplay.handshake.AlacDecoder.
 */
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <android/log.h>

#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"

#define LOG_TAG "AlacJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Largest ALAC frame we will decode: 4096 samples (the biggest spf any AirPlay sender uses) at
 * 2 channels / 16 bit, which is also the incompressible worst case. Real AirPlay frames are
 * spf=352 → well under 1.5 KB.
 */
static constexpr uint32_t MAX_ALAC_FRAME_BYTES = 4096 * 2 * 2;

/* Zeroed slack after the frame so Apple's unchecked bit-reader look-ahead stays in mapped memory. */
static constexpr uint32_t ALAC_READAHEAD_PAD = 64;

extern "C" {

/*
 * nativeInit(magicCookie) → opaque decoder handle (0 on failure).
 * magicCookie is the 24-byte big-endian ALACSpecificConfig (frameLength, bitDepth, pb/mb/kb,
 * numChannels, maxRun, sampleRate, …) built by AlacDecoder.buildMagicCookie().
 */
JNIEXPORT jlong JNICALL
Java_com_phairplay_airplay_handshake_AlacDecoder_nativeInit(JNIEnv *env, jobject, jbyteArray cookie) {
    if (cookie == nullptr) return 0;
    const jsize len = env->GetArrayLength(cookie);
    if (len <= 0) return 0;

    jbyte *cookieBytes = env->GetByteArrayElements(cookie, nullptr);
    if (cookieBytes == nullptr) return 0;

    auto *decoder = new ALACDecoder();
    const int32_t status = decoder->Init(cookieBytes, static_cast<uint32_t>(len));
    env->ReleaseByteArrayElements(cookie, cookieBytes, JNI_ABORT);

    if (status != 0) {
        LOGE("ALACDecoder.Init failed: %d", status);
        delete decoder;
        return 0;
    }
    if (decoder->mConfig.bitDepth != 16) {
        // The Kotlin/AudioTrack side assumes 16-bit PCM out; bail loudly if a cookie ever says otherwise.
        LOGE("Unsupported ALAC bitDepth %d (only 16 supported)", decoder->mConfig.bitDepth);
        delete decoder;
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

/*
 * nativeDecode(handle, input, inputLen, output) → number of PCM bytes written to output (-1 on error).
 * input holds one decrypted ALAC frame; output must be sized for frameLength*numChannels*2 bytes.
 */
JNIEXPORT jint JNICALL
Java_com_phairplay_airplay_handshake_AlacDecoder_nativeDecode(
        JNIEnv *env, jobject, jlong handle, jbyteArray input, jint inputLen, jbyteArray output) {
    if (handle == 0 || input == nullptr || output == nullptr) return -1;
    auto *decoder = reinterpret_cast<ALACDecoder *>(handle);

    const jsize inCap = env->GetArrayLength(input);
    if (inputLen <= 0 || inputLen > inCap) return -1;

    const uint32_t numChannels = decoder->mConfig.numChannels;
    const uint32_t frameLength = decoder->mConfig.frameLength;
    if (numChannels == 0 || frameLength == 0) return -1;

    const jsize outCap = env->GetArrayLength(output);
    if (outCap < static_cast<jsize>(frameLength * numChannels * 2)) return -1;

    // Apple's ag_dec.c reads ahead past the current bit position without bounds checking
    // (dyn_get_32bit pulls 32 bits plus look-ahead). Handing it a buffer sized exactly to the
    // packet means one corrupt or truncated frame walks off the end of the allocation and takes
    // the whole process down with SIGSEGV — observed as a page-aligned SEGV_MAPERR in
    // dyn_get_32bit. Decode from a padded copy so any over-read lands in our own zeroed slack;
    // the BitBuffer still gets the true length, so the decoder's own end-of-stream checks fire
    // and Decode() returns an error we can report normally.
    uint8_t padded[MAX_ALAC_FRAME_BYTES + ALAC_READAHEAD_PAD];
    if (inputLen > static_cast<jint>(MAX_ALAC_FRAME_BYTES)) {
        LOGE("ALAC frame too large: %d bytes", inputLen);
        return -1;
    }
    env->GetByteArrayRegion(input, 0, inputLen, reinterpret_cast<jbyte *>(padded));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return -1;
    }
    memset(padded + inputLen, 0, ALAC_READAHEAD_PAD);

    jbyte *outBytes = env->GetByteArrayElements(output, nullptr);
    if (outBytes == nullptr) return -1;

    BitBuffer bits;
    BitBufferInit(&bits, padded, static_cast<uint32_t>(inputLen));

    uint32_t outNumSamples = 0;
    const int32_t status = decoder->Decode(
            &bits, reinterpret_cast<uint8_t *>(outBytes), frameLength, numChannels, &outNumSamples);

    if (status != 0) {
        env->ReleaseByteArrayElements(output, outBytes, JNI_ABORT);
        LOGE("ALACDecoder.Decode failed: %d", status);
        return -1;
    }
    // Decode() reports how many samples it produced; a corrupt frame can claim more than the
    // frameLength the output buffer was sized for, so clamp before trusting it downstream.
    if (outNumSamples > frameLength) {
        env->ReleaseByteArrayElements(output, outBytes, JNI_ABORT);
        LOGE("ALAC decoded %u samples > frameLength %u — dropping frame", outNumSamples, frameLength);
        return -1;
    }

    const jint outByteCount = static_cast<jint>(outNumSamples * numChannels * 2);
    // Commit the PCM bytes back to the Java array (mode 0 = copy back + free).
    env->ReleaseByteArrayElements(output, outBytes, 0);
    return outByteCount;
}

JNIEXPORT void JNICALL
Java_com_phairplay_airplay_handshake_AlacDecoder_nativeRelease(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<ALACDecoder *>(handle);
}

}  // extern "C"

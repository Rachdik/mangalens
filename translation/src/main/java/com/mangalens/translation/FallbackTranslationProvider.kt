package com.mangalens.translation

/**
 * Provider utama dengan cadangan on-device.
 *
 * Dipakai ketika pengguna mengaktifkan "Automatic fallback": LLM/MyMemory tetap
 * provider pilihan, tetapi kegagalan jaringan, kuota, atau kunci API tidak
 * membuat halaman kosong. Hasil yang ditampilkan tetap terjemahan, bukan error.
 */
class FallbackTranslationProvider(
    private val primary: TranslationProvider,
    private val fallback: TranslationProvider
) : TranslationProvider {

    override val id: String = "${primary.id}+fallback"
    override val displayName: String = "${primary.displayName} + ${fallback.displayName}"

    override suspend fun prepare(): Boolean =
        runCatching { primary.prepare() }.getOrDefault(false) ||
            runCatching { fallback.prepare() }.getOrDefault(false)

    override suspend fun translate(requests: List<TranslationRequest>): TranslateResult {
        if (requests.isEmpty()) return TranslateResult.Success(emptyMap())

        val primaryReady = runCatching { primary.prepare() }.getOrDefault(false)
        if (primaryReady) {
            val result = runCatching { primary.translate(requests) }.getOrNull()
            if (result is TranslateResult.Success) return result
        }

        val fallbackReady = runCatching { fallback.prepare() }.getOrDefault(false)
        if (!fallbackReady) {
            return TranslateResult.Failure(
                "Provider utama gagal dan fallback on-device belum siap."
            )
        }
        return runCatching { fallback.translate(requests) }
            .getOrElse { TranslateResult.Failure("Semua provider terjemahan gagal.") }
    }
}

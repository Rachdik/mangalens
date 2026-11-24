package com.mangalens.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uji perangkat: memanggil ML Kit langsung tanpa pipeline/IndonesianStyle.
 * Tujuannya memisahkan kualitas model on-device dari bug routing aplikasi.
 */
@RunWith(AndroidJUnit4::class)
class MlKitTranslationDeviceTest {

    @Test
    fun japaneseShortDialog_rawModelOutput() = runBlocking {
        val provider = MlKitTranslationProvider("ja", "id")
        try {
            assertTrue("model ja tidak siap (perlu internet sekali)", provider.prepare())
            val requests = listOf(
                TranslationRequest("a", "まって"),
                TranslationRequest("b", "だめだ"),
                TranslationRequest("c", "待って！"),
                TranslationRequest("d", "どこ？")
            )
            val result = provider.translate(requests)
            assertTrue("terjemahan gagal: $result", result is TranslateResult.Success)
            val texts = (result as TranslateResult.Success).texts
            requests.forEach { request ->
                println("RAW ja>id '${request.text}' => '${texts[request.id]}'")
            }
            // Model harus benar-benar menghasilkan teks untuk tiap dialog.
            assertTrue(texts.values.all { it.isNotBlank() })
        } finally {
            provider.close()
        }
    }

    @Test
    fun autoDetectsJapanesePerRequest() = runBlocking {
        val provider = MlKitTranslationProvider("auto", "id")
        try {
            val result = provider.translate(
                listOf(
                    TranslationRequest("ja", "だめだ"),
                    TranslationRequest("en", "WAIT FOR ME!")
                )
            )
            assertTrue(result is TranslateResult.Success)
            val texts = (result as TranslateResult.Success).texts
            println("AUTO ja => '${texts["ja"]}' en => '${texts["en"]}'")
            assertTrue(texts["ja"].orEmpty().isNotBlank())
            assertTrue(texts["en"].orEmpty().isNotBlank())
        } finally {
            provider.close()
        }
    }
}

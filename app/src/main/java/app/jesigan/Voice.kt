package app.jesigan

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 음성 출력. 지금은 기기 내장 TextToSpeech(온디바이스)를 쓰고,
 * Pleos Playground 클라이언트 ID/시크릿 발급 후 Pleos TextToSpeech · SpeechToText SDK로 교체합니다.
 * (규정: 마이크 음성을 외부 음성 인식 서비스로 보내지 않음)
 */
class Voice(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.KOREAN
    }

    fun say(text: String) {
        SdkLog.add("TTS", "speak", text)
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jesigan")
    }

    fun shutdown() = tts.shutdown()
}

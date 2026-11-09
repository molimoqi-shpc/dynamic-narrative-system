package com.bs.narrative;
import com.bs.narrative.service.PlayerSpeech;
import com.bs.narrative.llm.LLMResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlayerSpeechTest {
    LLMResponse model(String text){String j="{\"spokenText\":\""+text+"\"}";return new LLMResponse(j,j,0,0,0,0);}
    @Test void spokenQuestionExcludesPrivateSuffix(){assertEquals("为何守着旧船",PlayerSpeech.extract(model("为何守着旧船"),"我问为何守着旧船，心里盘算绕路。"));}
    @Test void unspokenThoughtIsNeverForwarded(){assertEquals("",PlayerSpeech.extract(model("等他离开再动手"),"我心想等他离开再动手。"));}
    @Test void fabricatedOrWholeMixedTextIsRejected(){assertEquals("",PlayerSpeech.extract(model("交出宝物"),"我问可以过桥吗？"));assertEquals("",PlayerSpeech.extract(model("我问怎么过桥，心里盘算动手"),"我问怎么过桥，心里盘算动手"));}
    @Test void imaginaryQuotedSpeechIsNotAnUtterance(){assertEquals("",PlayerSpeech.extract(model("交出宝物"),"我心想他会说：“交出宝物”。"));}
    @Test void explicitQuotationCanIntentionallyAnnounceFutureIntent(){assertEquals("我准备离开",PlayerSpeech.extract(model("我准备离开"),"我说：“我准备离开”，暗自观察对方。"));}
}

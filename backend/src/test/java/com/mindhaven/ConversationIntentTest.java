package com.mindhaven;

import com.mindhaven.service.chat.ConversationIntent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ConversationIntentTest {
    private final ConversationIntent intents = new ConversationIntent();

    @Test
    void ambiguousTopicWithoutHistoryIsClarified() {
        var result = intents.decide("吃饭。", false);
        assertThat(result.mode()).isEqualTo("clarification");
        assertThat(result.retrieve()).isFalse();
    }

    @Test
    void briefContextualAnswerKeepsHistoryWithoutNewKnowledge() {
        var result = intents.decide("吃饭", true);
        assertThat(result.mode()).isEqualTo("conversation");
        assertThat(result.retrieve()).isFalse();
    }

    @Test
    void shortSymptomsAreNotMistakenForSmallTalk() {
        assertThat(intents.decide("失眠", false).retrieve()).isTrue();
        assertThat(intents.decide("最近吃不下饭，怎么办？", false).retrieve()).isTrue();
        assertThat(intents.decide("你好，我不想活了", false).retrieve()).isTrue();
    }

    @Test
    void referencesNeedHistoryAndGreetingsDoNotNeedRetrieval() {
        assertThat(intents.decide("那怎么办？", false).mode()).isEqualTo("clarification");
        assertThat(intents.decide("那怎么办？", true).retrieve()).isTrue();
        assertThat(intents.decide("你好！", false).mode()).isEqualTo("direct");
    }
}

package com.jzb.chatbot.voice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class XiaozhiStreamTextProcessingTest {

    @Test
    void shouldExtractResponsesDeltaTextAcrossSseChunks() {
        var extractor = new XiaozhiHermesStreamTextExtractor();

        assertThat(extractor.accept("event: response.output_text.delta\ndata: {\"delta\":\"你\"}")).isEmpty();
        assertThat(extractor.accept("\n\n")).containsExactly("你");
    }

    @Test
    void shouldExtractCompatAnswerText() {
        var extractor = new XiaozhiHermesStreamTextExtractor();

        assertThat(extractor.accept("event: message\ndata: {\"answer\":\"你好\"}\n\n")).containsExactly("你好");
    }

    @Test
    void shouldSegmentCompleteChineseSentencesAndFlushTail() {
        var segmenter = new XiaozhiSentenceSegmenter();

        assertThat(segmenter.accept("第一句内容很完整。第二句")).containsExactly("第一句内容很完整。");
        assertThat(segmenter.flush()).isEqualTo("第二句");
    }
}

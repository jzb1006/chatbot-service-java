import unittest
from argparse import Namespace
from unittest.mock import patch

import xiaozhi_ws_smoke


class XiaozhiWebSocketSmokeTest(unittest.TestCase):

    def test_parse_args_accepts_text_audio_input(self):
        args = xiaozhi_ws_smoke.parse_args([
            "--url",
            "ws://127.0.0.1:8766/xiaozhi/v1",
            "--input-text",
            "你好",
            "--send-interval",
            "0.02",
        ])

        self.assertEqual("你好", args.input_text)
        self.assertEqual(0.02, args.send_interval)

    def test_resolve_audio_frames_uses_tts_input_when_text_is_present(self):
        args = Namespace(input_text="你好")

        with patch.object(xiaozhi_ws_smoke, "synthesize_input_text_to_opus_frames", return_value=[b"a", b"b"]) as synthesize:
            frames = xiaozhi_ws_smoke.resolve_audio_frames(args)

        self.assertEqual([b"a", b"b"], frames)
        synthesize.assert_called_once_with("你好")

    def test_smoke_stats_reports_sentence_duplicates(self):
        stats = xiaozhi_ws_smoke.SmokeStats()
        stats.tts_sentences.extend(["第一句", "第二句", "第一句"])

        fields = dict(stats.fields())

        self.assertEqual("3", fields["tts_sentence_count"])
        self.assertEqual("1", fields["tts_duplicate_sentence_count"])


if __name__ == "__main__":
    unittest.main()

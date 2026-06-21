#!/usr/bin/env python3
"""Minimal Sherpa-ONNX websocket ASR server."""

import argparse
import asyncio
import json
import logging
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import sherpa_onnx
import websockets


def create_recognizer(args):
    return sherpa_onnx.OnlineRecognizer.from_paraformer(
        tokens=args.tokens,
        encoder=args.encoder,
        decoder=args.decoder,
        num_threads=args.num_threads,
        sample_rate=args.sample_rate,
        feature_dim=args.feat_dim,
        decoding_method=args.decoding_method,
        enable_endpoint_detection=args.use_endpoint,
        rule1_min_trailing_silence=args.rule1_min_trailing_silence,
        rule2_min_trailing_silence=args.rule2_min_trailing_silence,
        rule3_min_utterance_length=args.rule3_min_utterance_length,
        provider="cpu",
    )


class SherpaAsrServer:
    def __init__(self, recognizer, args):
        self.recognizer = recognizer
        self.sample_rate = int(recognizer.config.feat_config.sampling_rate)
        self.max_active_connections = args.max_active_connections
        self.active_connections = 0
        self.nn_pool = ThreadPoolExecutor(max_workers=args.nn_pool_size, thread_name_prefix="sherpa-nn")

    async def handle(self, websocket):
        if self.active_connections >= self.max_active_connections:
            await websocket.close(code=1013, reason="server busy")
            return
        self.active_connections += 1
        try:
            await self.handle_utterance(websocket)
        except websockets.exceptions.ConnectionClosed:
            logging.info("client disconnected")
        finally:
            self.active_connections -= 1

    async def handle_utterance(self, websocket):
        stream = self.recognizer.create_stream()
        segment = 0
        async for message in websocket:
            if message == "Done":
                break
            samples = np.frombuffer(message, dtype=np.float32)
            stream.accept_waveform(sample_rate=self.sample_rate, waveform=samples)
            while self.recognizer.is_ready(stream):
                await self.decode(stream)
                text = self.recognizer.get_result(stream)
                await websocket.send(json.dumps({"text": text, "segment": segment}, ensure_ascii=False))
                if self.recognizer.is_endpoint(stream):
                    self.recognizer.reset(stream)
                    segment += 1

        tail_padding = np.zeros(int(self.sample_rate * 0.3), dtype=np.float32)
        stream.accept_waveform(sample_rate=self.sample_rate, waveform=tail_padding)
        stream.input_finished()
        while self.recognizer.is_ready(stream):
            await self.decode(stream)
        text = self.recognizer.get_result(stream)
        await websocket.send(json.dumps({"text": text, "segment": segment}, ensure_ascii=False))
        await websocket.send("Done!")

    async def decode(self, stream):
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(self.nn_pool, self.recognizer.decode_streams, [stream])


def get_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=os.getenv("SHERPA_ASR_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("SHERPA_ASR_PORT", "6006")))
    parser.add_argument("--model-dir", default=os.getenv("SHERPA_ASR_MODEL_DIR", "/models/sherpa-onnx-streaming-paraformer-bilingual-zh-en"))
    parser.add_argument("--sample-rate", type=int, default=int(os.getenv("SHERPA_ASR_SAMPLE_RATE", "16000")))
    parser.add_argument("--feat-dim", type=int, default=int(os.getenv("SHERPA_ASR_FEAT_DIM", "80")))
    parser.add_argument("--num-threads", type=int, default=int(os.getenv("SHERPA_ASR_NUM_THREADS", "2")))
    parser.add_argument("--nn-pool-size", type=int, default=int(os.getenv("SHERPA_ASR_NN_POOL_SIZE", "1")))
    parser.add_argument("--max-active-connections", type=int, default=int(os.getenv("SHERPA_ASR_MAX_ACTIVE_CONNECTIONS", "4")))
    parser.add_argument("--decoding-method", default=os.getenv("SHERPA_ASR_DECODING_METHOD", "greedy_search"))
    parser.add_argument("--use-endpoint", type=int, default=int(os.getenv("SHERPA_ASR_USE_ENDPOINT", "1")))
    parser.add_argument("--rule1-min-trailing-silence", type=float, default=float(os.getenv("SHERPA_ASR_RULE1_MIN_TRAILING_SILENCE", "2.4")))
    parser.add_argument("--rule2-min-trailing-silence", type=float, default=float(os.getenv("SHERPA_ASR_RULE2_MIN_TRAILING_SILENCE", "1.2")))
    parser.add_argument("--rule3-min-utterance-length", type=float, default=float(os.getenv("SHERPA_ASR_RULE3_MIN_UTTERANCE_LENGTH", "20")))
    args = parser.parse_args()
    model_dir = Path(args.model_dir)
    args.tokens = str(model_dir / "tokens.txt")
    args.encoder = str(model_dir / "encoder.int8.onnx")
    args.decoder = str(model_dir / "decoder.int8.onnx")
    return args


async def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = get_args()
    for path in (args.tokens, args.encoder, args.decoder):
        if not Path(path).is_file():
            raise FileNotFoundError(path)
    recognizer = create_recognizer(args)
    server = SherpaAsrServer(recognizer, args)
    logging.info("starting sherpa asr websocket server on %s:%s", args.host, args.port)
    async with websockets.serve(server.handle, args.host, args.port, max_size=1 << 20, max_queue=32):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())

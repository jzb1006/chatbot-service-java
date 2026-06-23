# Xiaozhi S3 Audio Stop Follow-up

Date: 2026-06-23

## Current Status

- Firmware/device:
  - ESP32-S3 N16R8 board is connected to the server successfully.
  - Device ID seen by the server: `1c:db:d4:64:cc:54`.
  - Wake word upload works. Server logs show `xiaozhi wake word detected`.
  - Microphone audio upload works. Server logs show about 999 frames per minute and high `peakRms`.
  - ASR can recognize speech after the server eventually ends the audio stream.

- Server:
  - GitHub `main` is pushed to commit `d77c10c Add Xiaozhi auto-listen timeout diagnostics`.
  - Remote `device_gateway` has been deployed with the latest jar.
  - Health check returned `{"status":"UP"}`.
  - The server is currently using streaming ASR with sherpa-onnx.

## Problem Observed Tonight

The server is replying, but it replies only after the 60 second max-duration fallback.

Example logs:

```text
22:56:44 wake word detected: 你好小智
22:56:45 listen started, mode=auto
22:57:45 auto-stop detected, reason=MAX_DURATION_REACHED, frames=998, audioMillis=59880, peakRms=0.4479, speechStarted=true
22:58:00 streaming conversation turn, userText=现在是几点呢
```

Second turn:

```text
22:58:02 listen started, mode=auto
22:59:02 auto-stop detected, reason=MAX_DURATION_REACHED, frames=999, audioMillis=59940, peakRms=0.6547, speechStarted=true
22:59:15 streaming conversation turn, userText=有没有有有很多这些
```

Conclusion:

- The device is not silent from the server's perspective.
- Audio frames are continuously arriving.
- Current RMS-based auto-stop never sees enough silence, so it waits for `max-duration`.
- This is not mainly a token/auth/server-address problem.
- This is not mainly a "server did not generate TTS" problem.
- The urgent issue is endpointing: detecting when the user has stopped speaking.

## Immediate Fix Tomorrow

1. Lower `chatbot.voice.auto-stop.max-duration` for the deployed service.

Recommended temporary value:

```env
CHATBOT_VOICE_AUTO_STOP_MAX_DURATION=5s
```

Optional:

```env
CHATBOT_VOICE_AUTO_STOP_NO_SPEECH_TIMEOUT=5s
CHATBOT_VOICE_AUTO_STOP_SILENCE_DURATION=1200ms
CHATBOT_VOICE_AUTO_STOP_SPEECH_RMS_THRESHOLD=0.08
```

Why:

- This will prevent the user from waiting 60 seconds.
- It is not a perfect endpoint detector, but it makes testing usable again.

2. Restart `device_gateway`.

Remote commands:

```bash
ssh -i C:\Users\msi\.ssh\id_ed25519_chatbot_service_java root@203.195.202.54
docker restart device_gateway
docker logs --since 2m device_gateway -f
```

3. Test one short utterance:

Say:

```text
你好小智
现在几点
```

Expected after temporary fix:

- Server should end the turn around 5 seconds instead of 60 seconds.
- ASR/TTS should appear in logs soon after.

## Proper Fix

Implement server-side VAD endpointing similar to `joey-zhou/xiaozhi-esp32-server-java`.

Reference repo:

```text
https://github.com/joey-zhou/xiaozhi-esp32-server-java
```

Important files:

```text
xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/audio/VadService.java
xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/audio/vad/SileroVadModel.java
xiaozhi-dialogue/src/main/java/com/xiaozhi/dialogue/DialogueService.java
xiaozhi-dialogue/src/main/java/com/xiaozhi/communication/common/MessageHandler.java
```

Their approach:

- Device sends Opus frames continuously.
- Server decodes Opus to PCM.
- Server runs Silero VAD plus energy threshold.
- `SPEECH_START` starts STT streaming.
- `SPEECH_CONTINUE` sends PCM/audio to STT.
- `SPEECH_END` completes the STT stream.
- Default silence endpoint is around 800 ms, not fixed 4.5 seconds.

Suggested design for this project:

1. Add a `XiaozhiVadEndpoint` beside `XiaozhiAutoListenEndpoint`.
2. Start with a simple adaptive noise floor if Silero is too large for the first pass.
3. Prefer Silero VAD for the final version.
4. Keep the current max-duration fallback as a safety net.
5. Log these metrics per turn:
   - `reason`
   - `frames`
   - `audioMillis`
   - `peakRms`
   - `speechStarted`
   - average or baseline RMS
   - silence duration before endpoint

## Current Code Changes Already Done

Commits:

```text
5906962 feat: add auto-stop for Xiaozhi listening
eb2a766 Fix auto-stop for streaming Xiaozhi ASR
d77c10c Add Xiaozhi auto-listen timeout diagnostics
```

Latest behavior added:

- Streaming ASR auto-stop is supported.
- Auto listen now has:
  - no-speech timeout
  - max-duration timeout
  - diagnostics for frames, audio duration, peak RMS and speech state
- Tests passed before deployment:

```text
Tests run: 121, Failures: 0, Errors: 0, Skipped: 0
```

## Useful Commands

Check service health:

```bash
curl http://203.195.202.54:8766/actuator/health
```

Watch latest device logs:

```bash
ssh -i C:\Users\msi\.ssh\id_ed25519_chatbot_service_java root@203.195.202.54 "docker logs --since 5m device_gateway 2>&1 | grep -E 'wake word|listen started|auto-stop|streaming conversation|turn completed|asr_empty|deviceId=1c:db:d4:64:cc:54' | tail -n 200"
```

Check ASR container logs:

```bash
ssh -i C:\Users\msi\.ssh\id_ed25519_chatbot_service_java root@203.195.202.54 "docker logs --since 5m sherpa_asr 2>&1 | tail -n 120"
```

Build server jar locally:

```powershell
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.1\plugins\maven\lib\maven3\bin\mvn.cmd' -pl chatbot-bootstrap -am -DskipTests package
```

Deploy jar:

```powershell
scp -i C:\Users\msi\.ssh\id_ed25519_chatbot_service_java -o BatchMode=yes -o StrictHostKeyChecking=no chatbot-bootstrap\target\chatbot-bootstrap-0.0.1-SNAPSHOT.jar root@203.195.202.54:/tmp/chatbot-service.jar
ssh -i C:\Users\msi\.ssh\id_ed25519_chatbot_service_java -o BatchMode=yes -o StrictHostKeyChecking=no root@203.195.202.54 "docker cp /tmp/chatbot-service.jar device_gateway:/app/chatbot-service.jar && docker restart device_gateway"
```

## Tomorrow's Priority Order

1. Set temporary `max-duration=5s` on remote and restart.
2. Confirm the user gets responses within 5-8 seconds.
3. If audio playback is still not heard, inspect device serial logs and I2S speaker pins.
4. Implement proper VAD endpointing.
5. Re-test with short, medium and long utterances.
6. Commit and deploy the VAD fix.


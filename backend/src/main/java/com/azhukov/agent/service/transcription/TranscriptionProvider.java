package com.azhukov.agent.service.transcription;

/**
 * Provider interface for audio transcription (speech-to-text).
 */
public interface TranscriptionProvider {

    /**
     * Transcribe an audio file to text.
     *
     * @param audioFile the audio file bytes (OGG/MP3/WAV)
     * @return the transcribed text
     */
    String transcribe(byte[] audioFile);
}
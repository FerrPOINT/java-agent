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

    /**
     * Transcribe an audio file with caller-supplied multipart metadata.
     *
     * @param audioFile the audio file bytes
     * @param filename original filename, used for provider container detection
     * @param contentType original content type, used when valid
     * @return the transcribed text
     */
    default String transcribe(byte[] audioFile, String filename, String contentType) {
        return transcribe(audioFile);
    }
}

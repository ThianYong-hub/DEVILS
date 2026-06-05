package com.devils.addon.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

final class JoinSoundOggPlayback {
    private JoinSoundOggPlayback() {
    }

    static void playAsync(Path filePath, int localOggVolumePercent, boolean diagnostics) {
        JoinSoundPlayer.AUDIO_EXECUTOR.execute(() -> {
            JoinSoundPlaybackStatus status = playOnAudioThread(filePath, localOggVolumePercent, diagnostics);
            if (status != JoinSoundPlaybackStatus.OK) {
                JoinSoundPlayer.LOG.warn(
                    "Failed to play local join sound '{}', status={}. Falling back to '{}'.",
                    filePath,
                    status,
                    JoinSoundPlayer.FALLBACK_SOUND_ID
                );
                JoinSoundPlayer.playSoundId(JoinSoundPlayer.FALLBACK_SOUND_ID);
            }

            if (diagnostics) {
                JoinSoundPlayer.LOG.info("Diagnostic playback finished for local sound '{}'.", filePath);
            }
        });
    }

    static JoinSoundPlaybackStatus playBlocking(Path filePath, int localOggVolumePercent, boolean diagnostics) {
        Future<JoinSoundPlaybackStatus> future = JoinSoundPlayer.AUDIO_EXECUTOR.submit(
            () -> playOnAudioThread(filePath, localOggVolumePercent, diagnostics)
        );
        try {
            JoinSoundPlaybackStatus status = future.get();
            if (diagnostics) {
                JoinSoundPlayer.LOG.info(
                    "Blocking diagnostic playback finished for local sound '{}', status={}",
                    filePath,
                    status
                );
            }
            return status;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            JoinSoundPlayer.LOG.warn("Audio playback interrupted for '{}'.", filePath, e);
            return JoinSoundPlaybackStatus.DECODE_ERROR;
        } catch (ExecutionException e) {
            JoinSoundPlayer.LOG.warn("Audio playback execution failed for '{}'.", filePath, e.getCause());
            return JoinSoundPlaybackStatus.DECODE_ERROR;
        }
    }

    private static JoinSoundPlaybackStatus playOnAudioThread(Path filePath, int localOggVolumePercent, boolean diagnostics) {
        Thread thread = Thread.currentThread();
        ClassLoader previousClassLoader = thread.getContextClassLoader();
        ClassLoader targetClassLoader = JoinSoundPlayer.class.getClassLoader();
        int sanitizedVolumePercent = JoinSoundPlayer.sanitizeLocalOggVolumePercent(localOggVolumePercent);

        thread.setContextClassLoader(targetClassLoader);

        if (diagnostics) {
            JoinSoundPlayer.LOG.info(
                "Audio thread='{}', contextClassLoader='{}', playerClassLoader='{}'.",
                thread.getName(),
                thread.getContextClassLoader(),
                targetClassLoader
            );
        }

        try (AudioInputStream inputStream = openPcmAudioStream(filePath)) {
            Clip clip = openClip(inputStream.getFormat(), diagnostics);
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP || event.getType() == LineEvent.Type.CLOSE) {
                    clip.close();
                }
            });
            clip.open(inputStream);
            applyLocalOggVolume(clip, sanitizedVolumePercent, diagnostics);
            clip.start();
            return JoinSoundPlaybackStatus.OK;
        } catch (UnsupportedAudioFileException e) {
            JoinSoundPlayer.LOG.warn("Unsupported local sound format '{}'.", filePath, e);
            return JoinSoundPlaybackStatus.UNSUPPORTED_FORMAT;
        } catch (LineUnavailableException e) {
            JoinSoundPlayer.LOG.warn("Audio line unavailable for local sound '{}'.", filePath, e);
            return JoinSoundPlaybackStatus.LINE_UNAVAILABLE;
        } catch (IOException | IllegalArgumentException e) {
            JoinSoundPlayer.LOG.warn("Decode/open failure for local sound '{}'.", filePath, e);
            return JoinSoundPlaybackStatus.DECODE_ERROR;
        } catch (Exception e) {
            JoinSoundPlayer.LOG.warn("Unexpected playback error for local sound '{}'.", filePath, e);
            return JoinSoundPlaybackStatus.DECODE_ERROR;
        } finally {
            thread.setContextClassLoader(previousClassLoader);
        }
    }

    private static void applyLocalOggVolume(Clip clip, int volumePercent, boolean diagnostics) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            if (diagnostics) JoinSoundPlayer.LOG.info("MASTER_GAIN control is not supported by clip. Keeping default gain.");
            return;
        }

        try {
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float min = control.getMinimum();
            float max = control.getMaximum();

            float gain = volumePercent <= 0 ? min : (float) (20.0 * Math.log10(volumePercent / 100.0));
            if (gain < min) gain = min;
            if (gain > max) gain = max;

            control.setValue(gain);

            if (diagnostics) {
                JoinSoundPlayer.LOG.info(
                    "Applied local .ogg volume: {}% -> {} dB (range {}..{}).",
                    volumePercent,
                    gain,
                    min,
                    max
                );
            }
        } catch (Exception e) {
            JoinSoundPlayer.LOG.warn("Failed to apply local .ogg volume {}%. Continuing with default gain.", volumePercent, e);
        }
    }

    private static Clip openClip(AudioFormat format, boolean diagnostics) throws LineUnavailableException {
        DataLine.Info lineInfo = new DataLine.Info(Clip.class, format);

        try {
            return (Clip) AudioSystem.getLine(lineInfo);
        } catch (LineUnavailableException | IllegalArgumentException primary) {
            if (diagnostics) {
                JoinSoundPlayer.LOG.warn("Clip open via DataLine.Info failed, trying AudioSystem.getClip(). format={}", format, primary);
            }

            try {
                return AudioSystem.getClip();
            } catch (LineUnavailableException fallbackLine) {
                fallbackLine.addSuppressed(primary);
                throw fallbackLine;
            } catch (IllegalArgumentException fallbackInvalid) {
                LineUnavailableException wrapped = new LineUnavailableException("Unable to obtain clip line.");
                wrapped.addSuppressed(primary);
                wrapped.addSuppressed(fallbackInvalid);
                throw wrapped;
            }
        }
    }

    private static AudioInputStream openPcmAudioStream(Path filePath) throws UnsupportedAudioFileException, IOException {
        AudioInputStream encoded = AudioSystem.getAudioInputStream(filePath.toFile());
        AudioFormat source = encoded.getFormat();

        AudioFormat pcm = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            source.getSampleRate(),
            16,
            source.getChannels(),
            source.getChannels() * 2,
            source.getSampleRate(),
            false
        );

        if (!AudioSystem.isConversionSupported(pcm, source)) {
            encoded.close();
            throw new UnsupportedAudioFileException("PCM conversion is not supported for source format: " + source);
        }

        return AudioSystem.getAudioInputStream(pcm, encoded);
    }
}

enum JoinSoundPlaybackStatus {
    OK,
    FILE_NOT_FOUND,
    UNSUPPORTED_FORMAT,
    LINE_UNAVAILABLE,
    DECODE_ERROR,
    FALLBACK_USED
}

record JoinSoundPlaybackDiagnosticResult(
    JoinSoundPlaybackStatus status,
    JoinSoundResolvedSound resolved,
    boolean fallbackUsed,
    String message
) {
    boolean isOk() {
        return status == JoinSoundPlaybackStatus.OK;
    }
}

record JoinSoundResolvedSound(JoinSoundResolvedType type, Path filePath, Identifier soundId) {
    static JoinSoundResolvedSound fromFile(Path path) {
        return new JoinSoundResolvedSound(JoinSoundResolvedType.FILE, path, null);
    }

    static JoinSoundResolvedSound fromSoundId(Identifier id) {
        return new JoinSoundResolvedSound(JoinSoundResolvedType.SOUND_ID, null, id);
    }
}

enum JoinSoundResolvedType {
    FILE,
    SOUND_ID
}

record JoinSoundLocalResolution(Path path, JoinSoundPlaybackStatus status, String message) {
}

record JoinSoundResolvedPlayback(
    JoinSoundResolvedSound sound,
    boolean fallbackUsed,
    JoinSoundPlaybackStatus primaryStatus,
    String primaryMessage
) {
}

record JoinSoundRuleResolution(JoinSoundResolvedSound sound, JoinSoundPlaybackStatus status, String message) {
}



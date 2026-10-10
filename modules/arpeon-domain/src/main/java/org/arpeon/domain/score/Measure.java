package org.arpeon.domain.score;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.arpeon.domain.time.ScoreOffset;
import org.arpeon.domain.time.Tempo;
import org.arpeon.domain.time.TimeSignature;

public record Measure(
    String number,
    TimeSignature timeSignature,
    Optional<Tempo> tempo,
    List<ScoreEvent> events,
    boolean pickup,
    List<Voice> voices) {
  public Measure(
      String number,
      TimeSignature timeSignature,
      Optional<Tempo> tempo,
      List<ScoreEvent> events,
      boolean pickup) {
    this(
        number,
        timeSignature,
        tempo,
        List.copyOf(events),
        pickup,
        List.of(Voice.sequential("1", events)));
  }

  public static Measure withVoices(
      String number,
      TimeSignature timeSignature,
      Optional<Tempo> tempo,
      List<Voice> voices,
      boolean pickup) {
    if (voices.isEmpty()) throw new IllegalArgumentException("Measure must contain voices");
    Voice primary =
        voices.stream()
            .filter(voice -> voice.id().equals("1"))
            .findFirst()
            .orElse(voices.getFirst());
    return new Measure(
        number,
        timeSignature,
        tempo,
        primary.events().stream().map(TimedEvent::event).toList(),
        pickup,
        voices);
  }

  public Measure {
    if (number == null || number.isBlank())
      throw new IllegalArgumentException("Measure number required");
    Objects.requireNonNull(timeSignature);
    Objects.requireNonNull(tempo);
    events = List.copyOf(events);
    voices = List.copyOf(voices);
    if (events.isEmpty()) throw new IllegalArgumentException("Measure must contain events");
    if (voices.isEmpty()) throw new IllegalArgumentException("Measure must contain voices");
    Set<String> voiceIds = voices.stream().map(Voice::id).collect(Collectors.toSet());
    if (voiceIds.size() != voices.size()) throw new IllegalArgumentException("Duplicate voice IDs");
    Voice primary =
        voices.stream()
            .filter(voice -> voice.id().equals("1"))
            .findFirst()
            .orElse(voices.getFirst());
    if (!primary.events().stream().map(TimedEvent::event).toList().equals(events))
      throw new IllegalArgumentException("Legacy events must mirror the primary voice");
    ScoreOffset limit =
        new ScoreOffset(
            timeSignature.measureDuration().numerator(),
            timeSignature.measureDuration().denominator());
    ScoreOffset latest = ScoreOffset.zero();
    for (Voice voice : voices) {
      if (voice.end().compareTo(limit) > 0)
        throw new IllegalArgumentException("Voice exceeds measure duration");
      if (voice.end().compareTo(latest) > 0) latest = voice.end();
    }
    int comparison = latest.compareTo(limit);
    if (!pickup && voices.size() == 1 && !voices.getFirst().explicitlyTimed() && comparison != 0)
      throw new IllegalArgumentException("Measure duration does not match meter");
  }
}

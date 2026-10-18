package org.arpeon.musicxml;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.domain.score.Chord;
import org.arpeon.domain.score.MusicalKey;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.ScoreOffset;

/** Writes the supported canonical MusicXML subset without changing the user's source file. */
public final class MusicXmlExporter {
  private static final int DIVISIONS = 480;

  public String write(Score score) {
    return write(score, null);
  }

  public String write(Score score, String keyId) {
    return write(score, keyId, Map.of());
  }

  public String write(Score score, String keyId, Map<String, InstrumentProfile> instruments) {
    MusicalKey key = keyId == null ? null : MusicalKey.get(keyId);
    var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
    xml.append("<score-partwise version=\"4.0\">\n");
    if (!score.title().isBlank())
      xml.append("<work><work-title>")
          .append(escape(score.title()))
          .append("</work-title></work>\n");
    if (!score.composer().isBlank())
      xml.append("<identification><creator type=\"composer\">")
          .append(escape(score.composer()))
          .append("</creator></identification>\n");
    xml.append("<part-list>\n");
    for (int p = 0; p < score.parts().size(); p++) {
      var part = score.parts().get(p);
      xml.append("<score-part id=\"")
          .append(escape(part.id()))
          .append("\"><part-name>")
          .append(escape(part.name()))
          .append("</part-name>");
      var profile = instruments.get(part.id());
      if (profile != null && !profile.id().equals("concert")) {
        String instrumentId = "I" + (p + 1);
        xml.append("<score-instrument id=\"")
            .append(instrumentId)
            .append("\"><instrument-name>")
            .append(escape(profile.displayName()))
            .append("</instrument-name></score-instrument>")
            .append("<midi-instrument id=\"")
            .append(instrumentId)
            .append("\"><midi-program>")
            .append(profile.midiProgram() + 1)
            .append("</midi-program></midi-instrument>");
      }
      xml.append("</score-part>\n");
    }
    xml.append("</part-list>\n");
    for (var part : score.parts()) {
      var profile = instruments.get(part.id());
      MusicalKey writtenKey =
          key == null || profile == null
              ? key
              : key.transpose(-profile.soundingTranspositionSemitones());
      xml.append("<part id=\"").append(escape(part.id())).append("\">\n");
      for (int index = 0; index < part.measures().size(); index++) {
        var measure = part.measures().get(index);
        xml.append("<measure number=\"").append(escape(measure.number())).append('"');
        if (measure.pickup()) xml.append(" implicit=\"yes\"");
        xml.append(">\n");
        appendBarline(xml, score, index, "left");
        if (index == 0
            || part.clefs().containsKey(index)
            || !measure.timeSignature().equals(part.measures().get(index - 1).timeSignature())) {
          xml.append("<attributes><divisions>").append(DIVISIONS).append("</divisions>");
          if (index == 0 && writtenKey != null)
            xml.append("<key><fifths>")
                .append(writtenKey.fifths())
                .append("</fifths><mode>")
                .append(writtenKey.minor() ? "minor" : "major")
                .append("</mode></key>");
          xml.append("<time><beats>")
              .append(measure.timeSignature().beats())
              .append("</beats><beat-type>")
              .append(measure.timeSignature().beatUnit())
              .append("</beat-type></time>");
          if (index == 0 && profile != null && profile.soundingTranspositionSemitones() != 0) {
            xml.append("<transpose><diatonic>")
                .append(org.arpeon.domain.instrument.InstrumentCatalog.diatonic(profile))
                .append("</diatonic><chromatic>")
                .append(profile.soundingTranspositionSemitones())
                .append("</chromatic></transpose>");
          }
          var clef = part.clefs().get(index);
          if (clef != null) {
            xml.append("<clef><sign>")
                .append(clef.sign())
                .append("</sign><line>")
                .append(clef.line())
                .append("</line>");
            if (clef.octaveChange() != 0)
              xml.append("<clef-octave-change>")
                  .append(clef.octaveChange())
                  .append("</clef-octave-change>");
            xml.append("</clef>");
          }
          xml.append("</attributes>\n");
        }
        measure
            .tempo()
            .ifPresent(
                tempo ->
                    xml.append("<sound tempo=\"")
                        .append(tempo.quarterNotesPerMinute())
                        .append("\"/>\n"));
        long cursor = 0;
        boolean firstVoice = true;
        for (var voice : measure.voices()) {
          if (!firstVoice && cursor > 0) {
            xml.append("<backup><duration>").append(cursor).append("</duration></backup>\n");
            cursor = 0;
          }
          firstVoice = false;
          for (var timed : voice.events()) {
            long onset = ticks(timed.onset());
            if (onset < cursor) throw new IllegalArgumentException("Voice events overlap");
            if (onset > cursor)
              xml.append("<forward><duration>")
                  .append(onset - cursor)
                  .append("</duration></forward>\n");
            appendEvent(xml, timed.event(), voice.id());
            cursor = Math.addExact(onset, ticks(timed.event().duration()));
          }
        }
        if (measure.voices().size() == 1 && measure.voices().getFirst().explicitlyTimed()) {
          long measureEnd =
              ticks(
                  new ScoreOffset(
                      measure.timeSignature().measureDuration().numerator(),
                      measure.timeSignature().measureDuration().denominator()));
          if (cursor < measureEnd)
            xml.append("<forward><duration>")
                .append(measureEnd - cursor)
                .append("</duration></forward>\n");
        }
        appendBarline(xml, score, index, "right");
        xml.append("</measure>\n");
      }
      xml.append("</part>\n");
    }
    xml.append("</score-partwise>\n");
    if (xml.toString().getBytes(StandardCharsets.UTF_8).length > MusicXmlImporter.MAX_BYTES)
      throw new IllegalArgumentException("Edited MusicXML exceeds the 128 MiB limit");
    return xml.toString();
  }

  private static long ticks(Duration duration) {
    var value =
        BigInteger.valueOf(duration.numerator()).multiply(BigInteger.valueOf(4L * DIVISIONS));
    var result = value.divideAndRemainder(BigInteger.valueOf(duration.denominator()));
    if (result[1].signum() != 0)
      throw new IllegalArgumentException("Duration cannot be exported exactly");
    return result[0].longValueExact();
  }

  private static void appendBarline(
      StringBuilder xml, Score score, int measureIndex, String location) {
    var markers = new StringBuilder();
    for (var ending : score.endingRegions()) {
      if (location.equals("left") && ending.startMeasure() == measureIndex)
        markers
            .append("<ending number=\"")
            .append(
                ending.passes().stream()
                    .sorted()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")))
            .append("\" type=\"start\"/>");
      if (location.equals("right") && ending.endMeasure() == measureIndex)
        markers
            .append("<ending number=\"")
            .append(
                ending.passes().stream()
                    .sorted()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(",")))
            .append("\" type=\"stop\"/>");
    }
    for (var repeat : score.repeatRegions()) {
      if (location.equals("left") && repeat.startMeasure() == measureIndex)
        markers.append("<repeat direction=\"forward\"/>");
      if (location.equals("right") && repeat.endMeasure() == measureIndex)
        markers
            .append("<repeat direction=\"backward\" times=\"")
            .append(repeat.passes())
            .append("\"/>");
    }
    if (!markers.isEmpty())
      xml.append("<barline location=\"")
          .append(location)
          .append("\">")
          .append(markers)
          .append("</barline>\n");
  }

  private static long ticks(ScoreOffset offset) {
    var value = BigInteger.valueOf(offset.numerator()).multiply(BigInteger.valueOf(4L * DIVISIONS));
    var result = value.divideAndRemainder(BigInteger.valueOf(offset.denominator()));
    if (result[1].signum() != 0)
      throw new IllegalArgumentException("Voice onset cannot be exported exactly");
    return result[0].longValueExact();
  }

  private static void appendEvent(
      StringBuilder xml, org.arpeon.domain.score.ScoreEvent event, String voiceId) {
    if (event instanceof Chord chord) {
      for (int head = 0; head < chord.notes().size(); head++)
        appendNote(xml, chord.notes().get(head), head > 0, voiceId);
    } else if (event instanceof Note note) appendNote(xml, note, false, voiceId);
    else {
      xml.append("<note><rest/><duration>")
          .append(ticks(event.duration()))
          .append("</duration><voice>")
          .append(escape(voiceId))
          .append("</voice>");
      appendType(xml, event.duration(), event.tuplet());
      if (event.tuplet() != null && (event.tuplet().start() || event.tuplet().stop())) {
        xml.append("<notations>");
        appendTupletMarkers(xml, event.tuplet());
        xml.append("</notations>");
      }
      xml.append("</note>\n");
    }
  }

  private static void appendNote(StringBuilder xml, Note note, boolean chordHead, String voiceId) {
    xml.append("<note>");
    if (chordHead) xml.append("<chord/>");
    var pitch = note.writtenPitch();
    xml.append("<pitch><step>")
        .append(pitch.pitchClass())
        .append("</step><alter>")
        .append(pitch.accidental().semitones())
        .append("</alter><octave>")
        .append(pitch.octave())
        .append("</octave></pitch>");
    xml.append("<duration>").append(ticks(note.duration())).append("</duration>");
    if (note.tieStop()) xml.append("<tie type=\"stop\"/>");
    if (note.tieStart()) xml.append("<tie type=\"start\"/>");
    xml.append("<voice>").append(escape(voiceId)).append("</voice>");
    appendType(xml, note.duration(), note.tuplet());
    if (note.tieStart()
        || note.tieStop()
        || note.tuplet() != null && (note.tuplet().start() || note.tuplet().stop())) {
      xml.append("<notations>");
      if (note.tieStop()) xml.append("<tied type=\"stop\"/>");
      if (note.tieStart()) xml.append("<tied type=\"start\"/>");
      appendTupletMarkers(xml, note.tuplet());
      xml.append("</notations>");
    }
    xml.append("</note>\n");
  }

  private static void appendTupletMarkers(
      StringBuilder xml, org.arpeon.domain.score.Tuplet tuplet) {
    if (tuplet == null) return;
    if (tuplet.stop()) xml.append("<tuplet type=\"stop\"/>");
    if (tuplet.start()) xml.append("<tuplet type=\"start\"/>");
  }

  private static void appendType(
      StringBuilder xml, Duration duration, org.arpeon.domain.score.Tuplet tuplet) {
    if (tuplet != null)
      duration =
          new Duration(
              Math.multiplyExact(duration.numerator(), tuplet.actual()),
              Math.multiplyExact(duration.denominator(), tuplet.normal()));
    String[] types = {"whole", "half", "quarter", "eighth", "16th", "32nd", "64th", "128th"};
    for (int power = 0; power < types.length; power++) {
      var base = new Duration(1, 1L << power);
      var expected = base;
      for (int dots = 0; dots <= 3; dots++) {
        if (expected.equals(duration)) {
          xml.append("<type>").append(types[power]).append("</type>");
          for (int i = 0; i < dots; i++) xml.append("<dot/>");
          if (tuplet != null)
            xml.append("<time-modification><actual-notes>")
                .append(tuplet.actual())
                .append("</actual-notes><normal-notes>")
                .append(tuplet.normal())
                .append("</normal-notes></time-modification>");
          return;
        }
        expected = expected.plus(new Duration(1, 1L << (power + dots + 1)));
      }
    }
    if (tuplet != null)
      throw new IllegalArgumentException("Tuplet notation cannot be exported exactly");
  }

  private static String escape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }
}

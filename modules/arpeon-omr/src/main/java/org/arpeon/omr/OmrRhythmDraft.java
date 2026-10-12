package org.arpeon.omr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.arpeon.domain.time.ScoreOffset;
import org.arpeon.domain.time.TimeSignature;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Keeps recognized events intact while making uncertain measure boundaries explicit for review. */
final class OmrRhythmDraft {
  private OmrRhythmDraft() {}

  static void prepare(Element root, Set<String> warnings) throws IOException {
    for (Element part : children(root, "part")) {
      int divisions = 0;
      TimeSignature written = null;
      TimeSignature emitted = null;
      String keySignature = null;
      for (Element measure : children(part, "measure")) {
        String location = part.getAttribute("id") + " · compasso " + measure.getAttribute("number");
        try {
          Element attributes = child(measure, "attributes");
          if (attributes != null) {
            Element key = child(attributes, "key");
            if (key != null) {
              if (children(key, null).stream()
                  .anyMatch(e -> !Set.of("fifths", "mode").contains(e.getTagName())))
                throw new IllegalArgumentException("armadura não representável");
              Element mode = child(key, "mode");
              String signature =
                  integer(key, "fifths")
                      + ":"
                      + (mode == null ? "major" : mode.getTextContent().trim());
              if (keySignature != null) {
                if (!keySignature.equals(signature))
                  throw new IllegalArgumentException(
                      "mudança de armadura ainda não suportada pelo editor");
                attributes.removeChild(key);
              } else keySignature = signature;
            }
            Element division = child(attributes, "divisions");
            if (division != null) divisions = Integer.parseInt(division.getTextContent().trim());
            Element time = child(attributes, "time");
            if (time != null)
              written = new TimeSignature(integer(time, "beats"), integer(time, "beat-type"));
          }
          if (divisions < 1 || divisions > 1_000_000 || written == null)
            throw new IllegalArgumentException(
                "divisões e fórmula de compasso ausentes ou inválidas");
          long fullNumerator = Math.multiplyExact(4L * divisions, written.beats());
          if (fullNumerator % written.beatUnit() != 0)
            throw new IllegalArgumentException("fórmula de compasso incompatível com as divisões");
          long full = fullNumerator / written.beatUnit();
          long cursor = 0;
          long latest = 0;
          int notes = 0;
          for (Element item : children(measure, null)) {
            if (!Set.of("note", "backup", "forward").contains(item.getTagName())) continue;
            long duration = Long.parseLong(required(item, "duration").getTextContent().trim());
            if (duration <= 0) throw new IllegalArgumentException("duração inválida");
            if (item.getTagName().equals("note")) notes++;
            if (item.getTagName().equals("backup")) cursor = Math.subtractExact(cursor, duration);
            else if (child(item, "chord") == null) cursor = Math.addExact(cursor, duration);
            if (cursor < 0) throw new IllegalArgumentException("voz antes do início do compasso");
            latest = Math.max(latest, cursor);
          }
          if (notes == 0) {
            if (latest != 0)
              throw new IllegalArgumentException("compasso sem eventos com movimento de voz");
            Element placeholder = measure.getOwnerDocument().createElement("note");
            placeholder.appendChild(measure.getOwnerDocument().createElement("rest"));
            Element duration = measure.getOwnerDocument().createElement("duration");
            duration.setTextContent(Long.toString(full));
            placeholder.appendChild(duration);
            measure.appendChild(placeholder);
            cursor = full;
            latest = full;
            warnings.add(
                location
                    + ": nenhuma nota reconhecida. Pausa provisória mantém o espaço do compasso no"
                    + " rascunho; ela não foi reconhecida no original. Confira e complete este"
                    + " compasso.");
          }
          TimeSignature draft = written;
          if (latest > full) {
            ScoreOffset length = new ScoreOffset(latest, 4L * divisions);
            draft =
                new TimeSignature(
                    Math.toIntExact(length.numerator()), Math.toIntExact(length.denominator()));
            warnings.add(
                location
                    + ": duração reconhecida excede "
                    + written.beats()
                    + "/"
                    + written.beatUnit()
                    + ". Fórmula provisória "
                    + draft.beats()
                    + "/"
                    + draft.beatUnit()
                    + " no rascunho; confira notas, durações e barras no original antes de"
                    + " estudar.");
          } else if (latest < full && !measure.getAttribute("implicit").equals("yes")) {
            // Explicit cursor movement retains silence, without inventing a recognized rest or
            // pitch.
            Element forward = measure.getOwnerDocument().createElement("forward");
            Element duration = measure.getOwnerDocument().createElement("duration");
            duration.setTextContent(Long.toString(full - cursor));
            forward.appendChild(duration);
            measure.appendChild(forward);
            warnings.add(
                location
                    + ": compasso incompleto no reconhecimento. O espaço final permanece"
                    + " sem notas; confira o ritmo e as possíveis notas ausentes no original.");
          }
          if (!draft.equals(emitted) || (attributes != null && child(attributes, "time") != null)) {
            if (attributes == null) {
              attributes = measure.getOwnerDocument().createElement("attributes");
              measure.insertBefore(attributes, measure.getFirstChild());
            }
            Element time = child(attributes, "time");
            if (time != null) attributes.removeChild(time);
            time = measure.getOwnerDocument().createElement("time");
            Element beats = measure.getOwnerDocument().createElement("beats");
            beats.setTextContent(Integer.toString(draft.beats()));
            Element unit = measure.getOwnerDocument().createElement("beat-type");
            unit.setTextContent(Integer.toString(draft.beatUnit()));
            time.appendChild(beats);
            time.appendChild(unit);
            attributes.appendChild(time);
          }
          emitted = draft;
        } catch (IllegalArgumentException | ArithmeticException failure) {
          throw new IOException(
              location
                  + ": não foi possível preparar o ritmo para revisão ("
                  + failure.getMessage()
                  + "). Corrija este compasso no Audiveris e importe o MusicXML.",
              failure);
        }
      }
    }
    alignParts(root, warnings);
  }

  private record PartMeasure(
      Element measure, int divisions, TimeSignature meter, long cursor, long end, boolean pickup) {}

  private static void alignParts(Element root, Set<String> warnings) throws IOException {
    var parts = children(root, "part");
    if (parts.size() < 2) return;
    var states = new ArrayList<List<PartMeasure>>();
    for (var part : parts) {
      int divisions = 0;
      TimeSignature meter = null;
      var measures = new ArrayList<PartMeasure>();
      for (var measure : children(part, "measure")) {
        var attributes = child(measure, "attributes");
        if (attributes != null) {
          if (child(attributes, "divisions") != null) divisions = integer(attributes, "divisions");
          var time = child(attributes, "time");
          if (time != null)
            meter = new TimeSignature(integer(time, "beats"), integer(time, "beat-type"));
        }
        long cursor = 0;
        long end = 0;
        for (var item : children(measure, null)) {
          if (!Set.of("note", "backup", "forward").contains(item.getTagName())) continue;
          long duration = Long.parseLong(required(item, "duration").getTextContent().trim());
          if (item.getTagName().equals("backup")) cursor = Math.subtractExact(cursor, duration);
          else if (child(item, "chord") == null) cursor = Math.addExact(cursor, duration);
          if (item.getTagName().equals("note")) end = Math.max(end, cursor);
        }
        measures.add(
            new PartMeasure(
                measure,
                divisions,
                meter,
                cursor,
                end,
                measure.getAttribute("implicit").equals("yes")));
      }
      if (!states.isEmpty() && measures.size() != states.getFirst().size())
        throw new IOException(
            "As partes reconhecidas têm quantidades diferentes de compassos. Corrija as barras no"
                + " Audiveris.");
      states.add(List.copyOf(measures));
    }
    for (int index = 0; index < states.getFirst().size(); index++) {
      var reference = states.getFirst().get(index);
      TimeSignature shared = reference.meter();
      boolean compatible = true;
      var referenceEnd = new ScoreOffset(reference.end(), 4L * reference.divisions());
      for (var part : states) {
        var state = part.get(index);
        if (!state
            .measure()
            .getAttribute("number")
            .equals(reference.measure().getAttribute("number")))
          throw new IOException(
              "As partes reconhecidas têm números de compasso incompatíveis. Corrija as barras no"
                  + " Audiveris.");
        var meterEnd = new ScoreOffset(state.meter().beats(), state.meter().beatUnit());
        if (meterEnd.compareTo(new ScoreOffset(shared.beats(), shared.beatUnit())) > 0)
          shared = state.meter();
        if (!state.meter().equals(reference.meter())
            || state.pickup() != reference.pickup()
            || (state.pickup()
                && !new ScoreOffset(state.end(), 4L * state.divisions()).equals(referenceEnd)))
          compatible = false;
      }
      for (int partIndex = 0; partIndex < states.size(); partIndex++) {
        var state = states.get(partIndex).get(index);
        var measure = state.measure();
        TimeSignature target = compatible ? state.meter() : shared;
        // Re-state every meter so a shared provisional bar never leaks into following bars.
        var attributes = child(measure, "attributes");
        if (attributes == null) {
          attributes = measure.getOwnerDocument().createElement("attributes");
          measure.insertBefore(attributes, measure.getFirstChild());
        }
        var time = child(attributes, "time");
        if (time != null) attributes.removeChild(time);
        time = measure.getOwnerDocument().createElement("time");
        var beats = measure.getOwnerDocument().createElement("beats");
        beats.setTextContent(Integer.toString(target.beats()));
        var unit = measure.getOwnerDocument().createElement("beat-type");
        unit.setTextContent(Integer.toString(target.beatUnit()));
        time.appendChild(beats);
        time.appendChild(unit);
        attributes.appendChild(time);
        if (compatible) continue;
        measure.removeAttribute("implicit");
        long numerator = Math.multiplyExact(4L * state.divisions(), target.beats());
        if (numerator % target.beatUnit() != 0)
          throw new IOException(
              "Não é possível alinhar as partes sem alterar as durações reconhecidas. Corrija o"
                  + " compasso no Audiveris.");
        long full = numerator / target.beatUnit();
        if (state.end() < full && state.cursor() < full) {
          var forward = measure.getOwnerDocument().createElement("forward");
          var duration = measure.getOwnerDocument().createElement("duration");
          duration.setTextContent(Long.toString(full - state.cursor()));
          forward.appendChild(duration);
          measure.appendChild(forward);
        }
        warnings.add(
            parts.get(partIndex).getAttribute("id")
                + " · compasso "
                + measure.getAttribute("number")
                + ": alinhamento provisório entre partes em "
                + target.beats()
                + "/"
                + target.beatUnit()
                + "; o espaço sem notas não foi reconhecido como pausa. Confira a anacruse, as"
                + " durações e as barras no original antes de estudar.");
      }
    }
  }

  private static int integer(Element parent, String name) throws IOException {
    return Integer.parseInt(required(parent, name).getTextContent().trim());
  }

  private static Element required(Element parent, String name) throws IOException {
    Element found = child(parent, name);
    if (found == null) throw new IOException("MusicXML sem " + name);
    return found;
  }

  private static Element child(Element parent, String name) throws IOException {
    List<Element> found = children(parent, name);
    if (found.size() > 1) throw new IOException("MusicXML com elemento duplicado: " + name);
    return found.isEmpty() ? null : found.getFirst();
  }

  private static List<Element> children(Element parent, String name) {
    var found = new ArrayList<Element>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element && (name == null || name.equals(element.getTagName())))
        found.add(element);
    return found;
  }
}

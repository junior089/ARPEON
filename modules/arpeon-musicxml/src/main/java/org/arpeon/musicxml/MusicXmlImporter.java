package org.arpeon.musicxml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.MusicalKey;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.score.ScoreEvent;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.Tempo;
import org.arpeon.domain.time.TimeSignature;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Deliberately narrow monophonic score-partwise importer. Unsupported semantics fail closed. */
public final class MusicXmlImporter {
  public static final int MAX_BYTES = 1_048_576;

  public record ImportedScore(
      Score score,
      String sourceFilename,
      String sha256,
      String parserVersion,
      String keyId,
      Map<String, String> instruments) {}

  private record ParsedPart(Part part, String keyId) {}

  public ImportedScore read(InputStream source, String filename) throws IOException {
    if (filename == null || !filename.matches("[^/\\\\:]+\\.(?i:musicxml|xml)"))
      throw new IOException("Use an uncompressed .musicxml or .xml filename, without a path");
    byte[] bytes = source.readNBytes(MAX_BYTES + 1);
    if (bytes.length > MAX_BYTES) throw new IOException("MusicXML exceeds the 1 MiB limit");
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setAttribute("jdk.xml.maxElementDepth", "32");
      factory.setAttribute("jdk.xml.elementAttributeLimit", "16");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXException {
              throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
              throw exception;
            }
          });
      Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
      if (!root.getTagName().equals("score-partwise"))
        throw new IllegalArgumentException("Expected score-partwise MusicXML");
      allow(root, Set.of("work", "part-list", "part"), Set.of("version"));
      String title = "";
      var work = optional(root, "work");
      if (work.isPresent()) {
        allow(work.get(), Set.of("work-title"), Set.of());
        title = text(one(work.get(), "work-title"));
      }
      var partList = one(root, "part-list");
      allow(partList, Set.of("score-part"), Set.of());
      var names = new LinkedHashMap<String, String>();
      var instruments = new LinkedHashMap<String, String>();
      for (Element declaration : children(partList, "score-part")) {
        allow(
            declaration, Set.of("part-name", "score-instrument", "midi-instrument"), Set.of("id"));
        String id = declaration.getAttribute("id");
        if (id.isBlank() || names.putIfAbsent(id, text(one(declaration, "part-name"))) != null)
          throw new IllegalArgumentException("Missing or duplicate part ID");
        var named = optional(declaration, "score-instrument");
        var midi = optional(declaration, "midi-instrument");
        if (named.isPresent() != midi.isPresent())
          throw new IllegalArgumentException("Instrument and MIDI program must be paired");
        if (named.isPresent()) {
          allow(named.orElseThrow(), Set.of("instrument-name"), Set.of("id"));
          text(one(named.orElseThrow(), "instrument-name"));
          allow(midi.orElseThrow(), Set.of("midi-program"), Set.of("id"));
          if (named.orElseThrow().getAttribute("id").isBlank()
              || !named
                  .orElseThrow()
                  .getAttribute("id")
                  .equals(midi.orElseThrow().getAttribute("id")))
            throw new IllegalArgumentException("Instrument IDs do not match");
          int program = Integer.parseInt(text(one(midi.orElseThrow(), "midi-program")));
          String profileId =
              switch (program) {
                case 74 -> "flute";
                case 72 -> "bb-clarinet";
                default -> throw new IllegalArgumentException("Unsupported MIDI instrument");
              };
          instruments.put(id, profileId);
        }
      }
      var parts = new ArrayList<Part>();
      String keyId = null;
      for (Element part : children(root, "part")) {
        String id = part.getAttribute("id");
        if (!names.containsKey(id)) throw new IllegalArgumentException("Undeclared part");
        ParsedPart parsed =
            parsePart(
                part,
                id,
                names.get(id),
                InstrumentCatalog.get(instruments.getOrDefault(id, "concert")));
        if (!parts.isEmpty()) {
          if ((keyId == null) != (parsed.keyId() == null))
            throw new IllegalArgumentException("Parts use different key signatures");
          if (keyId != null) {
            MusicalKey firstKey = MusicalKey.get(keyId);
            MusicalKey currentKey = MusicalKey.get(parsed.keyId());
            if (firstKey.tonic() != currentKey.tonic() || firstKey.minor() != currentKey.minor())
              throw new IllegalArgumentException("Parts use different key signatures");
          }
        } else keyId = parsed.keyId();
        parts.add(parsed.part());
      }
      if (parts.size() != names.size())
        throw new IllegalArgumentException("Part declarations do not match score");
      return new ImportedScore(
          new Score(title, parts),
          filename,
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
          "musicxml-m2-v1",
          keyId,
          Map.copyOf(instruments));
    } catch (ParserConfigurationException
        | SAXException
        | IllegalArgumentException
        | ArithmeticException failure) {
      throw new IOException("Invalid or unsupported MusicXML: " + failure.getMessage(), failure);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private ParsedPart parsePart(Element part, String id, String name, InstrumentProfile profile) {
    allow(part, Set.of("measure"), Set.of("id"));
    int divisions = 0;
    TimeSignature meter = null;
    String keyId = null;
    boolean transposeSeen = false;
    var measures = new ArrayList<Measure>();
    for (Element measure : children(part, "measure")) {
      allow(measure, Set.of("attributes", "note", "sound"), Set.of("number", "implicit"));
      Optional<Tempo> tempo = Optional.empty();
      var events = new ArrayList<ScoreEvent>();
      for (Element element : children(measure, null)) {
        switch (element.getTagName()) {
          case "attributes" -> {
            if (!events.isEmpty())
              throw new IllegalArgumentException("Mid-measure attributes unsupported");
            allow(element, Set.of("divisions", "time", "key", "transpose"), Set.of());
            var transpose = optional(element, "transpose");
            if (transpose.isPresent()) {
              if (!measures.isEmpty() || transposeSeen || !profile.id().equals("bb-clarinet"))
                throw new IllegalArgumentException("Unsupported instrument transposition");
              allow(transpose.orElseThrow(), Set.of("diatonic", "chromatic"), Set.of());
              if (!text(one(transpose.orElseThrow(), "diatonic")).equals("-1")
                  || !text(one(transpose.orElseThrow(), "chromatic")).equals("-2"))
                throw new IllegalArgumentException("Unsupported instrument transposition");
              transposeSeen = true;
            }
            var key = optional(element, "key");
            if (key.isPresent()) {
              if (!measures.isEmpty() || keyId != null)
                throw new IllegalArgumentException("Only an initial key signature is supported");
              allow(key.get(), Set.of("fifths", "mode"), Set.of());
              String mode = optional(key.get(), "mode").map(MusicXmlImporter::text).orElse("major");
              if (!mode.equals("major") && !mode.equals("minor"))
                throw new IllegalArgumentException("Unsupported key mode");
              keyId =
                  MusicalKey.fromSignature(
                          Integer.parseInt(text(one(key.get(), "fifths"))), mode.equals("minor"))
                      .id();
            }
            var division = optional(element, "divisions");
            if (division.isPresent()) {
              divisions = Integer.parseInt(text(division.get()));
              if (divisions < 1 || divisions > 1_000_000)
                throw new IllegalArgumentException("Invalid divisions");
            }
            var time = optional(element, "time");
            if (time.isPresent()) {
              allow(time.get(), Set.of("beats", "beat-type"), Set.of());
              meter =
                  new TimeSignature(
                      Integer.parseInt(text(one(time.get(), "beats"))),
                      Integer.parseInt(text(one(time.get(), "beat-type"))));
            }
          }
          case "sound" -> {
            allow(element, Set.of(), Set.of("tempo"));
            if (!events.isEmpty() || tempo.isPresent())
              throw new IllegalArgumentException(
                  "Only one starting tempo per measure is supported");
            tempo = Optional.of(new Tempo(Double.parseDouble(element.getAttribute("tempo"))));
          }
          case "note" -> {
            if (divisions == 0 || meter == null)
              throw new IllegalArgumentException(
                  "Explicit divisions and meter required before notes");
            events.add(parseEvent(element, divisions));
          }
          default -> throw new IllegalArgumentException("Unsupported measure element");
        }
      }
      String implicit = measure.getAttribute("implicit");
      if (!implicit.isEmpty() && !implicit.equals("yes") && !implicit.equals("no"))
        throw new IllegalArgumentException("Invalid implicit attribute");
      if (meter == null) throw new IllegalArgumentException("Missing meter");
      measures.add(
          new Measure(
              measure.getAttribute("number"), meter, tempo, events, implicit.equals("yes")));
    }
    if (profile.soundingTranspositionSemitones() != 0 && !transposeSeen)
      throw new IllegalArgumentException("Transposing instrument needs MusicXML transpose");
    if (keyId != null)
      keyId = MusicalKey.get(keyId).transpose(profile.soundingTranspositionSemitones()).id();
    return new ParsedPart(new Part(id, name, measures), keyId);
  }

  private ScoreEvent parseEvent(Element note, int divisions) {
    allow(note, Set.of("pitch", "rest", "duration", "voice", "type", "dot"), Set.of());
    var voice = optional(note, "voice");
    if (voice.isPresent() && !text(voice.get()).equals("1"))
      throw new IllegalArgumentException("Only voice 1 supported");
    var duration = new Duration(Long.parseLong(text(one(note, "duration"))), 4L * divisions);
    validateNotatedDuration(note, duration);
    var rest = optional(note, "rest");
    var pitch = optional(note, "pitch");
    if (rest.isPresent() == pitch.isPresent())
      throw new IllegalArgumentException("Note must have exactly one pitch or rest");
    if (rest.isPresent()) {
      allow(rest.get(), Set.of(), Set.of());
      return new Rest(duration);
    }
    var p = pitch.orElseThrow();
    allow(p, Set.of("step", "alter", "octave"), Set.of());
    int alter = optional(p, "alter").map(MusicXmlImporter::text).map(Integer::parseInt).orElse(0);
    return new Note(
        new Pitch(
            PitchClass.valueOf(text(one(p, "step"))),
            Accidental.fromSemitones(alter),
            Integer.parseInt(text(one(p, "octave")))),
        duration);
  }

  private static void allow(Element parent, Set<String> tags, Set<String> attributes) {
    for (Element child : children(parent, null))
      if (!tags.contains(child.getTagName()))
        throw new IllegalArgumentException("Unsupported element: " + child.getTagName());
    var attrs = parent.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++)
      if (!attributes.contains(attrs.item(i).getNodeName()))
        throw new IllegalArgumentException("Unsupported attribute: " + attrs.item(i).getNodeName());
  }

  private static void validateNotatedDuration(Element note, Duration duration) {
    var type = optional(note, "type");
    var dots = children(note, "dot");
    if (dots.size() > 3 || (type.isEmpty() && !dots.isEmpty()))
      throw new IllegalArgumentException("Unsupported dotted duration");
    for (var dot : dots) {
      allow(dot, Set.of(), Set.of());
      if (!dot.getTextContent().isBlank()) throw new IllegalArgumentException("Invalid dot");
    }
    if (type.isEmpty()) return;
    int denominator =
        switch (text(type.orElseThrow())) {
          case "whole" -> 1;
          case "half" -> 2;
          case "quarter" -> 4;
          case "eighth" -> 8;
          case "16th" -> 16;
          case "32nd" -> 32;
          case "64th" -> 64;
          case "128th" -> 128;
          default -> throw new IllegalArgumentException("Unsupported note type");
        };
    var expected = new Duration(1, denominator);
    for (int i = 1; i <= dots.size(); i++)
      expected = expected.plus(new Duration(1, denominator * (1L << i)));
    if (!expected.equals(duration))
      throw new IllegalArgumentException("Notated type contradicts duration");
  }

  private static List<Element> children(Element parent, String tag) {
    var result = new ArrayList<Element>();
    for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element && (tag == null || element.getTagName().equals(tag)))
        result.add(element);
    return result;
  }

  private static Optional<Element> optional(Element parent, String tag) {
    var found = children(parent, tag);
    if (found.size() > 1) throw new IllegalArgumentException("Duplicate " + tag);
    return found.stream().findFirst();
  }

  private static Element one(Element parent, String tag) {
    return optional(parent, tag).orElseThrow(() -> new IllegalArgumentException("Missing " + tag));
  }

  private static String text(Element element) {
    allow(element, Set.of(), Set.of());
    return element.getTextContent().trim();
  }
}

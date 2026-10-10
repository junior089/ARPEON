package org.arpeon.musicxml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.arpeon.domain.instrument.InstrumentCatalog;
import org.arpeon.domain.instrument.InstrumentProfile;
import org.arpeon.domain.pitch.Accidental;
import org.arpeon.domain.pitch.Pitch;
import org.arpeon.domain.pitch.PitchClass;
import org.arpeon.domain.score.Chord;
import org.arpeon.domain.score.EndingRegion;
import org.arpeon.domain.score.Measure;
import org.arpeon.domain.score.MusicalKey;
import org.arpeon.domain.score.Note;
import org.arpeon.domain.score.Part;
import org.arpeon.domain.score.RepeatRegion;
import org.arpeon.domain.score.Rest;
import org.arpeon.domain.score.Score;
import org.arpeon.domain.score.ScoreEvent;
import org.arpeon.domain.score.TimedEvent;
import org.arpeon.domain.score.Voice;
import org.arpeon.domain.time.Duration;
import org.arpeon.domain.time.ScoreOffset;
import org.arpeon.domain.time.Tempo;
import org.arpeon.domain.time.TimeSignature;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded score-partwise importer. Unsupported musical semantics fail closed. */
public final class MusicXmlImporter {
  public static final int MAX_BYTES = 128 * 1_048_576;
  public static final int MAX_PARTS = 16;
  public static final int MAX_MEASURES_PER_PART = 2_048;
  public static final int MAX_SCORE_EVENTS = 16_384;
  public static final int MAX_XML_ELEMENTS = 250_000;
  public static final int MAX_XML_DEPTH = 32;
  private static final int MAX_MXL_ENTRIES = 256;
  private static final int MAX_MXL_EXPANDED_BYTES = 128 * 1_048_576;
  private static final int MAX_CONTAINER_BYTES = 64 * 1_024;
  private static final String CONTAINER_PATH = "META-INF/container.xml";

  public record ImportedScore(
      Score score,
      String sourceFilename,
      String sha256,
      String parserVersion,
      String keyId,
      Map<String, String> instruments) {}

  private record ParsedPart(
      Part part, String keyId, List<RepeatRegion> repeats, List<EndingRegion> endings) {}

  private record ParsedEvent(ScoreEvent event, boolean chordHead, String voiceId) {}

  public ImportedScore read(InputStream source, String filename) throws IOException {
    if (filename == null || !filename.matches("[^/\\\\:]+\\.(?i:musicxml|xml)"))
      throw new IOException("Use an uncompressed .musicxml or .xml filename, without a path");
    MessageDigest digest = newDigest();
    try {
      var limited = new LimitedInputStream(source, MAX_BYTES);
      return readStreaming(new DigestInputStream(limited, digest), filename, digest);
    } catch (XMLStreamException
        | ParserConfigurationException
        | IllegalArgumentException
        | ArithmeticException failure) {
      throw new IOException("Invalid or unsupported MusicXML: " + failure.getMessage(), failure);
    }
  }

  public ImportedScore read(byte[] bytes, String filename) throws IOException {
    if (filename != null && filename.matches("[^/\\\\:]+\\.(?i:mscz)"))
      return readMscz(bytes, filename);
    if (filename == null || !filename.matches("[^/\\\\:]+\\.(?i:musicxml|xml)"))
      throw new IOException(
          "Use an uncompressed .musicxml, .xml or .mscz filename, without a path");
    if (bytes.length > MAX_BYTES) throw new IOException("MusicXML exceeds the 128 MiB limit");
    return read(new ByteArrayInputStream(bytes), filename);
  }

  private ImportedScore readStreaming(InputStream source, String filename, MessageDigest digest)
      throws XMLStreamException, ParserConfigurationException, IOException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
    factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    XMLStreamReader reader = factory.createXMLStreamReader(source);
    try {
      while (reader.getEventType() != XMLStreamConstants.START_ELEMENT && reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE)
          throw new XMLStreamException("MusicXML DTDs and entities are not accepted");
      }
      if (reader.getEventType() != XMLStreamConstants.START_ELEMENT
          || !plainName(reader).equals("score-partwise"))
        throw new IllegalArgumentException("Expected score-partwise MusicXML");
      validateAttributes(reader);
      for (int index = 0; index < reader.getAttributeCount(); index++)
        if (!reader.getAttributeLocalName(index).equals("version"))
          throw new IllegalArgumentException(
              "Unsupported attribute: " + reader.getAttributeLocalName(index));

      Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      var budget = new XmlBudget();
      var names = new LinkedHashMap<String, String>();
      var instruments = new LinkedHashMap<String, String>();
      var parts = new ArrayList<Part>();
      List<RepeatRegion> repeatRegions = null;
      List<EndingRegion> endingRegions = null;
      var scoreEvents = new int[1];
      var rootChildren = new HashSet<String>();
      String workTitle = "";
      String movementTitle = "";
      String keyId = null;
      boolean partListSeen = false;
      boolean rootClosed = false;

      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.END_ELEMENT && plainName(reader).equals("score-partwise")) {
          rootClosed = true;
          break;
        }
        if (event == XMLStreamConstants.START_ELEMENT) {
          String name = plainName(reader);
          if (!Set.of(
                  "movement-title",
                  "work",
                  "part-list",
                  "part",
                  "identification",
                  "defaults",
                  "credit")
              .contains(name)) throw new IllegalArgumentException("Unsupported element: " + name);
          if (!name.equals("part") && !name.equals("credit")) {
            if (!rootChildren.add(name)) throw new IllegalArgumentException("Duplicate " + name);
          } else if (name.equals("part") && !partListSeen) {
            throw new IllegalArgumentException("Part declarations must precede score parts");
          }
          Element element = readSubtree(reader, document, budget, 2);
          switch (name) {
            case "movement-title" -> movementTitle = text(element);
            case "work" -> {
              allow(element, Set.of("work-title", "work-number", "opus"), Set.of());
              var title = optional(element, "work-title");
              if (title.isPresent()) workTitle = text(title.orElseThrow());
            }
            case "identification", "defaults", "credit" -> {
              /* descriptive/engraving metadata */
            }
            case "part-list" -> {
              readPartDeclarations(element, names, instruments);
              partListSeen = true;
            }
            case "part" -> {
              if (parts.size() == MAX_PARTS)
                throw new IllegalArgumentException("Score exceeds the 16-part limit");
              String id = element.getAttribute("id");
              if (!names.containsKey(id)) throw new IllegalArgumentException("Undeclared part");
              ParsedPart parsed =
                  parsePart(
                      element,
                      id,
                      names.get(id),
                      InstrumentCatalog.get(instruments.getOrDefault(id, "concert")),
                      scoreEvents);
              if (!parts.isEmpty()) {
                if ((keyId == null) != (parsed.keyId() == null))
                  throw new IllegalArgumentException("Parts use different key signatures");
                if (keyId != null) {
                  MusicalKey firstKey = MusicalKey.get(keyId);
                  MusicalKey currentKey = MusicalKey.get(parsed.keyId());
                  if (firstKey.tonic() != currentKey.tonic()
                      || firstKey.minor() != currentKey.minor())
                    throw new IllegalArgumentException("Parts use different key signatures");
                }
                if (!repeatRegions.equals(parsed.repeats())
                    || !endingRegions.equals(parsed.endings()))
                  throw new IllegalArgumentException("Parts use different repeat structures");
              } else keyId = parsed.keyId();
              if (repeatRegions == null) {
                repeatRegions = parsed.repeats();
                endingRegions = parsed.endings();
              }
              parts.add(parsed.part());
            }
            default -> throw new IllegalStateException("Unexpected root child");
          }
        } else if ((event == XMLStreamConstants.CHARACTERS
                || event == XMLStreamConstants.CDATA
                || event == XMLStreamConstants.SPACE)
            && !reader.isWhiteSpace()) {
          throw new IllegalArgumentException("Unexpected text between score elements");
        } else if (event == XMLStreamConstants.DTD
            || event == XMLStreamConstants.ENTITY_REFERENCE) {
          throw new XMLStreamException("MusicXML DTDs and entities are not accepted");
        }
      }
      if (!rootClosed) throw new XMLStreamException("MusicXML score-partwise is not closed");
      validateTrailingXml(reader);
      if (!partListSeen || parts.isEmpty())
        throw new IllegalArgumentException("Score needs part declarations and at least one part");
      if (parts.size() != names.size())
        throw new IllegalArgumentException("Part declarations do not match score");
      if (!workTitle.isBlank() && !movementTitle.isBlank() && !workTitle.equals(movementTitle))
        throw new IllegalArgumentException("Conflicting work-title and movement-title metadata");
      String title = movementTitle.isBlank() ? workTitle : movementTitle;
      return new ImportedScore(
          new Score(title, parts, repeatRegions, endingRegions),
          filename,
          HexFormat.of().formatHex(digest.digest()),
          "musicxml-m3-stax-v1",
          keyId,
          Map.copyOf(instruments));
    } finally {
      reader.close();
    }
  }

  private static void readPartDeclarations(
      Element partList, Map<String, String> names, Map<String, String> instruments) {
    allow(partList, Set.of("score-part", "part-group"), Set.of());
    var declarations =
        childrenBounded(partList, "score-part", MAX_PARTS, "Score exceeds the 16-part limit");
    for (Element declaration : declarations) {
      allow(
          declaration,
          Set.of(
              "part-name",
              "score-instrument",
              "midi-instrument",
              "midi-device",
              "part-abbreviation"),
          Set.of("id"));
      String id = declaration.getAttribute("id");
      if (id.isBlank() || names.putIfAbsent(id, text(one(declaration, "part-name"))) != null)
        throw new IllegalArgumentException("Missing or duplicate part ID");
      var named = optional(declaration, "score-instrument");
      var midi = optional(declaration, "midi-instrument");
      if (named.isPresent() != midi.isPresent())
        throw new IllegalArgumentException("Instrument and MIDI program must be paired");
      if (named.isPresent()) {
        allow(
            named.orElseThrow(),
            Set.of("instrument-name", "instrument-sound", "solo", "ensemble"),
            Set.of("id"));
        text(one(named.orElseThrow(), "instrument-name"));
        allow(
            midi.orElseThrow(),
            Set.of(
                "midi-program",
                "midi-channel",
                "midi-name",
                "midi-bank",
                "midi-unpitched",
                "volume",
                "pan"),
            Set.of("id"));
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
  }

  private static final class XmlBudget {
    private int elements = 1;
  }

  private static Element readSubtree(
      XMLStreamReader reader, Document document, XmlBudget budget, int depth)
      throws XMLStreamException {
    if (depth > MAX_XML_DEPTH)
      throw new XMLStreamException("MusicXML exceeds the 32-level nesting limit");
    if (++budget.elements > MAX_XML_ELEMENTS)
      throw new XMLStreamException("MusicXML exceeds the 250000-element limit");
    validateAttributes(reader);
    Element element = document.createElement(plainName(reader));
    for (int index = 0; index < reader.getAttributeCount(); index++)
      element.setAttribute(reader.getAttributeLocalName(index), reader.getAttributeValue(index));
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        element.appendChild(readSubtree(reader, document, budget, depth + 1));
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        return element;
      } else if (event == XMLStreamConstants.CHARACTERS
          || event == XMLStreamConstants.CDATA
          || event == XMLStreamConstants.SPACE) {
        element.appendChild(document.createTextNode(reader.getText()));
      } else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
        throw new XMLStreamException("MusicXML DTDs and entities are not accepted");
      } else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
        throw new XMLStreamException("Processing instructions are not supported in MusicXML");
      }
    }
    throw new XMLStreamException("Unexpected end of MusicXML element");
  }

  private static String plainName(XMLStreamReader reader) throws XMLStreamException {
    String namespace = reader.getNamespaceURI();
    String prefix = reader.getPrefix();
    if ((namespace != null && !namespace.isEmpty()) || (prefix != null && !prefix.isEmpty()))
      throw new XMLStreamException("Namespaced MusicXML elements are not supported");
    return reader.getLocalName();
  }

  private static void validateAttributes(XMLStreamReader reader) throws XMLStreamException {
    if (reader.getAttributeCount() > 16)
      throw new XMLStreamException("MusicXML element exceeds the 16-attribute limit");
    if (reader.getNamespaceCount() > 0)
      throw new XMLStreamException("Namespaced MusicXML is not supported");
    for (int index = 0; index < reader.getAttributeCount(); index++) {
      String namespace = reader.getAttributeNamespace(index);
      String prefix = reader.getAttributePrefix(index);
      if ((namespace != null && !namespace.isEmpty()) || (prefix != null && !prefix.isEmpty()))
        throw new XMLStreamException("Namespaced MusicXML attributes are not supported");
    }
  }

  private static void validateTrailingXml(XMLStreamReader reader) throws XMLStreamException {
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT
          || event == XMLStreamConstants.DTD
          || event == XMLStreamConstants.ENTITY_REFERENCE)
        throw new XMLStreamException("Unexpected content after score-partwise");
      if ((event == XMLStreamConstants.CHARACTERS
              || event == XMLStreamConstants.CDATA
              || event == XMLStreamConstants.SPACE)
          && !reader.isWhiteSpace())
        throw new XMLStreamException("Unexpected text after score-partwise");
    }
  }

  /** Reject pathological XML structure before DOM allocates a node for every element. */
  public static void validateXmlInput(byte[] bytes) throws IOException {
    try {
      validateXmlStructure(bytes);
    } catch (ParserConfigurationException | SAXException failure) {
      throw new IOException("Invalid or structurally excessive MusicXML", failure);
    }
  }

  private static void validateXmlStructure(byte[] bytes)
      throws ParserConfigurationException, SAXException, IOException {
    var factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    var parser = factory.newSAXParser();
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    parser.parse(
        new ByteArrayInputStream(bytes),
        new DefaultHandler() {
          private int depth;
          private int elements;

          @Override
          public void startElement(
              String uri, String localName, String qName, org.xml.sax.Attributes attributes)
              throws SAXException {
            if (++depth > MAX_XML_DEPTH)
              throw new SAXException("MusicXML exceeds the 32-level nesting limit");
            if (++elements > MAX_XML_ELEMENTS)
              throw new SAXException("MusicXML exceeds the 250000-element limit");
            if (attributes.getLength() > 16)
              throw new SAXException("MusicXML element exceeds the 16-attribute limit");
          }

          @Override
          public void endElement(String uri, String localName, String qName) {
            depth--;
          }

          @Override
          public void error(SAXParseException exception) throws SAXException {
            throw exception;
          }

          @Override
          public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
          }
        });
  }

  /** Reads the first MusicXML root file from a bounded, compressed MusicXML package. */
  public ImportedScore readMxl(byte[] archive, String filename) throws IOException {
    if (filename != null && filename.matches("[^/\\\\:]+\\.(?i:mscz)"))
      return readMscz(archive, filename);
    if (filename == null || !filename.matches("[^/\\\\:]+\\.(?i:mxl)"))
      throw new IOException("Use a .mxl or .mscz filename without a path");
    if (archive.length > MAX_BYTES) throw new IOException("MXL exceeds the 128 MiB limit");

    byte[] container = null;
    var names = new HashSet<String>();
    long expanded = 0;
    int count = 0;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (++count > MAX_MXL_ENTRIES) throw new IOException("MXL contains too many ZIP entries");
        String name = entry.getName();
        validateZipPath(name);
        if (!names.add(name)) throw new IOException("MXL contains duplicate ZIP paths");
        if (name.equals(CONTAINER_PATH)) {
          container = readBounded(zip, MAX_CONTAINER_BYTES);
          expanded += container.length;
        } else {
          expanded += drainBounded(zip, MAX_MXL_EXPANDED_BYTES);
        }
        if (expanded > MAX_MXL_EXPANDED_BYTES)
          throw new IOException("MXL expands beyond the 128 MiB limit");
        zip.closeEntry();
      }
    } catch (java.util.zip.ZipException failure) {
      throw new IOException("Invalid or unsupported MXL ZIP archive", failure);
    }
    if (container == null) throw new IOException("MXL is missing META-INF/container.xml");
    String rootPath = parseContainerRoot(container);
    if (!names.contains(rootPath))
      throw new IOException("MXL root file is missing from the archive");

    byte[] scoreXml = null;
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals(rootPath)) {
          scoreXml = readBounded(zip, MAX_MXL_EXPANDED_BYTES);
          break;
        }
        zip.closeEntry();
      }
    } catch (java.util.zip.ZipException failure) {
      throw new IOException("Invalid or unsupported MXL ZIP archive", failure);
    }
    if (scoreXml == null || scoreXml.length == 0) throw new IOException("MXL root file is empty");
    scoreXml = stripCanonicalMusicXmlDoctype(scoreXml);
    scoreXml = omitGraceNotes(scoreXml);
    scoreXml = markCompressedMeasuresImplicit(scoreXml);
    ImportedScore parsed = read(scoreXml, "score.musicxml");
    return new ImportedScore(
        parsed.score(),
        filename,
        HexFormat.of().formatHex(sha256(archive)),
        "mxl-v1+" + parsed.parserVersion(),
        parsed.keyId(),
        parsed.instruments());
  }

  /* The current score model has no zero-time grace-note event; keep timed notes intact. */
  private static byte[] omitGraceNotes(byte[] xml) {
    String source = new String(xml, StandardCharsets.UTF_8);
    String normalized =
        source.replaceAll(
            "(?s)<note\\b[^>]*>(?:(?!<note\\b).)*?<grace\\b[^>]*(?:/>|>[\\s\\S]*?</grace>)(?:(?!<note\\b).)*?</note>",
            "");
    return normalized.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] markCompressedMeasuresImplicit(byte[] xml) {
    String source = new String(xml, StandardCharsets.UTF_8);
    var measures =
        java.util.regex.Pattern.compile("(?s)<measure\\b([^>]*)>(.*?)</measure>").matcher(source);
    var output = new StringBuffer(source.length());
    int divisions = 0, beats = 4, beatType = 4;
    while (measures.find()) {
      String attributes = measures.group(1), body = measures.group(2);
      var divisionMatcher =
          java.util.regex.Pattern.compile("<divisions>(\\d+)</divisions>").matcher(body);
      if (divisionMatcher.find()) divisions = Integer.parseInt(divisionMatcher.group(1));
      var timeMatcher =
          java.util.regex.Pattern.compile(
                  "<time\\b[^>]*>.*?<beats>(\\d+)</beats>.*?<beat-type>(\\d+)</beat-type>.*?</time>",
                  java.util.regex.Pattern.DOTALL)
              .matcher(body);
      if (timeMatcher.find()) {
        beats = Integer.parseInt(timeMatcher.group(1));
        beatType = Integer.parseInt(timeMatcher.group(2));
      }
      boolean inferPickup = false;
      if (divisions > 0
          && beatType > 0
          && !body.contains("<backup")
          && !body.contains("<forward")
          && !attributes.matches("(?s).*\\bimplicit\\s*=.*")) {
        long ticks = 0;
        var notes = java.util.regex.Pattern.compile("(?s)<note\\b[^>]*>(.*?)</note>").matcher(body);
        while (notes.find()) {
          String note = notes.group(1);
          if (note.contains("<chord")) continue;
          var duration =
              java.util.regex.Pattern.compile("<duration>(\\d+)</duration>").matcher(note);
          if (duration.find()) ticks += Long.parseLong(duration.group(1));
        }
        inferPickup = ticks > 0 && ticks < 4L * divisions * beats / beatType;
      }
      String open = "<measure" + attributes + (inferPickup ? " implicit=\"yes\"" : "") + ">";
      measures.appendReplacement(
          output, java.util.regex.Matcher.quoteReplacement(open + body + "</measure>"));
    }
    measures.appendTail(output);
    return output.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Removes only the standard external MusicXML declaration; DTDs are never fetched. */
  private static byte[] stripCanonicalMusicXmlDoctype(byte[] xml) throws IOException {
    String text = new String(xml, StandardCharsets.UTF_8);
    int start = text.indexOf("<!DOCTYPE");
    if (start < 0) return xml;
    int end = text.indexOf('>', start);
    if (end < 0) throw new IOException("Invalid MusicXML DOCTYPE");
    String declaration = text.substring(start, end + 1);
    if (declaration.contains("[")
        || !declaration.matches(
            "(?s)<!DOCTYPE\\s+score-partwise\\s+PUBLIC\\s+['\\\"]-//Recordare//DTD MusicXML 4\\.0"
                + " Partwise//EN['\\\"]\\s+['\\\"]https?://www\\.musicxml\\.org/dtds/partwise\\.dtd['\\\"]\\s*>"))
      throw new IOException("Only the standard external MusicXML 4.0 Partwise DTD is accepted");
    return (text.substring(0, start) + text.substring(end + 1)).getBytes(StandardCharsets.UTF_8);
  }

  /** Opens a MuseScore compressed score after validating and converting its MSCX document. */
  public ImportedScore readMscz(byte[] archive, String filename) throws IOException {
    if (filename == null || !filename.matches("[^/\\\\:]+\\.(?i:mscz)"))
      throw new IOException("Use a .mscz filename without a path");
    if (archive.length > MAX_BYTES) throw new IOException("MSCZ exceeds the 128 MiB limit");
    byte[] score = null;
    long expanded = 0;
    int count = 0;
    var paths = new HashSet<String>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (++count > MAX_MXL_ENTRIES) throw new IOException("MSCZ contains too many ZIP entries");
        validateZipPath(entry.getName());
        if (!paths.add(entry.getName())) throw new IOException("MSCZ contains duplicate ZIP paths");
        if (entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mscx")) {
          if (score != null) throw new IOException("MSCZ must contain exactly one MSCX score");
          score = readBounded(zip, MAX_MXL_EXPANDED_BYTES);
          expanded += score.length;
        } else expanded += drainBounded(zip, MAX_MXL_EXPANDED_BYTES);
        if (expanded > MAX_MXL_EXPANDED_BYTES)
          throw new IOException("MSCZ expands beyond the 128 MiB limit");
        zip.closeEntry();
      }
    } catch (java.util.zip.ZipException failure) {
      throw new IOException("Invalid or unsupported MSCZ archive", failure);
    }
    if (score == null || score.length == 0) throw new IOException("MSCZ has no MSCX score");
    byte[] musicXml = MuseScoreConverter.toMusicXml(score);
    ImportedScore parsed = read(musicXml, "converted.musicxml");
    return new ImportedScore(
        parsed.score(),
        filename,
        HexFormat.of().formatHex(sha256(archive)),
        "mscz-v1+" + parsed.parserVersion(),
        parsed.keyId(),
        parsed.instruments());
  }

  private static byte[] readBounded(InputStream source, int limit) throws IOException {
    var output = new ByteArrayOutputStream(Math.min(limit, 8192));
    byte[] buffer = new byte[8192];
    int total = 0;
    int read;
    while ((read = source.read(buffer)) != -1) {
      if (read > limit - total) throw new IOException("MXL entry exceeds its size limit");
      output.write(buffer, 0, read);
      total += read;
    }
    return output.toByteArray();
  }

  private static long drainBounded(InputStream source, long limit) throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = source.read(buffer)) != -1) {
      if (read > limit - total) throw new IOException("MXL entry exceeds its size limit");
      total += read;
    }
    return total;
  }

  private static void validateZipPath(String name) throws IOException {
    if (name == null
        || name.isBlank()
        || name.startsWith("/")
        || name.startsWith("\\")
        || name.indexOf('\\') >= 0
        || name.indexOf(':') >= 0
        || name.indexOf('\0') >= 0) throw new IOException("MXL contains an unsafe ZIP path");
    String[] segments = name.split("/", -1);
    for (int index = 0; index < segments.length; index++) {
      String segment = segments[index];
      if (segment.equals(".")
          || segment.equals("..")
          || segment.isEmpty() && !(name.endsWith("/") && index == segments.length - 1))
        throw new IOException("MXL contains an unsafe ZIP path");
    }
  }

  private static String parseContainerRoot(byte[] bytes) throws IOException {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
      Element root = document.getDocumentElement();
      if (root == null || !localName(root).equals("container"))
        throw new IOException("Invalid MXL container manifest");
      Element rootfiles = directChild(root, "rootfiles");
      Element first = rootfiles == null ? null : directChild(rootfiles, "rootfile");
      if (first == null) throw new IOException("MXL manifest has no rootfile");
      String mediaType = first.getAttribute("media-type");
      if (!mediaType.isEmpty()
          && !mediaType.equals("application/vnd.recordare.musicxml+xml")
          && !mediaType.equals("application/vnd.recordare.musicxml"))
        throw new IOException("MXL first rootfile is not MusicXML");
      String path = first.getAttribute("full-path");
      validateZipPath(path);
      if (path.endsWith("/")) throw new IOException("MXL rootfile must name a file");
      return path;
    } catch (ParserConfigurationException | SAXException | IllegalArgumentException failure) {
      throw new IOException("Invalid MXL container manifest", failure);
    }
  }

  private static Element directChild(Element parent, String expectedName) {
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element element && localName(element).equals(expectedName))
        return element;
    return null;
  }

  private static String localName(Element element) {
    return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static final class LimitedInputStream extends FilterInputStream {
    private final long limit;
    private long count;

    private LimitedInputStream(InputStream source, long limit) {
      super(source);
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0 && ++count > limit)
        throw new IOException("MusicXML exceeds the 128 MiB limit");
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int value = super.read(buffer, offset, length);
      if (value > 0 && (count += value) > limit)
        throw new IOException("MusicXML exceeds the 128 MiB limit");
      return value;
    }
  }

  private ParsedPart parsePart(
      Element part, String id, String name, InstrumentProfile profile, int[] scoreEvents) {
    try {
      return parsePartContents(part, id, name, profile, scoreEvents);
    } catch (IllegalArgumentException | ArithmeticException failure) {
      throw new IllegalArgumentException(
          "Part " + partLabel(name) + " (" + id + "): " + failure.getMessage(), failure);
    }
  }

  private ParsedPart parsePartContents(
      Element part, String id, String name, InstrumentProfile profile, int[] scoreEvents) {
    allow(part, Set.of("measure"), Set.of("id"));
    int divisions = 0;
    TimeSignature meter = null;
    String keyId = null;
    boolean transposeSeen = false;
    var measures = new ArrayList<Measure>();
    var repeatRegions = new ArrayList<RepeatRegion>();
    var endingRegions = new ArrayList<EndingRegion>();
    int openRepeatStart = -1;
    int openEndingStart = -1;
    Set<Integer> openEndingPasses = Set.of();
    var measureElements =
        childrenBounded(
            part, "measure", MAX_MEASURES_PER_PART, "Part exceeds the 2048-measure limit");
    for (Element measure : measureElements) {
      String measureNumber = measure.getAttribute("number");
      if (measureNumber.isBlank()) measureNumber = Integer.toString(measures.size() + 1);
      try {
        allow(
            measure,
            Set.of(
                "attributes",
                "note",
                "sound",
                "backup",
                "forward",
                "barline",
                "print",
                "direction"),
            Set.of("number", "implicit"));
        Optional<Tempo> tempo = Optional.empty();
        var voiceEvents = new LinkedHashMap<String, List<TimedEvent>>();
        ScoreOffset cursor = ScoreOffset.zero();
        boolean explicitCursorMovement = false;
        for (Node child = measure.getFirstChild(); child != null; child = child.getNextSibling()) {
          if (!(child instanceof Element element)) continue;
          switch (element.getTagName()) {
            case "attributes" -> {
              if (!voiceEvents.isEmpty() || cursor.compareTo(ScoreOffset.zero()) != 0)
                throw new IllegalArgumentException("Mid-measure attributes unsupported");
              allow(
                  element,
                  Set.of(
                      "divisions",
                      "time",
                      "key",
                      "transpose",
                      "clef",
                      "staff-details",
                      "staves",
                      "part-symbol",
                      "instruments"),
                  Set.of());
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
                String mode =
                    optional(key.get(), "mode").map(MusicXmlImporter::text).orElse("major");
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
              if (!voiceEvents.isEmpty()
                  || cursor.compareTo(ScoreOffset.zero()) != 0
                  || tempo.isPresent())
                throw new IllegalArgumentException(
                    "Only one starting tempo per measure is supported");
              tempo = Optional.of(new Tempo(Double.parseDouble(element.getAttribute("tempo"))));
            }
            case "print", "direction" -> {
              /* layout, rehearsal text and non-tempo directions */
            }
            case "barline" -> {
              allow(
                  element, Set.of("bar-style", "repeat", "ending", "fermata"), Set.of("location"));
              String location = element.getAttribute("location");
              if (location.isEmpty()) location = "right";
              if (!Set.of("left", "middle", "right").contains(location))
                throw new IllegalArgumentException("Invalid barline location");
              for (Node markerNode = element.getFirstChild();
                  markerNode != null;
                  markerNode = markerNode.getNextSibling()) {
                if (!(markerNode instanceof Element marker)) continue;
                switch (marker.getTagName()) {
                  case "bar-style" -> allow(marker, Set.of(), Set.of());
                  case "fermata" -> {
                    /* expressive mark; timing is unchanged */
                  }
                  case "repeat" -> {
                    allow(marker, Set.of(), Set.of("direction", "times"));
                    String direction = marker.getAttribute("direction");
                    if (direction.equals("forward")) {
                      if (openRepeatStart >= 0)
                        throw new IllegalArgumentException("Nested repeats are not supported");
                      if (location.equals("middle"))
                        throw new IllegalArgumentException("Mid-measure repeats are not supported");
                      if (marker.hasAttribute("times"))
                        throw new IllegalArgumentException("Forward repeat cannot declare times");
                      openRepeatStart = measures.size() + (location.equals("left") ? 0 : 1);
                    } else if (direction.equals("backward")) {
                      if (location.equals("middle"))
                        throw new IllegalArgumentException("Mid-measure repeats are not supported");
                      int end = measures.size() - (location.equals("left") ? 1 : 0);
                      int start = openRepeatStart < 0 ? 0 : openRepeatStart;
                      int passes =
                          marker.hasAttribute("times")
                              ? Integer.parseInt(marker.getAttribute("times"))
                              : 2;
                      repeatRegions.add(new RepeatRegion(start, end, passes));
                      openRepeatStart = -1;
                    } else throw new IllegalArgumentException("Unsupported repeat direction");
                  }
                  case "ending" -> {
                    allow(marker, Set.of(), Set.of("number", "type"));
                    Set<Integer> passes = parseEndingPasses(marker.getAttribute("number"));
                    String type = marker.getAttribute("type");
                    if (type.equals("start")) {
                      if (openEndingStart >= 0)
                        throw new IllegalArgumentException("Nested endings are not supported");
                      if (location.equals("middle"))
                        throw new IllegalArgumentException("Mid-measure endings are not supported");
                      openEndingStart = measures.size() + (location.equals("left") ? 0 : 1);
                      openEndingPasses = passes;
                    } else if (type.equals("stop") || type.equals("discontinue")) {
                      if (openEndingStart < 0 || !openEndingPasses.equals(passes))
                        throw new IllegalArgumentException("Ending stop does not match its start");
                      if (location.equals("middle"))
                        throw new IllegalArgumentException("Mid-measure endings are not supported");
                      int end = measures.size() - (location.equals("left") ? 1 : 0);
                      endingRegions.add(new EndingRegion(openEndingStart, end, openEndingPasses));
                      openEndingStart = -1;
                      openEndingPasses = Set.of();
                    } else throw new IllegalArgumentException("Unsupported ending marker");
                  }
                  default -> throw new IllegalArgumentException("Unsupported barline element");
                }
              }
            }
            case "backup", "forward" -> {
              explicitCursorMovement = true;
              if (divisions == 0 || meter == null)
                throw new IllegalArgumentException(
                    "Explicit divisions and meter required before timing");
              Set<String> allowedChildren =
                  element.getTagName().equals("forward")
                      ? Set.of("duration", "voice", "staff")
                      : Set.of("duration");
              allow(element, allowedChildren, Set.of());
              if (element.getTagName().equals("forward")) {
                optional(element, "voice");
                var staff = optional(element, "staff");
                if (staff.isPresent() && !text(staff.orElseThrow()).equals("1"))
                  throw new IllegalArgumentException("Multiple staves are not supported");
              }
              Duration shift =
                  new Duration(Long.parseLong(text(one(element, "duration"))), 4L * divisions);
              cursor =
                  element.getTagName().equals("backup") ? cursor.minus(shift) : cursor.plus(shift);
              ScoreOffset measureLimit =
                  new ScoreOffset(
                      meter.measureDuration().numerator(), meter.measureDuration().denominator());
              if (cursor.compareTo(measureLimit) > 0)
                throw new IllegalArgumentException("Voice cursor exceeds measure duration");
            }
            case "note" -> {
              if (divisions == 0 || meter == null)
                throw new IllegalArgumentException(
                    "Explicit divisions and meter required before notes");
              if (++scoreEvents[0] > MAX_SCORE_EVENTS)
                throw new IllegalArgumentException(
                    "note " + scoreEvents[0] + " exceeds the 16384-event score limit");
              try {
                ParsedEvent parsed = parseEvent(element, divisions);
                var events =
                    voiceEvents.computeIfAbsent(parsed.voiceId(), ignored -> new ArrayList<>());
                if (parsed.chordHead()) {
                  if (!(parsed.event() instanceof Note note) || events.isEmpty())
                    throw new IllegalArgumentException("Chord head needs a preceding note");
                  TimedEvent previousTimed = events.getLast();
                  if (!previousTimed.onset().plus(previousTimed.event().duration()).equals(cursor))
                    throw new IllegalArgumentException(
                        "Chord head must share the preceding note onset");
                  ScoreEvent previous = previousTimed.event();
                  if (previous.duration().compareTo(note.duration()) != 0)
                    throw new IllegalArgumentException("Chord notes must have the same duration");
                  if (previous instanceof Note first)
                    events.set(
                        events.size() - 1,
                        new TimedEvent(previousTimed.onset(), new Chord(List.of(first, note))));
                  else if (previous instanceof Chord chord) {
                    var heads = new ArrayList<>(chord.notes());
                    heads.add(note);
                    events.set(
                        events.size() - 1, new TimedEvent(previousTimed.onset(), new Chord(heads)));
                  } else throw new IllegalArgumentException("Chord head cannot follow a rest");
                } else {
                  events.add(new TimedEvent(cursor, parsed.event()));
                  cursor = cursor.plus(parsed.event().duration());
                }
              } catch (IllegalArgumentException | ArithmeticException failure) {
                throw new IllegalArgumentException(
                    "note " + scoreEvents[0] + ": " + failure.getMessage(), failure);
              }
            }
            default -> throw new IllegalArgumentException("Unsupported measure element");
          }
        }
        String implicit = measure.getAttribute("implicit");
        if (!implicit.isEmpty() && !implicit.equals("yes") && !implicit.equals("no"))
          throw new IllegalArgumentException("Invalid implicit attribute");
        if (meter == null) throw new IllegalArgumentException("Missing meter");
        boolean independentlyTimed = explicitCursorMovement || voiceEvents.size() > 1;
        var voices =
            voiceEvents.entrySet().stream()
                .map(entry -> new Voice(entry.getKey(), entry.getValue(), independentlyTimed))
                .toList();
        measures.add(
            Measure.withVoices(
                measure.getAttribute("number"), meter, tempo, voices, implicit.equals("yes")));
      } catch (IllegalArgumentException | ArithmeticException failure) {
        throw new IllegalArgumentException(
            "measure " + measureNumber + ": " + failure.getMessage(), failure);
      }
    }
    // Some MuseScore exports use a trailing forward marker with no matching backward marker.
    if (openEndingStart >= 0) throw new IllegalArgumentException("Unclosed ending");
    if (profile.soundingTranspositionSemitones() != 0 && !transposeSeen)
      throw new IllegalArgumentException("Transposing instrument needs MusicXML transpose");
    if (keyId != null)
      keyId = MusicalKey.get(keyId).transpose(profile.soundingTranspositionSemitones()).id();
    return new ParsedPart(
        new Part(id, name, measures),
        keyId,
        List.copyOf(repeatRegions),
        List.copyOf(endingRegions));
  }

  private static Set<Integer> parseEndingPasses(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Ending number required");
    var passes = new java.util.TreeSet<Integer>();
    for (String item : value.split(",")) {
      String token = item.trim();
      if (!token.matches("[1-8]")) throw new IllegalArgumentException("Invalid ending number");
      passes.add(Integer.parseInt(token));
    }
    return Set.copyOf(passes);
  }

  private ParsedEvent parseEvent(Element note, int divisions) {
    allow(
        note,
        Set.of(
            "chord",
            "pitch",
            "rest",
            "duration",
            "voice",
            "type",
            "dot",
            "tie",
            "notations",
            "grace",
            "accidental",
            "beam",
            "stem",
            "instrument",
            "staff",
            "cue",
            "lyric",
            "play",
            "listen"),
        Set.of(
            "default-x",
            "default-y",
            "dynamics",
            "print-object",
            "print-spacing",
            "font-family",
            "font-size",
            "color"));
    var chordMarker = optional(note, "chord");
    if (chordMarker.isPresent()) allow(chordMarker.orElseThrow(), Set.of(), Set.of());
    var voice = optional(note, "voice");
    String voiceId = voice.map(MusicXmlImporter::text).orElse("1");
    if (voiceId.isBlank() || voiceId.length() > 64)
      throw new IllegalArgumentException("Invalid voice ID");
    boolean[] soundTies = tieFlags(children(note, "tie"));
    var notations = optional(note, "notations");
    boolean[] visualTies = new boolean[2];
    if (notations.isPresent()) {
      allow(
          notations.orElseThrow(),
          Set.of(
              "tied",
              "slur",
              "ornaments",
              "articulations",
              "fermata",
              "tuplet",
              "technical",
              "dynamics",
              "arpeggiate"),
          Set.of());
      for (Element tied : children(notations.orElseThrow(), "tied")) {
        int type = tieType(tied);
        if (visualTies[type]) throw new IllegalArgumentException("Duplicate visual tie type");
        visualTies[type] = true;
      }
    }
    if (notations.isPresent() && !java.util.Arrays.equals(soundTies, visualTies))
      throw new IllegalArgumentException("Sound and notation ties must match");
    var duration = new Duration(Long.parseLong(text(one(note, "duration"))), 4L * divisions);
    validateNotatedDuration(note, duration);
    var rest = optional(note, "rest");
    var pitch = optional(note, "pitch");
    if (rest.isPresent() == pitch.isPresent())
      throw new IllegalArgumentException("Note must have exactly one pitch or rest");
    if (rest.isPresent()) {
      allow(rest.get(), Set.of(), Set.of());
      if (soundTies[0] || soundTies[1])
        throw new IllegalArgumentException("A rest cannot carry a tie");
      if (chordMarker.isPresent())
        throw new IllegalArgumentException("A rest cannot be a chord head");
      return new ParsedEvent(new Rest(duration), false, voiceId);
    }
    var p = pitch.orElseThrow();
    allow(p, Set.of("step", "alter", "octave"), Set.of());
    int alter = optional(p, "alter").map(MusicXmlImporter::text).map(Integer::parseInt).orElse(0);
    return new ParsedEvent(
        new Note(
            new Pitch(
                PitchClass.valueOf(text(one(p, "step"))),
                Accidental.fromSemitones(alter),
                Integer.parseInt(text(one(p, "octave")))),
            duration,
            soundTies[0],
            soundTies[1]),
        chordMarker.isPresent(),
        voiceId);
  }

  private static boolean[] tieFlags(List<Element> ties) {
    boolean[] flags = new boolean[2];
    for (Element tie : ties) {
      int type = tieType(tie);
      if (flags[type]) throw new IllegalArgumentException("Duplicate sound tie type");
      flags[type] = true;
    }
    return flags;
  }

  private static int tieType(Element tie) {
    allow(tie, Set.of(), Set.of("type"));
    if (!tie.getTextContent().isBlank())
      throw new IllegalArgumentException("Tie marker must be empty");
    return switch (tie.getAttribute("type")) {
      case "start" -> 0;
      case "stop" -> 1;
      default -> throw new IllegalArgumentException("Tie type must be start or stop");
    };
  }

  private static String partLabel(String name) {
    String prefix = name.substring(0, Math.min(name.length(), 80)).replaceAll("\\s+", " ").trim();
    return prefix + (name.length() > 80 ? "…" : "");
  }

  private static void allow(Element parent, Set<String> tags, Set<String> attributes) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element child && !tags.contains(child.getTagName()))
        throw new IllegalArgumentException("Unsupported element: " + child.getTagName());
    var attrs = parent.getAttributes();
    for (int i = 0; i < attrs.getLength(); i++)
      if (!attributes.contains(attrs.item(i).getNodeName())
          && !Set.of(
                  "print-object",
                  "print-spacing",
                  "default-x",
                  "default-y",
                  "relative-x",
                  "relative-y",
                  "font-family",
                  "font-size",
                  "font-style",
                  "font-weight",
                  "color",
                  "width",
                  "height",
                  "new-system",
                  "new-page",
                  "page",
                  "justify",
                  "valign",
                  "orientation",
                  "placement",
                  "rotation")
              .contains(attrs.item(i).getNodeName()))
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

  private static List<Element> childrenBounded(
      Element parent, String tag, int max, String limitError) {
    var result = new ArrayList<Element>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element element) || !element.getTagName().equals(tag)) continue;
      if (result.size() == max) throw new IllegalArgumentException(limitError);
      result.add(element);
    }
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

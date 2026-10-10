package org.arpeon.musicxml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Converts the bounded, single-staff MSCX subset used by MuseScore scores to MusicXML. */
final class MuseScoreConverter {
  private static final Map<Integer, String[]> TPC =
      Map.ofEntries(
          Map.entry(7, new String[] {"C", "-1"}), Map.entry(8, new String[] {"G", "-1"}),
          Map.entry(9, new String[] {"D", "-1"}), Map.entry(10, new String[] {"A", "-1"}),
          Map.entry(11, new String[] {"E", "-1"}), Map.entry(12, new String[] {"B", "-1"}),
          Map.entry(13, new String[] {"F", "0"}), Map.entry(14, new String[] {"C", "0"}),
          Map.entry(15, new String[] {"G", "0"}), Map.entry(16, new String[] {"D", "0"}),
          Map.entry(17, new String[] {"A", "0"}), Map.entry(18, new String[] {"E", "0"}),
          Map.entry(19, new String[] {"B", "0"}), Map.entry(20, new String[] {"F", "1"}),
          Map.entry(21, new String[] {"C", "1"}), Map.entry(22, new String[] {"G", "1"}),
          Map.entry(23, new String[] {"D", "1"}), Map.entry(24, new String[] {"A", "1"}),
          Map.entry(25, new String[] {"E", "1"}), Map.entry(26, new String[] {"B", "1"}));

  private MuseScoreConverter() {}

  static byte[] toMusicXml(byte[] mscx) throws IOException {
    if (mscx.length > MusicXmlImporter.MAX_BYTES)
      throw new IOException("MSCX exceeds the size limit");
    MusicXmlImporter.validateXmlInput(mscx);
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      Element root =
          factory.newDocumentBuilder().parse(new ByteArrayInputStream(mscx)).getDocumentElement();
      if (!root.getTagName().equals("museScore"))
        throw new IOException("MSCZ does not contain a MuseScore document");
      Element score = direct(root, "Score");
      if (score == null) throw new IOException("MuseScore score data is missing");
      List<Element> staves = children(score, "Staff");
      if (staves.size() != 1) throw new IOException("MSCZ import currently supports one staff");
      Element staff = staves.getFirst();
      List<Element> measures = children(staff, "Measure");
      if (measures.isEmpty() || measures.size() > MusicXmlImporter.MAX_MEASURES_PER_PART)
        throw new IOException("MSCZ must contain between 1 and 2048 measures");
      String title = meta(score, "workTitle");
      StringBuilder xml = new StringBuilder(32_768);
      xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><score-partwise version=\"4.0\">");
      if (!title.isBlank())
        xml.append("<movement-title>").append(escape(title)).append("</movement-title>");
      xml.append("<part-list><score-part id=\"P1\"><part-name>")
          .append(escape(trackName(score)))
          .append(
              "</part-name><score-instrument"
                  + " id=\"I1\"><instrument-name>Flute</instrument-name></score-instrument><midi-instrument"
                  + " id=\"I1\"><midi-program>74</midi-program></midi-instrument></score-part></part-list><part"
                  + " id=\"P1\">");
      int divisions = integer(direct(score, "Division"), 480);
      if (divisions < 1 || divisions > 1_000_000)
        throw new IOException("Invalid MuseScore division value");
      int fifths = 0, beats = 4, beatType = 4;
      for (int i = 0; i < measures.size(); i++) {
        Element measure = measures.get(i);
        List<Element> voiceContainers = children(measure, "voice");
        if (voiceContainers.size() != 1) throw new IOException("MSCZ measure must have one voice");
        List<Element> events = children(voiceContainers.getFirst());
        StringBuilder content = new StringBuilder();
        Element startRepeat = direct(measure, "startRepeat");
        Element endRepeat = direct(measure, "endRepeat");
        if (startRepeat != null)
          content.append("<barline location=\"left\"><repeat direction=\"forward\"/></barline>");
        long totalTicks = 0;
        for (Element event : events) {
          switch (event.getTagName()) {
            case "KeySig" -> fifths = keyFifths(integer(direct(event, "concertKey"), 14));
            case "TimeSig" -> {
              beats = integer(direct(event, "sigN"), beats);
              beatType = integer(direct(event, "sigD"), beatType);
            }
            case "Beam", "Fermata" -> {}
            case "startRepeat" ->
                content.append(
                    "<barline location=\"left\"><repeat direction=\"forward\"/></barline>");
            case "endRepeat" ->
                content
                    .append("<barline location=\"right\"><repeat direction=\"backward\" times=\"")
                    .append(Math.max(2, integer(event, 2)))
                    .append("\"/></barline>");
            case "Chord", "Rest" -> {
              if (direct(event, "appoggiatura") != null) break;
              String durationType = text(direct(event, "durationType"));
              int denominator = durationDenominator(durationType);
              long ticks = 4L * divisions / denominator;
              if (ticks < 1) throw new IOException("Unsupported short MuseScore duration");
              List<Element> notes =
                  event.getTagName().equals("Rest") ? List.of() : children(event, "Note");
              if (notes.isEmpty() && event.getTagName().equals("Chord"))
                throw new IOException("MuseScore chord has no notes");
              int count = Math.max(1, notes.size());
              for (int n = 0; n < count; n++) {
                content.append("<note>");
                if (n > 0) content.append("<chord/>");
                if (notes.isEmpty()) content.append("<rest/>");
                else appendPitch(content, notes.get(n));
                content
                    .append("<duration>")
                    .append(ticks)
                    .append("</duration><type>")
                    .append(durationType)
                    .append("</type></note>");
              }
              totalTicks += ticks;
            }
            default ->
                throw new IOException(
                    "Unsupported MuseScore measure element: " + event.getTagName());
          }
        }
        if (endRepeat != null)
          content
              .append("<barline location=\"right\"><repeat direction=\"backward\" times=\"")
              .append(Math.max(2, integer(endRepeat, 2)))
              .append("\"/></barline>");
        long meterTicks = 4L * divisions * beats / beatType;
        boolean pickup = totalTicks != meterTicks;
        xml.append("<measure number=\"").append(i + 1).append("\"");
        if (pickup) xml.append(" implicit=\"yes\"");
        xml.append("><attributes>");
        if (i == 0)
          xml.append("<divisions>")
              .append(divisions)
              .append("</divisions><key><fifths>")
              .append(fifths)
              .append("</fifths></key><time><beats>")
              .append(beats)
              .append("</beats><beat-type>")
              .append(beatType)
              .append("</beat-type></time>");
        xml.append("</attributes>").append(content).append("</measure>");
      }
      xml.append("</part></score-partwise>");
      return xml.toString().getBytes(StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IOException(
          "Invalid or unsupported MuseScore document: " + failure.getMessage(), failure);
    }
  }

  private static void appendPitch(StringBuilder xml, Element note) throws IOException {
    int midi = integer(direct(note, "pitch"), -1);
    String[] spelling = TPC.get(integer(direct(note, "tpc"), -1));
    if (midi < 0 || midi > 127 || spelling == null)
      throw new IOException("Unsupported MuseScore pitch spelling");
    int alter = Integer.parseInt(spelling[1]);
    int natural =
        switch (spelling[0]) {
          case "C" -> 0;
          case "D" -> 2;
          case "E" -> 4;
          case "F" -> 5;
          case "G" -> 7;
          case "A" -> 9;
          default -> 11;
        };
    int octave = (midi - natural - alter) / 12 - 1;
    if ((octave + 1) * 12 + natural + alter != midi)
      throw new IOException("MuseScore pitch and spelling disagree");
    xml.append("<pitch><step>")
        .append(spelling[0])
        .append("</step><alter>")
        .append(alter)
        .append("</alter><octave>")
        .append(octave)
        .append("</octave></pitch>");
    for (Element spanner : children(note, "Spanner")) {
      if (!spanner.getAttribute("type").equals("Tie")) continue;
      boolean start = direct(spanner, "Tie") != null;
      boolean stop = direct(spanner, "prev") != null;
      if (start) xml.append("<tie type=\"start\"/>");
      if (stop) xml.append("<tie type=\"stop\"/>");
      if (start || stop) {
        xml.append("<notations>");
        if (start) xml.append("<tied type=\"start\"/>");
        if (stop) xml.append("<tied type=\"stop\"/>");
        xml.append("</notations>");
      }
    }
  }

  private static int durationDenominator(String value) throws IOException {
    return switch (value) {
      case "whole" -> 1;
      case "half" -> 2;
      case "quarter" -> 4;
      case "eighth" -> 8;
      case "16th" -> 16;
      case "32nd" -> 32;
      case "64th" -> 64;
      default -> throw new IOException("Unsupported MuseScore duration: " + value);
    };
  }

  private static int keyFifths(int key) throws IOException {
    if (key < -7 || key > 7) throw new IOException("Unsupported MuseScore key signature");
    return key;
  }

  private static String trackName(Element score) {
    var parts = children(score, "Part");
    if (!parts.isEmpty()) {
      Element name = direct(parts.getFirst(), "trackName");
      if (name != null && !text(name).isBlank()) return text(name);
    }
    return "Flute";
  }

  private static String meta(Element score, String name) {
    for (Element tag : descendants(score, "metaTag"))
      if (tag.getAttribute("name").equals(name)) return text(tag);
    return "";
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static int integer(Element element, int fallback) {
    try {
      return element == null ? fallback : Integer.parseInt(text(element));
    } catch (NumberFormatException bad) {
      return fallback;
    }
  }

  private static String text(Element element) {
    return element == null ? "" : element.getTextContent().trim();
  }

  private static Element direct(Element parent, String name) {
    for (Node n = parent == null ? null : parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e && e.getTagName().equals(name)) return e;
    return null;
  }

  private static List<Element> children(Element parent, String name) {
    var found = new java.util.ArrayList<Element>();
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e && (name == null || e.getTagName().equals(name))) found.add(e);
    return found;
  }

  private static List<Element> children(Element parent) {
    return children(parent, null);
  }

  private static List<Element> descendants(Element parent, String name) {
    var found = new java.util.ArrayList<Element>();
    var nodes = parent.getElementsByTagName(name);
    for (int i = 0; i < nodes.getLength(); i++) found.add((Element) nodes.item(i));
    return found;
  }
}

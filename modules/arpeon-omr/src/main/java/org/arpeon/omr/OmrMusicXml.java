package org.arpeon.omr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.arpeon.musicxml.MusicXmlImporter;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/** Removes presentation-only MusicXML while rejecting unsupported musical structure. */
public final class OmrMusicXml {
  public record Normalized(byte[] xml, List<String> warnings, List<String> unsupportedSymbols) {}

  private OmrMusicXml() {}

  public static Normalized normalize(byte[] source) throws IOException {
    if (source.length == 0 || source.length > MusicXmlImporter.MAX_BYTES)
      throw new IOException("Audiveris MusicXML exceeds 128 MiB");
    validateUtf8(source);
    byte[] xmlBytes = source;
    int start = indexOf(xmlBytes, "<!DOCTYPE".getBytes(StandardCharsets.US_ASCII), 0);
    if (start >= 0) {
      int end = indexOf(xmlBytes, (byte) '>', start);
      if (end < 0 || end - start > 300) throw new IOException("Unsupported MusicXML DTD");
      String dtd = new String(xmlBytes, start, end - start + 1, StandardCharsets.US_ASCII);
      if (!dtd.matches(
              "(?s)<!DOCTYPE\\s+score-partwise\\s+PUBLIC\\s+\"-//Recordare//DTD MusicXML"
                  + " [^\"]+\"\\s+\"[^\"]+\"\\s*>")
          || dtd.contains("[")) throw new IOException("Unsupported MusicXML DTD");
      xmlBytes = removeRange(xmlBytes, start, end + 1);
    }
    if (indexOf(xmlBytes, "<!ENTITY".getBytes(StandardCharsets.US_ASCII), 0) >= 0
        || indexOf(xmlBytes, "<!DOCTYPE".getBytes(StandardCharsets.US_ASCII), 0) >= 0)
      throw new IOException("MusicXML entities and DTDs are not accepted");
    try {
      MusicXmlImporter.validateXmlInput(xmlBytes);
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setAttribute("jdk.xml.maxElementDepth", "48");
      factory.setAttribute("jdk.xml.elementAttributeLimit", "32");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmlBytes));
      Element root = document.getDocumentElement();
      if (!root.getTagName().equals("score-partwise"))
        throw new IOException("Audiveris did not export score-partwise MusicXML");
      var warnings = new LinkedHashSet<String>();
      var unsupported = new LinkedHashSet<String>();
      for (Element child : children(root)) {
        switch (child.getTagName()) {
          case "work", "part-list", "part" -> {}
          case "movement-title", "movement-number", "identification", "defaults", "credit" -> {
            root.removeChild(child);
            warnings.add("Metadados e diagramação não entram na partitura editável.");
          }
          default -> fail(child);
        }
      }
      retainAttributes(root, Set.of("version"));
      root.setAttribute("version", "4.0");
      var work = optional(root, "work");
      if (work != null) retain(work, Set.of("work-title"));
      for (Element declaration : children(first(root, "part-list"))) {
        if (declaration.getTagName().equals("part-group")) {
          declaration.getParentNode().removeChild(declaration);
          warnings.add("Agrupamentos de pautas não são preservados.");
        } else if (declaration.getTagName().equals("score-part")) {
          retainAttributes(declaration, Set.of("id"));
          for (Element item : children(declaration)) {
            if (!Set.of("part-name", "score-instrument", "midi-instrument")
                .contains(item.getTagName())) declaration.removeChild(item);
            else if (item.getTagName().equals("score-instrument"))
              retain(item, Set.of("instrument-name"));
            else if (item.getTagName().equals("midi-instrument"))
              retain(item, Set.of("midi-program"));
          }
          var instrument = optional(declaration, "score-instrument");
          var midi = optional(declaration, "midi-instrument");
          if (instrument != null && midi != null) {
            String program = first(midi, "midi-program").getTextContent().trim();
            if (!program.equals("74") && !program.equals("72")) {
              declaration.removeChild(instrument);
              declaration.removeChild(midi);
              warnings.add("Instrumento MIDI não mapeado; revisar instrumento na partitura.");
            } else {
              String id = instrument.getAttribute("id");
              midi.setAttribute("id", id);
            }
          } else {
            if (instrument != null) declaration.removeChild(instrument);
            if (midi != null) declaration.removeChild(midi);
          }
        } else fail(declaration);
      }
      for (Element part : children(root, "part")) {
        retainAttributes(part, Set.of("id"));
        for (Element measure : children(part)) {
          if (!measure.getTagName().equals("measure")) fail(measure);
          retainAttributes(measure, Set.of("number", "implicit"));
          for (Element item : children(measure)) {
            switch (item.getTagName()) {
              case "print", "direction" -> {
                measure.removeChild(item);
                warnings.add(
                    "Indicações de layout, texto e dinâmica precisam de revisão no original.");
                unsupported.add(item.getTagName());
              }
              case "barline" -> {
                retainAttributes(item, Set.of("location"));
                for (Element marker : children(item)) {
                  if (marker.getTagName().equals("bar-style")) item.removeChild(marker);
                  else if (marker.getTagName().equals("repeat"))
                    retainAttributes(marker, Set.of("direction", "times"));
                  else if (marker.getTagName().equals("ending"))
                    retainAttributes(marker, Set.of("number", "type"));
                  else fail(marker);
                }
              }
              case "backup", "forward" -> {
                Set<String> allowedChildren =
                    item.getTagName().equals("forward")
                        ? Set.of("duration", "voice", "staff")
                        : Set.of("duration");
                for (Element child : children(item))
                  if (!allowedChildren.contains(child.getTagName())) fail(child);
                var staff = optional(item, "staff");
                if (staff != null && !staff.getTextContent().trim().equals("1")) fail(staff);
                retainAttributes(item, Set.of());
              }
              case "attributes" -> {
                for (Element attribute : children(item)) {
                  if (Set.of("clef", "staves", "staff-details").contains(attribute.getTagName()))
                    item.removeChild(attribute);
                  else if (!Set.of("divisions", "key", "time", "transpose")
                      .contains(attribute.getTagName())) fail(attribute);
                }
                retainAttributes(item, Set.of());
                for (Element attribute : children(item)) retainAttributes(attribute, Set.of());
              }
              case "sound" -> {
                if (!item.hasAttribute("tempo")) {
                  measure.removeChild(item);
                  unsupported.add("sound");
                } else retainAttributes(item, Set.of("tempo"));
              }
              case "note" -> {
                retainAttributes(item, Set.of());
                var tupletTiming = optional(item, "time-modification");
                if (tupletTiming != null) {
                  for (Element timing : children(tupletTiming))
                    if (!Set.of("actual-notes", "normal-notes", "normal-type", "normal-dot")
                        .contains(timing.getTagName())) fail(timing);
                  int actual =
                      Integer.parseInt(first(tupletTiming, "actual-notes").getTextContent().trim());
                  int normal =
                      Integer.parseInt(first(tupletTiming, "normal-notes").getTextContent().trim());
                  if (actual < 1 || actual > 128 || normal < 1 || normal > 128)
                    throw new IOException("Proporção de quiáltera inválida no reconhecimento.");
                  // The domain keeps exact durations; tuplets' visual grouping is not represented.
                  item.removeChild(tupletTiming);
                  for (Element visual : children(item))
                    if (Set.of("type", "dot").contains(visual.getTagName()))
                      item.removeChild(visual);
                  unsupported.add("tuplet");
                  warnings.add(
                      "Quiálteras: durações reconhecidas preservadas em frações; números e"
                          + " colchetes de agrupamento não são preservados. Confira esses trechos"
                          + " no original.");
                }
                for (Element noteChild : children(item)) {
                  switch (noteChild.getTagName()) {
                    case "chord", "pitch", "rest", "duration", "voice", "type", "dot" -> {
                      if (noteChild.getTagName().equals("rest")) {
                        retainAttributes(noteChild, Set.of());
                        for (Element restChild : children(noteChild)) {
                          if (!Set.of("display-step", "display-octave")
                              .contains(restChild.getTagName())) fail(restChild);
                          noteChild.removeChild(restChild);
                        }
                      } else if (noteChild.getTagName().equals("chord"))
                        retainAttributes(noteChild, Set.of());
                    }
                    case "tie" -> retainAttributes(noteChild, Set.of("type"));
                    case "stem", "beam", "accidental", "staff", "notehead", "lyric" -> {
                      if (noteChild.getTagName().equals("staff")
                          && !noteChild.getTextContent().trim().equals("1")) fail(noteChild);
                      item.removeChild(noteChild);
                      if (Set.of("lyric", "notehead").contains(noteChild.getTagName()))
                        unsupported.add(noteChild.getTagName());
                    }
                    case "notations" -> {
                      for (Element symbol : children(noteChild)) {
                        if (symbol.getTagName().equals("tied"))
                          retainAttributes(symbol, Set.of("type"));
                        else if (Set.of("glissando", "slide").contains(symbol.getTagName()))
                          fail(symbol);
                        else {
                          unsupported.add(symbol.getTagName());
                          noteChild.removeChild(symbol);
                        }
                      }
                      if (!children(noteChild).isEmpty()) retainAttributes(noteChild, Set.of());
                      else item.removeChild(noteChild);
                    }
                    default -> fail(noteChild);
                  }
                }
              }
              default -> fail(item);
            }
          }
        }
      }
      OmrRhythmDraft.prepare(root, warnings);
      stripPresentationAttributes(root);
      var transformer = TransformerFactory.newInstance();
      transformer.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      transformer.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      transformer.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      var writer = transformer.newTransformer();
      writer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      writer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      var output = new ByteArrayOutputStream();
      writer.transform(new DOMSource(document), new StreamResult(output));
      if (output.size() > MusicXmlImporter.MAX_BYTES)
        throw new IOException("Normalized MusicXML exceeds 128 MiB");
      if (!unsupported.isEmpty())
        warnings.add("Símbolos não preservados: " + String.join(", ", unsupported) + ".");
      return new Normalized(output.toByteArray(), List.copyOf(warnings), List.copyOf(unsupported));
    } catch (ParserConfigurationException
        | SAXException
        | TransformerException
        | IllegalArgumentException failure) {
      throw new IOException(
          "Invalid or unsupported Audiveris MusicXML: " + failure.getMessage(), failure);
    }
  }

  private static void validateUtf8(byte[] bytes) throws IOException {
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    var input = ByteBuffer.wrap(bytes);
    var output = CharBuffer.allocate(8192);
    try {
      while (true) {
        var result = decoder.decode(input, output, true);
        output.clear();
        if (result.isError()) result.throwException();
        if (result.isUnderflow()) break;
      }
      var result = decoder.flush(output);
      if (result.isError()) result.throwException();
    } catch (CharacterCodingException failure) {
      throw new IOException("Audiveris MusicXML must be UTF-8", failure);
    }
  }

  private static int indexOf(byte[] bytes, byte[] value, int fromIndex) {
    outer:
    for (int start = fromIndex; start <= bytes.length - value.length; start++) {
      for (int offset = 0; offset < value.length; offset++)
        if (bytes[start + offset] != value[offset]) continue outer;
      return start;
    }
    return -1;
  }

  private static int indexOf(byte[] bytes, byte value, int fromIndex) {
    for (int index = fromIndex; index < bytes.length; index++)
      if (bytes[index] == value) return index;
    return -1;
  }

  private static byte[] removeRange(byte[] bytes, int from, int to) {
    var result = new byte[bytes.length - (to - from)];
    System.arraycopy(bytes, 0, result, 0, from);
    System.arraycopy(bytes, to, result, from, bytes.length - to);
    return result;
  }

  private static void fail(Element element) throws IOException {
    String message =
        switch (element.getTagName()) {
          case "tie", "tied" ->
              "A partitura reconhecida contém um marcador de ligadura incompleto ou inválido.";
          case "tuplet", "time-modification" ->
              "A partitura reconhecida contém quiálteras, que o editor ainda não preserva.";
          case "backup", "forward" ->
              "A partitura reconhecida contém vozes simultâneas, que o editor ainda não preserva.";
          default -> "Unsupported OMR symbol or structure: " + element.getTagName();
        };
    throw new IOException(message);
  }

  private static List<Element> children(Element parent) {
    var result = new ArrayList<Element>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element) result.add(element);
    return result;
  }

  private static List<Element> children(Element parent, String name) {
    return children(parent).stream().filter(e -> e.getTagName().equals(name)).toList();
  }

  private static Element optional(Element parent, String name) throws IOException {
    var matches = children(parent, name);
    if (matches.size() > 1) throw new IOException("Duplicate MusicXML element: " + name);
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private static Element first(Element parent, String name) throws IOException {
    var found = optional(parent, name);
    if (found == null) throw new IOException("Missing MusicXML element: " + name);
    return found;
  }

  private static void retain(Element parent, Set<String> allowed) throws IOException {
    for (Element child : children(parent))
      if (!allowed.contains(child.getTagName())) parent.removeChild(child);
  }

  private static void retainAttributes(Element parent, Set<String> allowed) {
    var names = new ArrayList<String>();
    var attributes = parent.getAttributes();
    for (int index = 0; index < attributes.getLength(); index++)
      names.add(attributes.item(index).getNodeName());
    for (String name : names) if (!allowed.contains(name)) parent.removeAttribute(name);
  }

  private static void stripPresentationAttributes(Element element) {
    Set<String> allowed =
        switch (element.getTagName()) {
          case "score-partwise" -> Set.of("version");
          case "score-part", "score-instrument", "midi-instrument", "part" -> Set.of("id");
          case "measure" -> Set.of("number", "implicit");
          case "barline" -> Set.of("location");
          case "repeat" -> Set.of("direction", "times");
          case "ending" -> Set.of("number", "type");
          case "sound" -> Set.of("tempo");
          case "tie", "tied" -> Set.of("type");
          default -> Set.of();
        };
    retainAttributes(element, allowed);
    for (Element child : children(element)) stripPresentationAttributes(child);
  }
}

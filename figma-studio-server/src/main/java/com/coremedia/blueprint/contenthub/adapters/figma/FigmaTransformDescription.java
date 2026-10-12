package com.coremedia.blueprint.contenthub.adapters.figma;

import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A transform description, i.e. the <code>settings</code> struct of a CMSettings content in
 * {@link FigmaTransformDescriptions#FOLDER}, which defines the content created from a Figma component:
 * <pre>
 * initialType   String   content type to create
 * viewtype      String   optional, the viewtype of the content, see {@link FigmaViewtypes#key}
 * properties    Struct   property name -&gt; mapping:
 *   String      "&lt;layerId&gt;-&gt;&lt;jsonPath&gt;"                     value of a Figma layer, e.g. "1:4-&gt;characters"
 *                                                        or "1:3-&gt;fills[0].imageRef", see {@link FigmaNodes#value}
 *   Struct      {type: "CMPicture", properties: {...}}   a new linked content, optionally with a viewtype
 *   StructList  [{type, properties}, ...]                several new linked contents
 *   Struct      {layer: "1:9"}                           a new linked content, transformed with the description
 *                                                        named by the <code>cm-type</code> of the layer
 *   Struct      {markup: [item, ...]}                    richtext of a markup property, the items are written
 *                                                        in their order, see {@link Richtext}
 * </pre>
 */
@DefaultAnnotation(NonNull.class)
record FigmaTransformDescription(String name, String initialType, @Nullable String viewtype,
                                 Map<String, Mapping> properties) {

  static final String INITIAL_TYPE = "initialType";
  static final String VIEWTYPE = "viewtype";
  static final String PROPERTIES = "properties";
  static final String TYPE = "type";
  static final String LAYER = "layer";
  static final String MARKUP = "markup";
  static final String VALUE = "value";
  /**
   * The default content type of the pictures created for images embedded in markup.
   */
  static final String PICTURE_TYPE = "CMPicture";
  static final String PICTURE_DATA = "data";
  static final String PICTURE_TITLE = "title";
  private static final String PATH_SEPARATOR = "->";

  FigmaTransformDescription(String name, String initialType, Map<String, Mapping> properties) {
    this(name, initialType, null, properties);
  }

  sealed interface Mapping {
  }

  /**
   * The value at <code>path</code> of the layer <code>layerId</code>.
   */
  record Value(String layerId, String path) implements Mapping {
  }

  /**
   * A new content of the given type and optional viewtype, linked from the property.
   */
  record NewContent(String type, @Nullable String viewtype, Map<String, Mapping> properties) implements Mapping {
    NewContent(String type, Map<String, Mapping> properties) {
      this(type, null, properties);
    }
  }

  /**
   * A new content, transformed with the description named by the <code>cm-type</code> of the layer.
   */
  record Component(String layerId) implements Mapping {
  }

  /**
   * Several new contents ({@link NewContent} or {@link Component}), linked from the property.
   */
  record Contents(List<Mapping> contents) implements Mapping {
  }

  /**
   * The richtext of a markup property: the items are written in their order, texts as paragraphs and contents as
   * embedded images (pictures) or links (other contents). The items of the settings struct:
   * <pre>
   * {value: "1:4-&gt;characters"}                    a text, one paragraph per line
   * {value: "1:3-&gt;fills[0]-&gt;imageRef"}            an image: a new picture (optional <code>type</code>, default
   *                                               CMPicture) with the image as data and the layer name as title
   * {type: "CMVideo", properties: {...}}         a new content, optionally with a viewtype
   * {layer: "1:9"}                               a new content, transformed with the description of the layer
   * </pre>
   *
   * @param items {@link Value texts}, {@link NewContent new contents} and {@link Component components}
   */
  record Richtext(List<Mapping> items) implements Mapping {
  }

  /**
   * Parses the settings struct, see {@link com.coremedia.cap.struct.Struct#toNestedMaps()}.
   *
   * @throws IllegalArgumentException if the description is invalid
   */
  static FigmaTransformDescription parse(String name, Map<String, Object> settings) {
    String initialType = string(settings.get(INITIAL_TYPE), name + "." + INITIAL_TYPE);
    return new FigmaTransformDescription(name, initialType, optionalString(settings.get(VIEWTYPE), name + "." + VIEWTYPE),
            properties(settings.get(PROPERTIES), name + "." + PROPERTIES));
  }

  private static Map<String, Mapping> properties(Object value, String location) {
    if (value == null) {
      return Map.of();
    }
    Map<String, Mapping> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> property : map(value, location).entrySet()) {
      String propertyLocation = location + "." + property.getKey();
      result.put(property.getKey(), mapping(property.getValue(), propertyLocation));
    }
    return Collections.unmodifiableMap(result);
  }

  private static Mapping mapping(Object value, String location) {
    if (value instanceof String string) {
      return value(string, location);
    }
    if (value instanceof Map<?, ?> struct && struct.containsKey(MARKUP)) {
      return markup(struct.get(MARKUP), location + "." + MARKUP);
    }
    if (value instanceof List<?> list) {
      List<Mapping> contents = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        contents.add(content(list.get(i), location + "[" + i + "]"));
      }
      return new Contents(List.copyOf(contents));
    }
    return content(value, location);
  }

  private static Value value(String string, String location) {
    int separator = string.indexOf(PATH_SEPARATOR);
    if (separator <= 0 || separator == string.length() - PATH_SEPARATOR.length()) {
      throw invalid(location, "expected '<layerId>" + PATH_SEPARATOR + "<jsonPath>', but was '" + string + "'");
    }
    String path = string.substring(separator + PATH_SEPARATOR.length()).trim();
    try {
      FigmaNodes.segments(path);
    } catch (IllegalArgumentException e) {
      throw invalid(location, e.getMessage());
    }
    return new Value(string.substring(0, separator).trim(), path);
  }

  private static Richtext markup(Object value, String location) {
    if (!(value instanceof List<?> list)) {
      throw invalid(location, "expected a list of items, but was " + value);
    }
    List<Mapping> items = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      items.add(markupItem(list.get(i), location + "[" + i + "]"));
    }
    return new Richtext(List.copyOf(items));
  }

  private static Mapping markupItem(Object value, String location) {
    Map<String, Object> struct = map(value, location);
    if (struct.containsKey(MARKUP)) {
      throw invalid(location, "markup cannot be nested");
    }
    if (!struct.containsKey(VALUE)) {
      return content(struct, location);
    }
    Value text = value(string(struct.get(VALUE), location + "." + VALUE), location + "." + VALUE);
    if (!FigmaContentHubTransformer.isImageRef(text)) {
      return text;
    }
    // an image can only be embedded as a picture
    String type = optionalString(struct.get(TYPE), location + "." + TYPE);
    return new NewContent(type == null ? PICTURE_TYPE : type, Map.of(PICTURE_DATA, text,
            PICTURE_TITLE, new Value(text.layerId(), "name")));
  }

  private static Mapping content(Object value, String location) {
    Map<String, Object> struct = map(value, location);
    if (struct.containsKey(LAYER)) {
      return new Component(string(struct.get(LAYER), location + "." + LAYER));
    }
    return new NewContent(string(struct.get(TYPE), location + "." + TYPE),
            optionalString(struct.get(VIEWTYPE), location + "." + VIEWTYPE),
            properties(struct.get(PROPERTIES), location + "." + PROPERTIES));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value, String location) {
    if (!(value instanceof Map<?, ?> map)) {
      throw invalid(location, "expected a struct, but was " + value);
    }
    return (Map<String, Object>) map;
  }

  private static String string(Object value, String location) {
    if (!(value instanceof String string) || string.isBlank()) {
      throw invalid(location, "expected a non-empty string, but was " + value);
    }
    return string.trim();
  }

  @Nullable
  private static String optionalString(@Nullable Object value, String location) {
    if (value == null || value instanceof String string && string.isBlank()) {
      return null;
    }
    return string(value, location);
  }

  private static IllegalArgumentException invalid(String location, String message) {
    return new IllegalArgumentException("Invalid Figma transform description " + location + ": " + message);
  }
}

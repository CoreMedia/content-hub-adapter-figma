package com.coremedia.blueprint.contenthub.adapters.figma;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Access to layers of the node JSON of the Figma API, as used by {@link FigmaTransformDescription}s.
 */
@DefaultAnnotation(NonNull.class)
final class FigmaNodes {

  private static final char INSTANCE_ID_SEPARATOR = ';';

  private FigmaNodes() {
  }

  /**
   * Searches the node and its descendants depth-first for the layer with the given id.
   * <p>
   * Layer ids in transform descriptions are the ids of the component definition, so that a description applies to
   * the definition and to all its instances:
   * <ul>
   *   <li>Within an instance, Figma prefixes the ids of the children with the instance id (e.g. <code>I1:20;1:4</code>),
   *   so a layer also matches if its id ends with <code>;&lt;layerId&gt;</code>. This also holds for layers of nested
   *   instances, e.g. <code>5:9;3:1</code> matches <code>I5:9;3:1</code> in the definition and
   *   <code>I1:20;5:9;3:1</code> in an instance.</li>
   *   <li>An imported instance matches the id of its component (<code>componentId</code>).</li>
   * </ul>
   */
  static Optional<JsonNode> findLayer(JsonNode node, String layerId) {
    String plainLayerId = stripInstancePrefix(layerId);
    if ("INSTANCE".equals(node.path("type").asText()) && plainLayerId.equals(node.path("componentId").asText())) {
      return Optional.of(node);
    }
    return findLayerRecursive(node, plainLayerId);
  }

  private static Optional<JsonNode> findLayerRecursive(JsonNode node, String layerId) {
    String id = node.path("id").asText();
    String plainId = stripInstancePrefix(id);
    if (id.equals(layerId) || plainId.equals(layerId) || plainId.endsWith(INSTANCE_ID_SEPARATOR + layerId)) {
      return Optional.of(node);
    }
    for (JsonNode child : node.path("children")) {
      Optional<JsonNode> result = findLayerRecursive(child, layerId);
      if (result.isPresent()) {
        return result;
      }
    }
    return Optional.empty();
  }

  /**
   * Ids of layers within instances start with <code>I</code>, e.g. <code>I1:20;1:4</code>.
   */
  private static String stripInstancePrefix(String id) {
    return id.startsWith("I") ? id.substring(1) : id;
  }

  /**
   * Returns the value at the path, e.g. <code>characters</code>, <code>fills[0]-&gt;imageRef</code>,
   * <code>fills[0].imageRef</code> or <code>fills.0.imageRef</code>: field names are separated by
   * <code>-&gt;</code> or dots, array elements are selected with <code>[index]</code> or a numeric segment.
   * Returns empty if the path does not denote a value.
   */
  static Optional<String> value(JsonNode node, String path) {
    JsonNode current = node;
    for (String segment : segments(path)) {
      current = current.isArray() && FigmaId.isNumeric(segment)
              ? current.path(Integer.parseInt(segment))
              : current.path(segment);
    }
    return current.isValueNode() && !current.isNull() ? Optional.of(current.asText()) : Optional.empty();
  }

  /**
   * Splits a path into field names and indexes: <code>fills[0]-&gt;imageRef</code> -&gt; <code>fills, 0, imageRef</code>.
   *
   * @throws IllegalArgumentException if the path is malformed
   */
  static List<String> segments(String path) {
    List<String> result = new ArrayList<>();
    for (String part : path.split("->|\\.")) {
      part = part.trim();
      int bracket = part.indexOf('[');
      String field = bracket < 0 ? part : part.substring(0, bracket).trim();
      if (field.isEmpty() && bracket != 0) {
        throw new IllegalArgumentException("Empty segment in Figma path '" + path + "'");
      }
      if (!field.isEmpty()) {
        result.add(field);
      }
      while (bracket >= 0) {
        int end = part.indexOf(']', bracket);
        if (end < 0) {
          throw new IllegalArgumentException("Missing ']' in Figma path '" + path + "'");
        }
        String index = part.substring(bracket + 1, end).trim();
        if (!FigmaId.isNumeric(index)) {
          throw new IllegalArgumentException("Invalid index [" + index + "] in Figma path '" + path + "'");
        }
        result.add(index);
        int next = end + 1;
        if (next < part.length() && part.charAt(next) != '[') {
          throw new IllegalArgumentException("Unexpected '" + part.substring(next) + "' after [" + index
                  + "] in Figma path '" + path + "'");
        }
        bracket = next < part.length() ? next : -1;
      }
    }
    return result;
  }
}

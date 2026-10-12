package com.coremedia.blueprint.contenthub.adapters.figma;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Reads component properties from the node JSON of the Figma API.
 * <p>
 * Depending on how a component is used, a property shows up in different places:
 * <ul>
 *   <li>instances: <code>componentProperties: {"cType": {"type": "VARIANT", "value": "..."}}</code></li>
 *   <li>components and component sets:
 *   <code>componentPropertyDefinitions: {"cType#12:0": {"type": "TEXT", "defaultValue": "..."}}</code></li>
 *   <li>variants of a component set: in the name only, e.g. <code>cType=Article, Size=L</code></li>
 * </ul>
 * Figma appends a unique suffix (<code>#&lt;id&gt;</code>) to the names of text, boolean and instance swap
 * properties, so names are matched without that suffix.
 */
@DefaultAnnotation(NonNull.class)
final class FigmaComponentProperties {

  private FigmaComponentProperties() {
  }

  /**
   * Searches the node and its descendants depth-first and returns the first value of the given property.
   */
  static Optional<String> find(JsonNode node, String name) {
    String value = ownValue(node, name);
    if (value != null) {
      return Optional.of(value);
    }
    for (JsonNode child : node.path("children")) {
      Optional<String> result = find(child, name);
      if (result.isPresent()) {
        return result;
      }
    }
    return Optional.empty();
  }

  @Nullable
  private static String ownValue(JsonNode node, String name) {
    String value = propertyValue(node.path("componentProperties"), name, "value");
    if (value == null) {
      value = propertyValue(node.path("componentPropertyDefinitions"), name, "defaultValue");
    }
    if (value == null && "COMPONENT".equals(node.path("type").asText())) {
      value = variantValue(node.path("name").asText(), name);
    }
    return value;
  }

  @Nullable
  private static String propertyValue(JsonNode properties, String name, String valueField) {
    for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> property = it.next();
      if (matches(property.getKey(), name)) {
        JsonNode value = property.getValue().path(valueField);
        if (value.isValueNode() && !value.asText().isBlank()) {
          return value.asText().trim();
        }
      }
    }
    return null;
  }

  /**
   * Variant names have the form <code>prop1=value1, prop2=value2</code>.
   */
  @Nullable
  private static String variantValue(String variantName, String name) {
    for (String pair : variantName.split(",")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && matches(pair.substring(0, eq).trim(), name)) {
        String value = pair.substring(eq + 1).trim();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

  private static boolean matches(String key, String name) {
    int hash = key.indexOf('#');
    String plainKey = hash < 0 ? key : key.substring(0, hash);
    return plainKey.trim().equalsIgnoreCase(name);
  }
}

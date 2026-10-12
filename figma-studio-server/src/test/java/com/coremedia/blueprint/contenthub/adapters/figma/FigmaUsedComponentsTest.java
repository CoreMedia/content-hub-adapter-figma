package com.coremedia.blueprint.contenthub.adapters.figma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FigmaUsedComponentsTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void usedComponents() throws Exception {
    JsonNode page = objectMapper.readTree("""
            {"type": "CANVAS", "children": [
              {"id": "1:1", "type": "FRAME", "children": [
                {"id": "1:2", "type": "INSTANCE", "name": "Button", "componentId": "9:1", "children": [
                  {"id": "I1:2;9:2", "type": "INSTANCE", "name": "Icon", "componentId": "9:5"}]},
                {"id": "1:3", "type": "INSTANCE", "name": "Button", "componentId": "9:1"},
                {"id": "1:4", "type": "INSTANCE", "name": "Hidden", "componentId": "9:3", "visible": false},
                {"id": "1:5", "type": "GROUP", "children": [
                  {"id": "1:6", "type": "INSTANCE", "name": "Teaser", "componentId": "9:4"}]}]},
              {"id": "1:7", "type": "COMPONENT", "name": "Definition only", "children": [
                {"id": "1:9", "type": "INSTANCE", "name": "Nested", "componentId": "9:6"}]},
              {"id": "9:4", "type": "COMPONENT", "name": "Teaser definition"},
              {"id": "1:10", "type": "COMPONENT_SET", "name": "Card", "children": [
                {"id": "1:11", "type": "COMPONENT", "name": "Size=L"}]},
              {"id": "1:8", "type": "RECTANGLE"}]}
            """);
    assertThat(FigmaContentHubAdapter.usedComponents(page))
            .extracting(node -> node.path("id").asText())
            // every visible instance, also of the same component (1:3) or of a definition on the page (1:6 of 9:4)
            .containsExactly("1:2", "1:3", "1:6", "1:7", "9:4", "1:10");
  }
}

package com.coremedia.blueprint.contenthub.adapters.figma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FigmaComponentPropertiesTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void instanceProperty() throws Exception {
    JsonNode page = json("""
            {"type": "CANVAS", "children": [
              {"type": "FRAME", "children": [
                {"type": "INSTANCE", "componentProperties": {
                  "Size": {"type": "VARIANT", "value": "L"},
                  "cType": {"type": "VARIANT", "value": "CMTeaser"}}}]}]}
            """);
    assertThat(FigmaComponentProperties.find(page, "cType")).contains("CMTeaser");
  }

  @Test
  void textPropertyWithIdSuffix() throws Exception {
    JsonNode node = json("""
            {"type": "INSTANCE", "componentProperties": {"cType#12:0": {"type": "TEXT", "value": " CMArticle "}}}
            """);
    assertThat(FigmaComponentProperties.find(node, "cType")).contains("CMArticle");
  }

  @Test
  void componentDefinition() throws Exception {
    JsonNode node = json("""
            {"type": "COMPONENT_SET", "componentPropertyDefinitions": {
              "cType": {"type": "VARIANT", "defaultValue": "CMPicture", "variantOptions": ["CMPicture", "CMVideo"]}}}
            """);
    assertThat(FigmaComponentProperties.find(node, "cType")).contains("CMPicture");
  }

  @Test
  void variantName() throws Exception {
    JsonNode node = json("""
            {"type": "COMPONENT", "name": "Size=L, cType=CMVideo"}
            """);
    assertThat(FigmaComponentProperties.find(node, "cType")).contains("CMVideo");
  }

  @Test
  void missing() throws Exception {
    JsonNode node = json("""
            {"type": "FRAME", "name": "a=b", "children": [{"type": "INSTANCE", "componentProperties": {}}]}
            """);
    assertThat(FigmaComponentProperties.find(node, "cType")).isEmpty();
    assertThat(FigmaComponentProperties.find(objectMapper.missingNode(), "cType")).isEmpty();
  }

  private JsonNode json(String s) throws Exception {
    return objectMapper.readTree(s);
  }
}

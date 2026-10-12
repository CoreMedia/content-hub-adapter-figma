package com.coremedia.blueprint.contenthub.adapters.figma;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FigmaNodesTest {

  private static final String COMPONENT = """
          {"id": "1:8", "type": "COMPONENT", "children": [
            {"id": "1:4", "type": "TEXT", "characters": "Lorem ipsum", "style": {"fontSize": 12.0},
             "fills": [{"type": "SOLID"}], "imageTransform": [[2.7, 0.0, -0.06]]},
            {"id": "I1:20;1:7", "type": "TEXT", "characters": "Click me"}]}
          """;

  @Test
  void findLayer() throws Exception {
    JsonNode component = new ObjectMapper().readTree(COMPONENT);
    assertThat(FigmaNodes.findLayer(component, "1:8")).contains(component);
    assertThat(FigmaNodes.findLayer(component, "1:4")).map(node -> node.path("characters").asText()).contains("Lorem ipsum");
    assertThat(FigmaNodes.findLayer(component, "1:7")).map(node -> node.path("characters").asText()).contains("Click me");
    assertThat(FigmaNodes.findLayer(component, "1:77")).isEmpty();
    assertThat(FigmaNodes.findLayer(component, "4")).isEmpty();
  }

  @Test
  void value() throws Exception {
    JsonNode text = FigmaNodes.findLayer(new ObjectMapper().readTree(COMPONENT), "1:4").orElseThrow();
    assertThat(FigmaNodes.value(text, "characters")).contains("Lorem ipsum");
    assertThat(FigmaNodes.value(text, "style.fontSize")).contains("12.0");
    assertThat(FigmaNodes.value(text, "fills.0.type")).contains("SOLID");
    assertThat(FigmaNodes.value(text, "style")).isEmpty();
    assertThat(FigmaNodes.value(text, "fills.1.type")).isEmpty();
    assertThat(FigmaNodes.value(text, "missing")).isEmpty();
  }

  @Test
  void indexedAccess() throws Exception {
    JsonNode text = FigmaNodes.findLayer(new ObjectMapper().readTree(COMPONENT), "1:4").orElseThrow();
    assertThat(FigmaNodes.value(text, "fills[0].type")).contains("SOLID");
    assertThat(FigmaNodes.value(text, "fills[0]->type")).contains("SOLID");
    assertThat(FigmaNodes.value(text, "fills[1].type")).isEmpty();
    assertThat(FigmaNodes.value(text, "imageTransform[0][2]")).contains("-0.06");
    // an element which is an object is not a value
    assertThat(FigmaNodes.value(text, "fills[0]")).isEmpty();
  }

  @Test
  void segments() {
    assertThat(FigmaNodes.segments("characters")).containsExactly("characters");
    assertThat(FigmaNodes.segments("fills[0].imageRef")).containsExactly("fills", "0", "imageRef");
    assertThat(FigmaNodes.segments("fills.0.imageRef")).containsExactly("fills", "0", "imageRef");
    assertThat(FigmaNodes.segments("imageTransform[0][2]")).containsExactly("imageTransform", "0", "2");
    assertThat(FigmaNodes.segments("fills[0]->imageRef")).containsExactly("fills", "0", "imageRef");
    assertThat(FigmaNodes.segments("imageTransform[0][2]")).containsExactly("imageTransform", "0", "2");
    assertThatThrownBy(() -> FigmaNodes.segments("fills[0")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FigmaNodes.segments("fills[0]imageRef")).hasMessageContaining("Unexpected 'imageRef'");
    assertThatThrownBy(() -> FigmaNodes.segments("fills[x]")).hasMessageContaining("Invalid index");
    assertThatThrownBy(() -> FigmaNodes.segments("fills..imageRef")).hasMessageContaining("Empty segment");
  }

  @Test
  void idsOfTheDefinitionMatchInstancesAndNestedInstances() throws Exception {
    JsonNode instance = new ObjectMapper().readTree("""
            {"id": "13:8", "type": "INSTANCE", "componentId": "1:8", "children": [
              {"id": "I13:8;1:4", "type": "TEXT", "characters": "Lorem ipsum"},
              {"id": "I13:8;5:9", "type": "INSTANCE", "componentId": "5:0", "children": [
                {"id": "I13:8;5:9;3:1", "type": "TEXT", "characters": "Go"}]}]}
            """);
    // the imported instance by its component id
    assertThat(FigmaNodes.findLayer(instance, "1:8")).contains(instance);
    assertThat(FigmaNodes.findLayer(instance, "1:4")).map(node -> node.path("characters").asText()).contains("Lorem ipsum");
    // a layer of a nested instance, as written by the editor, or as in the component definition
    assertThat(FigmaNodes.findLayer(instance, "5:9;3:1")).map(node -> node.path("characters").asText()).contains("Go");
    assertThat(FigmaNodes.findLayer(instance, "I5:9;3:1")).map(node -> node.path("characters").asText()).contains("Go");
    assertThat(FigmaNodes.findLayer(instance, "5:9")).map(node -> node.path("id").asText()).contains("I13:8;5:9");
    // the component id only matches the imported node itself
    assertThat(FigmaNodes.findLayer(instance, "5:0")).isEmpty();
  }
}

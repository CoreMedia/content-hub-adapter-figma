package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Component;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Contents;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.NewContent;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Value;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FigmaTransformDescriptionTest {

  @Test
  void parse() {
    FigmaTransformDescription description = FigmaTransformDescription.parse("hero", Map.of(
            "initialType", "CMTeaser",
            "properties", Map.of(
                    "title", "1:4->characters",
                    "pictures", Map.of("type", "CMPicture", "viewtype", "portrait",
                            "properties", Map.of("title", "1:3 -> name")),
                    "related", List.of(
                            Map.of("type", "CMArticle", "properties", Map.of("title", "1:7->characters")),
                            Map.of("layer", "1:9")),
                    "target", Map.of("layer", "1:10"))));

    assertThat(description.name()).isEqualTo("hero");
    assertThat(description.initialType()).isEqualTo("CMTeaser");
    assertThat(description.viewtype()).isNull();
    assertThat(description.properties()).containsOnly(
            Map.entry("title", new Value("1:4", "characters")),
            Map.entry("pictures", new NewContent("CMPicture", "portrait", Map.of("title", new Value("1:3", "name")))),
            Map.entry("related", new Contents(List.of(
                    new NewContent("CMArticle", Map.of("title", new Value("1:7", "characters"))),
                    new Component("1:9")))),
            Map.entry("target", new Component("1:10")));
  }

  @Test
  void viewtype() {
    assertThat(FigmaTransformDescription.parse("hero", Map.of("initialType", "CMTeaser", "viewtype", " hero "))
            .viewtype()).isEqualTo("hero");
    assertThat(FigmaTransformDescription.parse("hero", Map.of("initialType", "CMTeaser", "viewtype", ""))
            .viewtype()).isNull();
  }

  @Test
  void propertiesAreOptional() {
    assertThat(FigmaTransformDescription.parse("empty", Map.of("initialType", "CMTeaser")).properties()).isEmpty();
  }

  @Test
  void invalid() {
    assertThatThrownBy(() -> FigmaTransformDescription.parse("hero", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hero.initialType");
    assertThatThrownBy(() -> FigmaTransformDescription.parse("hero", Map.of("initialType", "CMTeaser",
            "properties", Map.of("title", "characters"))))
            .hasMessageContaining("hero.properties.title")
            .hasMessageContaining("<layerId>-><jsonPath>");
    assertThatThrownBy(() -> FigmaTransformDescription.parse("hero", Map.of("initialType", "CMTeaser",
            "properties", Map.of("pictures", List.of(Map.of("properties", Map.of()))))))
            .hasMessageContaining("hero.properties.pictures[0].type");
    assertThatThrownBy(() -> FigmaTransformDescription.parse("hero", Map.of("initialType", "CMPicture",
            "properties", Map.of("data", "1:3->fills[0.imageRef"))))
            .hasMessageContaining("hero.properties.data")
            .hasMessageContaining("Missing ']'");
  }
}

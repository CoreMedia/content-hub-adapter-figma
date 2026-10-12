package com.coremedia.blueprint.contenthub.adapters.figma;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FigmaHelpersTest {

  @Test
  void thumbnailScale() {
    assertThat(FigmaComponentItem.thumbnailScale(200)).isEqualTo(1);
    assertThat(FigmaComponentItem.thumbnailScale(375)).isEqualTo(1);
    assertThat(FigmaComponentItem.thumbnailScale(800)).isEqualTo(0.5);
    assertThat(FigmaComponentItem.thumbnailScale(1440)).isEqualTo(0.25);
    assertThat(FigmaComponentItem.thumbnailScale(5000)).isEqualTo(0.1);
    assertThat(FigmaComponentItem.thumbnailScale(50000)).isEqualTo(0.05);
  }

  @Test
  void contentName() {
    assertThat(FigmaContentHubTransformer.contentName("Button/Primary")).isEqualTo("Button-Primary");
    assertThat(FigmaContentHubTransformer.contentName("  .hidden  frame ")).isEqualTo("hidden frame");
    assertThat(FigmaContentHubTransformer.contentName(" / ")).isEqualTo("-");
    assertThat(FigmaContentHubTransformer.contentName("   ")).isEqualTo("Figma Frame");
  }

  @Test
  void settings() {
    assertThat(FigmaContentHubAdapter.parseFormat(null)).isEqualTo("png");
    assertThat(FigmaContentHubAdapter.parseFormat("JPG")).isEqualTo("jpg");
    assertThatThrownBy(() -> FigmaContentHubAdapter.parseFormat("gif")).isInstanceOf(IllegalArgumentException.class);
    assertThat(FigmaContentHubAdapter.parseScale("")).isEqualTo(1);
    assertThat(FigmaContentHubAdapter.parseScale("2")).isEqualTo(2);
    assertThatThrownBy(() -> FigmaContentHubAdapter.parseScale("5")).isInstanceOf(IllegalArgumentException.class);
  }
}

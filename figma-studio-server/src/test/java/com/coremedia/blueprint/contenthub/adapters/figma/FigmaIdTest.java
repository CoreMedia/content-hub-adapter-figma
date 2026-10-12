package com.coremedia.blueprint.contenthub.adapters.figma;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FigmaIdTest {

  @Test
  void externalIdsAreFigmaIds() {
    assertThat(FigmaId.ROOT.asExternalId()).isEqualTo("root");
    assertThat(FigmaId.team("1172463995325975201").asExternalId()).isEqualTo("1172463995325975201");
    assertThat(FigmaId.folder("456").asExternalId()).isEqualTo("456");
    assertThat(FigmaId.file("aBc123").asExternalId()).isEqualTo("aBc123");
    assertThat(FigmaId.page("aBc123", "0:1").asExternalId()).isEqualTo("aBc123:0:1");
    assertThat(FigmaId.component("aBc123", "0:1", "12:34").asExternalId()).isEqualTo("aBc123:0:1~12:34");
  }

  @Test
  void parseNodeId() {
    assertThat(FigmaId.parseNodeId("aBc123:12:345")).isEqualTo(FigmaId.page("aBc123", "12:345"));
    assertThat(FigmaId.parseNodeId("aBc123:0:1~12:34")).isEqualTo(FigmaId.component("aBc123", "0:1", "12:34"));
    assertThat(FigmaId.parseNodeId("aBc123")).isNull();
    assertThat(FigmaId.parseNodeId(":12")).isNull();
    assertThat(FigmaId.parseNodeId("aBc123:")).isNull();
    assertThat(FigmaId.parseNodeId("aBc123:0:1~")).isNull();
    assertThat(FigmaId.parseNodeId("aBc123:~12:34")).isNull();
  }

  @Test
  void componentsBelongToTheirPage() {
    assertThat(FigmaId.component("aBc123", "0:1", "12:34").asPage()).isEqualTo(FigmaId.page("aBc123", "0:1"));
  }

  @Test
  void isNumeric() {
    assertThat(FigmaId.isNumeric("1172463995325975201")).isTrue();
    assertThat(FigmaId.isNumeric("aBc123")).isFalse();
    assertThat(FigmaId.isNumeric("")).isFalse();
  }
}

package com.coremedia.blueprint.contenthub.adapters.figma;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FigmaComponentItemTest {

  @Test
  void imageFillsHaveDistinctClassifiers() {
    FigmaComponentData component = new FigmaComponentData("file", "File", "0:1", "Page", "1:8", "Component 1",
            100, 100, null, JsonNodeFactory.instance.objectNode(), List.of());
    FigmaComponentItem item = new FigmaComponentItem("figma", component, mock(FigmaClient.class), "png", 1);

    // Content Hub caches blobs by owner and classifier
    assertThat(item.getImageFill("aaa").getClassifier()).isNotEqualTo(item.getImageFill("bbb").getClassifier());
    assertThat(item.getImageFill("aaa").getClassifier()).isEqualTo(item.getImageFill("aaa").getClassifier());
  }
}

package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Component;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Richtext;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.NewContent;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Value;
import com.coremedia.cap.common.Blob;
import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.common.CapPropertyDescriptorType;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentType;
import com.coremedia.rest.cap.intercept.ContentWriteRequest;
import jakarta.activation.MimeType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FigmaMarkupTest {

  @Test
  void parsesMarkupItems() {
    FigmaTransformDescription description = FigmaTransformDescription.parse("hero", Map.of(
            "initialType", "CMTeaser",
            "properties", Map.of("detailText", Map.of("markup", List.of(
                    Map.of("value", "1:4->characters"),
                    Map.of("value", "1:3->fills[0]->imageRef"),
                    Map.of("value", "1:5->fills[0]->imageRef", "type", "CMImage"),
                    Map.of("type", "CMVideo", "properties", Map.of("title", "1:6->name")),
                    Map.of("layer", "1:9"))))));

    assertThat(description.properties().get("detailText")).isEqualTo(new Richtext(List.of(
            new Value("1:4", "characters"),
            new NewContent("CMPicture", Map.of("data", new Value("1:3", "fills[0]->imageRef"),
                    "title", new Value("1:3", "name"))),
            new NewContent("CMImage", Map.of("data", new Value("1:5", "fills[0]->imageRef"),
                    "title", new Value("1:5", "name"))),
            new NewContent("CMVideo", Map.of("title", new Value("1:6", "name"))),
            new Component("1:9"))));
  }

  @Test
  void rejectsNestedMarkup() {
    Map<String, Object> settings = Map.of("initialType", "CMTeaser",
            "properties", Map.of("detailText", Map.of("markup", List.of(Map.of("markup", List.of())))));

    assertThatThrownBy(() -> FigmaTransformDescription.parse("hero", settings))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hero.properties.detailText.markup[0]");
  }

  @Test
  void interceptorWritesRichtextInOrder() throws Exception {
    Content picture = content("coremedia:///cap/content/42", "Hero <image>", "image/png");
    Content video = content("coremedia:///cap/content/44", "Teaser video", null);
    Map<String, Object> properties = new HashMap<>();
    properties.put("title", "Hero");
    properties.put("detailText", new FigmaMarkup(List.of(
            new FigmaMarkup.Text("Welcome\n\n to Figma & more"),
            new FigmaMarkup.Embed("figma-markup:detailText:0"),
            new FigmaMarkup.Embed("figma-markup:detailText:1"),
            new FigmaMarkup.Embed("figma-markup:detailText:2"),
            new FigmaMarkup.Text("The end"))));
    properties.put("figma-markup:detailText:0", picture);
    properties.put("figma-markup:detailText:1", video);
    // not created, e.g. because of an error
    properties.put("figma-markup:detailText:2", "unresolved");
    ContentWriteRequest request = mock(ContentWriteRequest.class);
    when(request.getProperties()).thenReturn(properties);

    new FigmaMarkupWriteInterceptor().intercept(request);

    assertThat(properties).containsOnlyKeys("title", "detailText");
    String richtext = properties.get("detailText").toString();
    assertThat(richtext).containsSubsequence(
            "<p>Welcome</p>",
            "<p>to Figma &amp; more</p>",
            "<img alt=\"Hero &lt;image&gt;\" xlink:href=\"coremedia:///cap/blob/content/42#data\"",
            "<a xlink:href=\"coremedia:///cap/content/44\">Teaser video</a>",
            "<p>The end</p>");
  }

  @Test
  void interceptorIgnoresOtherWrites() {
    Map<String, Object> properties = new HashMap<>(Map.of("title", "Hero"));
    ContentWriteRequest request = mock(ContentWriteRequest.class);
    when(request.getProperties()).thenReturn(properties);

    new FigmaMarkupWriteInterceptor().intercept(request);

    assertThat(properties).containsExactly(Map.entry("title", "Hero"));
  }

  private static Content content(String id, String name, String mimeType) throws Exception {
    Content content = mock(Content.class);
    ContentType type = mock(ContentType.class);
    when(content.getId()).thenReturn(id);
    when(content.getName()).thenReturn(name);
    when(content.getType()).thenReturn(type);
    if (mimeType != null) {
      CapPropertyDescriptor data = mock(CapPropertyDescriptor.class);
      when(data.getType()).thenReturn(CapPropertyDescriptorType.BLOB);
      when(type.getDescriptor("data")).thenReturn(data);
      Blob blob = mock(Blob.class);
      when(blob.getContentType()).thenReturn(new MimeType(mimeType));
      when(content.getBlob("data")).thenReturn(blob);
    }
    return content;
  }
}

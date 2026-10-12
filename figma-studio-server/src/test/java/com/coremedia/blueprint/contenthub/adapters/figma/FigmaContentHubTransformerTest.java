package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Component;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Contents;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Mapping;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Richtext;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.NewContent;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Value;
import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.common.CapPropertyDescriptorType;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.multisite.Site;
import com.coremedia.contenthub.api.ContentHubAdapter;
import com.coremedia.contenthub.api.ContentHubBlob;
import com.coremedia.contenthub.api.ContentHubContext;
import com.coremedia.contenthub.api.ContentHubObjectId;
import com.coremedia.contenthub.api.ContentModel;
import com.coremedia.contenthub.api.ContentModelReference;
import com.coremedia.contenthub.api.exception.ContentHubException;
import com.coremedia.xml.Markup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FigmaContentHubTransformerTest {

  /**
   * Component 1 of the Test-FGI file, with an instance of a "button" component.
   */
  private static final String COMPONENT = """
          {"id": "1:8", "name": "Component 1", "type": "COMPONENT",
           "componentPropertyDefinitions": {"cm-type#2:0": {"type": "TEXT", "defaultValue": "hero"}},
           "children": [
             {"id": "1:3", "name": "Rectangle 2", "type": "RECTANGLE",
              "fills": [{"type": "IMAGE", "imageRef": "67126d820224aa17e85f8245224426d9c1f5d657"}]},
             {"id": "1:4", "name": "Lorem ipsum", "type": "TEXT", "characters": "Lorem ipsum"},
             {"id": "1:7", "name": "Click me", "type": "TEXT", "characters": "Click me"},
             {"id": "1:9", "name": "Button", "type": "INSTANCE", "componentId": "5:0",
              "componentProperties": {"cm-type": {"type": "VARIANT", "value": "button"}},
              "children": [{"id": "I1:9;5:1", "type": "TEXT", "characters": "Go"}]}]}
          """;

  private final ContentHubAdapter adapter = mock(ContentHubAdapter.class);
  private final ContentHubContext context = mock(ContentHubContext.class);
  private FigmaTransformDescriptions descriptions;
  private FigmaViewtypes viewtypes;
  private FigmaContentHubTransformer transformer;
  private FigmaItem item;

  @BeforeEach
  void setUp() throws Exception {
    descriptions = mock(FigmaTransformDescriptions.class);
    viewtypes = mock(FigmaViewtypes.class);
    transformer = new FigmaContentHubTransformer(descriptions, viewtypes);
    JsonNode node = new ObjectMapper().readTree(COMPONENT);
    item = mock(FigmaItem.class);
    when(item.getName()).thenReturn("Component 1");
    when(item.getId()).thenReturn(new ContentHubObjectId("figma", "file:0:1~1:8"));
    when(item.getNode()).thenReturn(node);
    when(item.getFigmaId()).thenReturn(FigmaId.component("file", "0:1", "1:8"));
    property("CMTeaser", "title", CapPropertyDescriptorType.STRING);
    property("CMTeaser", "teaserText", CapPropertyDescriptorType.MARKUP);
    property("CMTeaser", "pictures", CapPropertyDescriptorType.LINK);
    property("CMPicture", "title", CapPropertyDescriptorType.STRING);
    property("CMLinkable", "title", CapPropertyDescriptorType.STRING);
    property("CMPicture", "data", CapPropertyDescriptorType.BLOB);
  }

  @Test
  void valuesAreMappedByPropertyType() throws Exception {
    describe("hero", "CMTeaser", Map.of(
            "title", new Value("1:4", "characters"),
            "teaserText", new Value("1:7", "characters"),
            "unknown", new Value("1:4", "characters"),
            "pictures", new Value("1:4", "characters"),
            "missing", new Value("1:99", "characters")));

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getCoreMediaContentType()).isEqualTo("CMTeaser");
    assertThat(model.getContentName()).isEqualTo("Component 1");
    // unknown properties, values for link properties and missing layers are skipped
    assertThat(model.getContentProperties()).containsKeys("title", "teaserText")
            .doesNotContainKeys("unknown", "pictures", "missing");
    assertThat(model.getContentProperties().get("title")).isEqualTo("Lorem ipsum");
    assertThat(model.getContentProperties().get("teaserText")).isInstanceOf(Markup.class);
    assertThat(model.getContentProperties().get("teaserText").toString()).contains("Click me");
  }

  @Test
  void newContentsAreLinked() throws Exception {
    describe("hero", "CMTeaser", Map.of(
            "pictures", new NewContent("CMPicture", Map.of("title", new Value("1:3", "name"))),
            "related", new Contents(List.of(
                    new NewContent("CMPicture", Map.of("title", new Value("1:4", "characters"))),
                    new NewContent("CMPicture", Map.of("title", new Value("1:7", "characters")))))));

    ContentModel model = transformer.transform(item, adapter, context);

    ContentModel picture = resolve(model, "pictures", 0);
    assertThat(picture.getCoreMediaContentType()).isEqualTo("CMPicture");
    assertThat(picture.getContentName()).isEqualTo("Component 1 - pictures");
    assertThat(picture.getContentProperties()).containsEntry("title", "Rectangle 2");
    assertThat(references(model, "related")).hasSize(2);
    assertThat(resolve(model, "related", 1).getContentProperties()).containsEntry("title", "Click me");
  }

  @Test
  void markupEmbedsContentsInOrder() throws Exception {
    describe("hero", "CMTeaser", Map.of("teaserText", new Richtext(List.of(
            new Value("1:4", "characters"),
            new NewContent("CMPicture", Map.of("title", new Value("1:3", "name"))),
            new Value("1:99", "characters"),
            new Value("1:7", "characters")))));

    ContentModel model = transformer.transform(item, adapter, context);

    Object value = model.getContentProperties().get("teaserText");
    assertThat(value).isInstanceOf(FigmaMarkup.class);
    // the missing layer is skipped
    assertThat(((FigmaMarkup) value).parts()).containsExactly(
            new FigmaMarkup.Text("Lorem ipsum"),
            new FigmaMarkup.Embed("figma-markup:teaserText:0"),
            new FigmaMarkup.Text("Click me"));
    ContentModelReference reference = (ContentModelReference) model.getContentProperties().get("figma-markup:teaserText:0");
    ContentModel picture = transformer.resolveReference(item, reference, adapter, context);
    assertThat(picture.getContentName()).isEqualTo("Component 1 - teaserText 1");
    assertThat(picture.getContentProperties()).containsEntry("title", "Rectangle 2");
  }

  @Test
  void markupRequiresMarkupProperty() throws Exception {
    describe("hero", "CMTeaser", Map.of("title", new Richtext(List.of(new Value("1:4", "characters")))));

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getContentProperties()).doesNotContainKey("title");
  }

  @Test
  void componentPointsToComponent() throws Exception {
    describe("hero", "CMTeaser", Map.of("pictures", new Component("1:9")));
    FigmaTransformDescription button = new FigmaTransformDescription("button", "CMLinkable",
            Map.of("title", new Value("5:1", "characters")));
    when(descriptions.find(any(JsonNode.class))).thenAnswer(invocation ->
            "1:9".equals(invocation.<JsonNode>getArgument(0).path("id").asText()) ? Optional.of(button) : Optional.empty());

    ContentModel model = transformer.transform(item, adapter, context);

    ContentModel linked = resolve(model, "pictures", 0);
    assertThat(linked.getCoreMediaContentType()).isEqualTo("CMLinkable");
    // layer ids of the nested description are resolved within the instance
    assertThat(linked.getContentProperties()).containsEntry("title", "Go");
  }

  @Test
  void recursiveDescriptionsAreSkipped() throws Exception {
    FigmaTransformDescription hero = describe("hero", "CMTeaser", Map.of("pictures", new Component("1:9")));
    when(descriptions.find(any(JsonNode.class))).thenReturn(Optional.of(hero));

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getContentProperties()).doesNotContainKey("pictures");
  }

  @Test
  void nestingIsLimited() throws Exception {
    Mapping mapping = new Value("1:4", "characters");
    for (int i = 0; i <= FigmaContentHubTransformer.MAX_DEPTH; i++) {
      mapping = new NewContent("CMTeaser", Map.of("pictures", mapping));
    }
    describe("hero", "CMTeaser", Map.of("pictures", mapping));

    ContentModel current = transformer.transform(item, adapter, context);
    int depth = 0;
    while (current.getContentProperties().containsKey("pictures")) {
      current = resolve(current, "pictures", 0);
      depth++;
    }
    assertThat(depth).isEqualTo(FigmaContentHubTransformer.MAX_DEPTH);
  }

  @Test
  void imageRefIsDownloadedIntoBlobProperty() throws Exception {
    ContentHubBlob image = mock(ContentHubBlob.class);
    when(image.getLength()).thenReturn(42L);
    when(item.getImageFill("67126d820224aa17e85f8245224426d9c1f5d657")).thenReturn(image);
    describe("hero", "CMTeaser", Map.of(
            "pictures", new NewContent("CMPicture", Map.of("data", new Value("1:3", "fills[0]->imageRef")))));

    ContentModel picture = resolve(transformer.transform(item, adapter, context), "pictures", 0);

    assertThat(picture.getContentProperties()).containsEntry("data", image);
  }

  @Test
  void onlyImageRefsAreMappedToBlobs() throws Exception {
    describe("picture", "CMPicture", Map.of("data", new Value("1:3", "name")));

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getContentProperties()).doesNotContainKey("data");
    verify(item, never()).getImageFill(anyString());
  }

  @Test
  void missingImageIsSkipped() throws Exception {
    ContentHubBlob image = mock(ContentHubBlob.class);
    when(image.getLength()).thenThrow(new ContentHubException("Figma file file has no image"));
    when(item.getImageFill(anyString())).thenReturn(image);
    describe("picture", "CMPicture", Map.of(
            "title", new Value("1:4", "characters"),
            "data", new Value("1:3", "fills.0.imageRef")));

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getContentProperties()).containsEntry("title", "Lorem ipsum").doesNotContainKey("data");
  }

  @Test
  void viewtypesAreResolvedForTheSite() throws Exception {
    Site site = mock(Site.class);
    Content hero = mock(Content.class);
    Content portrait = mock(Content.class);
    when(viewtypes.site(context)).thenReturn(site);
    when(viewtypes.hasViewtype(anyString())).thenReturn(true);
    when(viewtypes.find("CMTeaser", "hero", site)).thenReturn(Optional.of(hero));
    when(viewtypes.find("CMPicture", "portrait", site)).thenReturn(Optional.of(portrait));
    when(viewtypes.find("CMPicture", "missing", site)).thenReturn(Optional.empty());
    when(descriptions.find(any(FigmaItem.class))).thenReturn(Optional.of(new FigmaTransformDescription("hero",
            "CMTeaser", "hero", Map.of(
                    "pictures", new NewContent("CMPicture", "portrait", Map.of()),
                    "related", new NewContent("CMPicture", "missing", Map.of())))));

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getContentProperties()).containsEntry("viewtype", List.of(hero));
    assertThat(resolve(model, "pictures", 0).getContentProperties()).containsEntry("viewtype", List.of(portrait));
    // unknown viewtypes are skipped
    assertThat(resolve(model, "related", 0).getContentProperties()).doesNotContainKey("viewtype");
  }

  @Test
  void viewtypeIsSkippedForTypesWithoutViewtype() throws Exception {
    when(viewtypes.hasViewtype("CMTeaser")).thenReturn(false);
    when(descriptions.find(any(FigmaItem.class))).thenReturn(Optional.of(new FigmaTransformDescription("hero",
            "CMTeaser", "hero", Map.of())));

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getContentProperties()).doesNotContainKey("viewtype");
    verify(viewtypes, never()).find(anyString(), anyString(), any());
  }

  @Test
  void withoutDescription() throws Exception {
    when(descriptions.find(any(FigmaItem.class))).thenReturn(Optional.empty());
    when(item.getFigmaUrl()).thenReturn("https://www.figma.com/design/file");

    ContentModel model = transformer.transform(item, adapter, context);

    assertThat(model.getCoreMediaContentType()).isEqualTo("CMChannel");
    assertThat(model.getContentProperties()).containsEntry("title", "Component 1");
  }

  private FigmaTransformDescription describe(String name, String type, Map<String, Mapping> properties) {
    FigmaTransformDescription description = new FigmaTransformDescription(name, type, properties);
    when(descriptions.find(any(FigmaItem.class))).thenReturn(Optional.of(description));
    return description;
  }

  private void property(String contentType, String property, CapPropertyDescriptorType type) {
    CapPropertyDescriptor descriptor = mock(CapPropertyDescriptor.class);
    when(descriptor.getType()).thenReturn(type);
    when(descriptions.property(contentType, property)).thenReturn(descriptor);
  }

  @SuppressWarnings("unchecked")
  private static List<ContentModelReference> references(ContentModel model, String property) {
    return (List<ContentModelReference>) model.getContentProperties().get(property);
  }

  private ContentModel resolve(ContentModel model, String property, int index) {
    return transformer.resolveReference(item, references(model, property).get(index), adapter, context);
  }
}

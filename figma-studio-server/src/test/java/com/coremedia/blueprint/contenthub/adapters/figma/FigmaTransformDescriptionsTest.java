package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.content.ContentType;
import com.coremedia.cap.struct.Struct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FigmaTransformDescriptionsTest {

  private ContentRepository contentRepository;
  private FigmaTransformDescriptions transformDescriptions;

  @BeforeEach
  void setUp() {
    contentRepository = mock(ContentRepository.class);
    transformDescriptions = new FigmaTransformDescriptions(contentRepository);
  }

  @Test
  void findsSettingsByCmType() {
    Struct struct = mock(Struct.class);
    when(struct.toNestedMaps()).thenReturn(Map.of("initialType", "CMTeaser", "properties", Map.of("title", "1:4->characters")));
    Content settings = settings("CMSettings");
    when(settings.getStruct("settings")).thenReturn(struct);
    when(contentRepository.getChild("/Settings/Options/Settings/Figma/Teaser")).thenReturn(settings);

    assertThat(transformDescriptions.find(item("Teaser"))).contains(new FigmaTransformDescription("Teaser", "CMTeaser",
            Map.of("title", new FigmaTransformDescription.Value("1:4", "characters"))));
  }

  @Test
  void ignoresItemsWithoutCmType() {
    assertThat(transformDescriptions.find(item(null))).isEmpty();
    verify(contentRepository, never()).getChild(anyString());
  }

  @Test
  void ignoresMissingAndNonSettingsContent() {
    Content article = settings("CMArticle");
    when(contentRepository.getChild("/Settings/Options/Settings/Figma/Article")).thenReturn(article);

    assertThat(transformDescriptions.find("Missing")).isEmpty();
    assertThat(transformDescriptions.find("Article")).isEmpty();
  }

  @Test
  void rejectsNamesWithPathSeparators() {
    assertThat(transformDescriptions.find("../Teaser")).isEmpty();
    assertThat(transformDescriptions.find(" ")).isEmpty();
    verify(contentRepository, never()).getChild(anyString());
  }

  private static Content settings(String typeName) {
    ContentType type = mock(ContentType.class);
    when(type.isSubtypeOf("CMSettings")).thenReturn("CMSettings".equals(typeName));
    Content content = mock(Content.class);
    when(content.getType()).thenReturn(type);
    return content;
  }

  private static FigmaItem item(String cmType) {
    FigmaItem item = mock(FigmaItem.class);
    when(item.getComponentProperty("cm-type")).thenReturn(Optional.ofNullable(cmType));
    return item;
  }
}

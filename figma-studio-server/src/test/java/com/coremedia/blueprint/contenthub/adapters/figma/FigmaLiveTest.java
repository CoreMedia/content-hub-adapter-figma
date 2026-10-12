package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cache.Cache;
import com.coremedia.cache.CacheKey;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.multisite.SitesService;
import com.coremedia.contenthub.api.ContentHubAdapter;
import com.coremedia.contenthub.api.ContentHubBlob;
import com.coremedia.contenthub.api.ContentHubContext;
import com.coremedia.contenthub.api.ContentHubObject;
import com.coremedia.contenthub.api.ContentModel;
import com.coremedia.contenthub.api.Folder;
import com.coremedia.contenthub.api.Item;
import com.coremedia.contenthub.api.pagination.PaginationRequest;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real Figma API. Enable with the environment variables
 * <code>FIGMA_TOKEN</code> and at least one of <code>FIGMA_TEAM_IDS</code>,
 * <code>FIGMA_FOLDER_IDS</code> or <code>FIGMA_FILE_KEYS</code> (comma separated).
 */
@EnabledIfEnvironmentVariable(named = "FIGMA_TOKEN", matches = ".+")
class FigmaLiveTest {

  private static final ContentHubContext CONTEXT = new ContentHubContext() {
    @Override
    public String getSiteName() {
      return "test";
    }

    @Override
    public Locale getSiteLocale() {
      return Locale.ENGLISH;
    }

    @Override
    public Locale getUserLocale() {
      return Locale.ENGLISH;
    }
  };

  @Test
  void browseRenderAndTransform() throws Exception {
    FigmaProperties properties = new FigmaProperties();
    properties.setAccessToken(System.getenv("FIGMA_TOKEN"));
    Cache cache = new Cache("figma-live-test");
    cache.setCapacity(CacheKey.CACHE_CLASS_DEFAULT, 1000);
    ContentHubAdapter adapter = new FigmaContentHubAdapterFactory(properties,
            new FigmaTransformDescriptions(Mockito.mock(ContentRepository.class)),
            new FigmaViewtypes(Mockito.mock(ContentRepository.class), Mockito.mock(SitesService.class)), cache).createAdapter(new EnvSettings(), "figma");

    Folder root = adapter.getRootFolder(CONTEXT);
    Deque<Folder> queue = new ArrayDeque<>(List.of(root));
    List<Item> items = new ArrayList<>();
    while (!queue.isEmpty()) {
      Folder folder = queue.poll();
      List<ContentHubObject> children = adapter.getChildren(CONTEXT, folder, new PaginationRequest()).getChildren();
      System.out.println(folder.getId().getExternalId() + " '" + folder.getDisplayName() + "': " + children.size() + " children");
      for (ContentHubObject child : children) {
        System.out.println("  " + child.getContentHubType().getName() + " " + child.getId().getExternalId() + " '" + child.getDisplayName() + "'");
        // round trip through the adapter as the Studio does
        if (child instanceof Folder childFolder) {
          assertThat(adapter.getFolder(CONTEXT, child.getId())).isEqualTo(childFolder);
          assertThat(adapter.getParent(CONTEXT, child)).isEqualTo(folder);
          queue.add(childFolder);
        } else if (child instanceof Item childItem) {
          assertThat(adapter.getItem(CONTEXT, child.getId())).isEqualTo(childItem);
          assertThat(adapter.getParent(CONTEXT, child)).isEqualTo(folder);
          items.add(childItem);
        }
      }
    }
    assertThat(items).as("found items").isNotEmpty();

    for (Item item : items) {
      ContentHubBlob thumbnail = item.getThumbnailBlob();
      assertThat(thumbnail).isNotNull();
      assertThat(thumbnail.getLength()).isPositive();
      assertThat(item.getDetails()).isNotEmpty();

      ContentModel model = adapter.transformer().transform(item, adapter, CONTEXT);
      ContentHubBlob data = (ContentHubBlob) model.getContentProperties().get("data");
      System.out.println(item.getContentHubType().getName() + " '" + item.getDisplayName() + "': thumbnail " + thumbnail.getContentType()
              + " " + thumbnail.getLength() + " bytes -> content '" + model.getContentName() + "' " + model.getCoreMediaContentType()
              + ", data " + data.getContentType() + " " + data.getLength() + " bytes");
      // without transform descriptions (the repository is a mock), the fallback content type is created
      assertThat(model.getCoreMediaContentType()).isEqualTo("CMChannel");
      assertThat(data.getContentType().getPrimaryType()).isEqualTo("image");
    }

    for (Item item : items) {
      for (String imageRef : ((FigmaItem) item).getNode().findValuesAsText(FigmaContentHubTransformer.IMAGE_REF)) {
        ContentHubBlob image = ((FigmaItem) item).getImageFill(imageRef);
        System.out.println("image fill " + imageRef + " of '" + item.getDisplayName() + "': " + image.getContentType()
                + " " + image.getLength() + " bytes");
        assertThat(image.getContentType().getPrimaryType()).isEqualTo("image");
      }
    }

    Item item = items.get(0);
    String term = item.getName().length() > 3 ? item.getName().substring(0, 3) : item.getName();
    List<ContentHubObject> hits = adapter.searchService().orElseThrow()
            .search(term, null, null, List.of(), List.of(), 20).getHits();
    System.out.println("search '" + term + "': " + hits.size() + " hits");
    assertThat(hits).contains(item);
  }

  private static class EnvSettings implements FigmaContentHubSettings {
    @Override
    public String getDisplayName() {
      return "Figma Test";
    }

    @Nullable
    @Override
    public String getAccessToken() {
      return null;
    }

    @Override
    public List<String> getTeamIds() {
      return list("FIGMA_TEAM_IDS");
    }

    @Override
    public List<String> getFolderIds() {
      return list("FIGMA_FOLDER_IDS");
    }

    @Override
    public List<String> getFileKeys() {
      return list("FIGMA_FILE_KEYS");
    }

    @Nullable
    @Override
    public String getImageFormat() {
      return null;
    }

    @Nullable
    @Override
    public String getImageScale() {
      return null;
    }

    private static List<String> list(String name) {
      String value = System.getenv(name);
      return value == null ? List.of() : Arrays.asList(value.split(","));
    }
  }
}

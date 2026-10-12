package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.contenthub.api.ContentHubObject;
import com.coremedia.contenthub.api.ContentHubType;
import com.coremedia.contenthub.api.Folder;
import com.coremedia.contenthub.api.exception.ContentHubException;
import com.coremedia.contenthub.api.search.ContentHubSearchResult;
import com.coremedia.contenthub.api.search.ContentHubSearchService;
import com.coremedia.contenthub.api.search.Sort;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Figma has no search API, so this search walks the configured hierarchy (below the given folder)
 * and matches the names of the components and instances on pages. Responses are cached by the
 * {@link FigmaClient}, but the number of scanned pages is limited to protect the Figma rate limit.
 */
@DefaultAnnotation(NonNull.class)
class FigmaSearchService implements ContentHubSearchService {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaSearchService.class);

  private static final int MAX_SCANNED_PAGES = 30;
  private static final int DEFAULT_LIMIT = 100;

  private final FigmaContentHubAdapter adapter;

  FigmaSearchService(FigmaContentHubAdapter adapter) {
    this.adapter = adapter;
  }

  @Override
  public Collection<ContentHubType> supportedTypes() {
    return List.of(FigmaComponentItem.TYPE_COMPONENT, FigmaComponentItem.TYPE_INSTANCE);
  }

  @Override
  public boolean supportsSearchBelowFolder() {
    return true;
  }

  @Override
  public ContentHubSearchResult search(String query, @Nullable Folder belowFolder, @Nullable ContentHubType type,
                                       Collection<String> filterQueries, List<Sort> sortCriteria, int limit) {
    String term = query.trim().toLowerCase(Locale.ROOT);
    if ("*".equals(term)) {
      term = "";
    }
    int max = limit > 0 ? limit : DEFAULT_LIMIT;
    List<ContentHubObject> hits = new ArrayList<>();
    Deque<FigmaId> queue = new ArrayDeque<>();
    FigmaId start = belowFolder == null ? FigmaId.ROOT : adapter.figmaId(belowFolder);
    if (start == null) {
      return new ContentHubSearchResult(List.of());
    }
    queue.add(start);
    int scannedPages = 0;

    while (!queue.isEmpty() && hits.size() < max) {
      FigmaId current = queue.poll();
      if (current.getKind() == FigmaId.Kind.PAGE && ++scannedPages > MAX_SCANNED_PAGES) {
        LOG.info("Figma search for '{}' stopped after scanning {} pages", query, MAX_SCANNED_PAGES);
        break;
      }
      List<? extends ContentHubObject> children;
      try {
        children = adapter.children(current);
      } catch (ContentHubException e) {
        LOG.warn("Figma search skips {}: {}", current, e.getMessage());
        continue;
      }
      for (ContentHubObject child : children) {
        if (child instanceof FigmaFolder folder) {
          queue.add(folder.getFigmaId());
        }
        if (matches(child, term, type) && hits.size() < max) {
          hits.add(child);
        }
      }
    }
    return new ContentHubSearchResult(hits);
  }

  private static boolean matches(ContentHubObject object, String term, @Nullable ContentHubType type) {
    ContentHubType objectType = object.getContentHubType();
    boolean typeMatches = type == null ? supportedType(objectType) : type.equals(objectType);
    return typeMatches && object.getName().toLowerCase(Locale.ROOT).contains(term);
  }

  private static boolean supportedType(ContentHubType type) {
    return FigmaComponentItem.TYPE_COMPONENT.equals(type) || FigmaComponentItem.TYPE_INSTANCE.equals(type);
  }
}

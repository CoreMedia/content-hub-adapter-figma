package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.common.CapPropertyDescriptorType;
import com.coremedia.cap.common.descriptors.LinkPropertyDescriptor;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.content.ContentType;
import com.coremedia.cap.multisite.Site;
import com.coremedia.cap.multisite.SitesService;
import com.coremedia.contenthub.api.ContentHubContext;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Looks up the viewtypes applicable to a content type, like the viewtype selector of the Studio forms
 * (see <code>ViewtypeUtil</code> in bpbase-studio-components):
 * <ul>
 *   <li>the viewtypes are the {@link #VIEWTYPE_TYPE} documents in the site-relative folder {@link #SITE_FOLDER} and
 *   in the global folder {@link #GLOBAL_FOLDER},</li>
 *   <li>in each of these folders, the ones directly in the folder and the ones in the subfolders named after
 *   the content type and its supertypes, e.g. <code>/Settings/Options/Viewtypes/CMTeaser</code>.</li>
 * </ul>
 * Transform descriptions are global, so they refer to a viewtype by its {@link #key}, which is resolved for the
 * site at import time. A site-specific viewtype overrides a global one with the same key.
 */
@DefaultAnnotation(NonNull.class)
class FigmaViewtypes {
  static final String GLOBAL_FOLDER = "/Settings/Options/Viewtypes";
  static final String SITE_FOLDER = "Options/Viewtypes";
  static final String VIEWTYPE_TYPE = "CMViewtype";
  static final String VIEWTYPE_PROPERTY = "viewtype";
  private static final String LAYOUT_PROPERTY = "layout";
  /**
   * Supertypes without a viewtype folder of their own, as in the Studio forms.
   */
  private static final Set<String> EXCLUDED_TYPES = Set.of("Document_", "Content_", "CMHasContexts");

  private final ContentRepository contentRepository;
  private final SitesService sitesService;

  FigmaViewtypes(ContentRepository contentRepository, SitesService sitesService) {
    this.contentRepository = contentRepository;
    this.sitesService = sitesService;
  }

  /**
   * Whether the content type has a {@link #VIEWTYPE_PROPERTY} link to viewtypes.
   */
  boolean hasViewtype(String contentType) {
    ContentType type = contentRepository.getContentType(contentType);
    ContentType viewtypeType = contentRepository.getContentType(VIEWTYPE_TYPE);
    if (type == null || viewtypeType == null) {
      return false;
    }
    CapPropertyDescriptor descriptor = type.getDescriptorsByName().get(VIEWTYPE_PROPERTY);
    return descriptor != null && descriptor.getType() == CapPropertyDescriptorType.LINK
            && viewtypeType.isSubtypeOf(((LinkPropertyDescriptor) descriptor).getLinkType());
  }

  /**
   * The viewtypes applicable to the content type: the ones of the site first, then the global ones, the ones for
   * the most specific type first.
   */
  List<Content> applicable(String contentType, @Nullable Site site) {
    ContentType type = contentRepository.getContentType(contentType);
    ContentType viewtypeType = contentRepository.getContentType(VIEWTYPE_TYPE);
    if (type == null || viewtypeType == null) {
      return List.of();
    }
    List<Content> baseFolders = new ArrayList<>();
    if (site != null && site.getSiteRootFolder() != null) {
      baseFolders.add(site.getSiteRootFolder().getChild(SITE_FOLDER));
    }
    baseFolders.add(contentRepository.getChild(GLOBAL_FOLDER));
    Set<Content> result = new LinkedHashSet<>();
    for (Content baseFolder : baseFolders) {
      if (baseFolder == null || !baseFolder.isFolder()) {
        continue;
      }
      for (ContentType current = type; current != null; current = current.getParent()) {
        if (!EXCLUDED_TYPES.contains(current.getName())) {
          addViewtypes(baseFolder.getChild(current.getName()), viewtypeType, result);
        }
      }
      addViewtypes(baseFolder, viewtypeType, result);
    }
    return List.copyOf(result);
  }

  /**
   * The applicable viewtype with the given {@link #key}, preferring the one of the site.
   */
  Optional<Content> find(String contentType, String key, @Nullable Site site) {
    return applicable(contentType, site).stream().filter(viewtype -> key.equals(key(viewtype))).findFirst();
  }

  /**
   * The key a transform description refers to the viewtype with: its layout, or its name if it has no layout.
   */
  static String key(Content viewtype) {
    String layout = viewtype.getType().getDescriptorsByName().containsKey(LAYOUT_PROPERTY)
            ? viewtype.getString(LAYOUT_PROPERTY) : null;
    return layout == null || layout.isBlank() ? viewtype.getName() : layout.trim();
  }

  /**
   * The site to import into: the preferred site of the user, identified by its name and locale.
   */
  @Nullable
  Site site(ContentHubContext context) {
    String name = context.getSiteName();
    if (name == null) {
      return null;
    }
    List<Site> sites = sitesService.getSites().stream().filter(site -> name.equals(site.getName())).toList();
    // sites derived by translation usually share the name of their master
    return sites.stream()
            .filter(site -> Objects.equals(site.getLocale(), context.getSiteLocale()))
            .findFirst()
            .orElse(sites.isEmpty() ? null : sites.get(0));
  }

  @Nullable
  Site site(@Nullable String siteId) {
    return siteId == null || siteId.isBlank() ? null : sitesService.getSite(siteId);
  }

  private static void addViewtypes(@Nullable Content folder, ContentType viewtypeType, Set<Content> result) {
    if (folder == null || !folder.isFolder()) {
      return;
    }
    folder.getChildDocuments().stream()
            .filter(content -> !content.isDestroyed() && content.getType().isSubtypeOf(viewtypeType))
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .forEach(result::add);
  }
}

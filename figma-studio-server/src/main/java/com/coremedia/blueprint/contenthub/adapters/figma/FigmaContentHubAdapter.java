package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.contenthub.api.ContentHubAdapter;
import com.coremedia.contenthub.api.ContentHubContext;
import com.coremedia.contenthub.api.ContentHubObject;
import com.coremedia.contenthub.api.ContentHubObjectId;
import com.coremedia.contenthub.api.ContentHubTransformer;
import com.coremedia.contenthub.api.Folder;
import com.coremedia.contenthub.api.GetChildrenResult;
import com.coremedia.contenthub.api.Item;
import com.coremedia.contenthub.api.exception.ContentHubException;
import com.coremedia.contenthub.api.pagination.PaginationRequest;
import com.coremedia.contenthub.api.search.ContentHubSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Content Hub adapter for Figma.
 * <p>
 * Hierarchy: root &rarr; teams &rarr; folders (nested) &rarr; files and pages &rarr; components.
 * The children of a folder are its files, each followed by the pages of the file as folders. The children
 * of a page are the components used on the page, i.e. its instance nodes. Teams and folders are read with
 * the Figma v2 folders API. Files and components are imported as CMPicture with a rendition rendered by Figma.
 */
@DefaultAnnotation(NonNull.class)
class FigmaContentHubAdapter implements ContentHubAdapter {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaContentHubAdapter.class);

  static final String FIGMA_WEB = "https://www.figma.com";
  private static final String DEFAULT_DISPLAY_NAME = "Figma";
  private static final Set<String> SUPPORTED_FORMATS = Set.of("png", "jpg", "svg");
  private static final double MIN_SCALE = 0.01;
  private static final double MAX_SCALE = 4;

  private final FigmaContentHubSettings settings;
  private final String connectionId;
  private final FigmaClient client;
  private final String imageFormat;
  private final double imageScale;
  private final FigmaContentHubTransformer transformer;
  private final FigmaSearchService searchService;

  /**
   * Remembers the parents of teams, folders and files, since this cannot be derived from their ids.
   */
  private final Map<FigmaId, FigmaId> parents = new ConcurrentHashMap<>();

  FigmaContentHubAdapter(FigmaContentHubSettings settings, String connectionId, FigmaClient client,
                         FigmaTransformDescriptions transformDescriptions, FigmaViewtypes viewtypes) {
    this.settings = settings;
    this.connectionId = connectionId;
    this.client = client;
    this.imageFormat = parseFormat(settings.getImageFormat());
    this.imageScale = parseScale(settings.getImageScale());
    this.transformer = new FigmaContentHubTransformer(transformDescriptions, viewtypes);
    this.searchService = new FigmaSearchService(this);
  }

  // --- ContentHubAdapter ------------------------------------------

  @Override
  public Folder getRootFolder(ContentHubContext context) {
    return rootFolder();
  }

  @Nullable
  @Override
  public Folder getFolder(ContentHubContext context, ContentHubObjectId id) {
    return ifExists(() -> {
      FigmaId figmaId = resolve(id.getExternalId());
      return figmaId == null ? null : folder(figmaId);
    });
  }

  @Nullable
  @Override
  public Item getItem(ContentHubContext context, ContentHubObjectId id) {
    return ifExists(() -> {
      FigmaId figmaId = resolve(id.getExternalId());
      return figmaId == null ? null : item(figmaId);
    });
  }

  @Nullable
  @Override
  public Folder getParent(ContentHubContext context, ContentHubObject hubObject) {
    FigmaId figmaId = figmaId(hubObject);
    if (figmaId == null) {
      return null;
    }
    return ifExists(() -> switch (figmaId.getKind()) {
      case ROOT -> null;
      case PAGE -> folder(FigmaId.file(figmaId.getKey()));
      case COMPONENT -> folder(figmaId.asPage());
      default -> folder(parentOf(figmaId));
    });
  }

  @Override
  public GetChildrenResult getChildren(ContentHubContext context, Folder folder, @Nullable PaginationRequest paginationRequest) {
    FigmaId figmaId = figmaId(folder);
    if (figmaId == null) {
      return new GetChildrenResult(List.of());
    }
    List<ContentHubObject> children = new ArrayList<>(children(figmaId));
    return new GetChildrenResult(children);
  }

  @Override
  public ContentHubTransformer transformer() {
    return transformer;
  }

  @Override
  public Optional<ContentHubSearchService> searchService() {
    return Optional.of(searchService);
  }

  @Override
  public void invalidate(ContentHubContext context, ContentHubObjectId id, @Nullable PaginationRequest paginationRequest) {
    client.clearCache();
  }

  // --- id resolution ----------------------------------------------

  /**
   * Determines the kind of object denoted by an external id. External ids are the plain ids
   * returned by the Figma API (see {@link FigmaId}), so the kind must be derived:
   * <ul>
   *   <li><code>fileKey:pageId</code> is a page, <code>fileKey:pageId~nodeId</code> is a component.</li>
   *   <li>Numeric ids are teams or folders. Team and folder ids cannot be told apart syntactically,
   *   so they are looked up in the configuration and in the folders of the configured teams.</li>
   *   <li>All other ids are file keys.</li>
   * </ul>
   *
   * @return the resolved id, or null if the external id does not denote a known object
   */
  @Nullable
  FigmaId resolve(String externalId) {
    if (FigmaId.ROOT.asExternalId().equals(externalId)) {
      return FigmaId.ROOT;
    }
    FigmaId node = FigmaId.parseNodeId(externalId);
    if (node != null) {
      return node;
    }
    if (!FigmaId.isNumeric(externalId)) {
      return FigmaId.file(externalId);
    }
    if (nonNull(settings.getTeamIds()).contains(externalId)) {
      return FigmaId.team(externalId);
    }
    if (nonNull(settings.getFolderIds()).contains(externalId) || isFolderOfConfiguredTeam(externalId)) {
      return FigmaId.folder(externalId);
    }
    LOG.debug("Figma id {} is neither a configured team nor a folder of a configured team", externalId);
    return null;
  }

  /**
   * Returns the id of an object created by this adapter without resolving it again, falls back to {@link #resolve(String)}.
   */
  @Nullable
  FigmaId figmaId(ContentHubObject hubObject) {
    if (hubObject instanceof FigmaFolder folder) {
      return folder.getFigmaId();
    }
    if (hubObject instanceof FigmaItem item) {
      return item.getFigmaId();
    }
    return resolve(hubObject.getId().getExternalId());
  }

  /**
   * Returns the parent of a team, folder or file. If the object has not been browsed yet,
   * the parent is looked up in the folders of the configured teams.
   */
  private FigmaId parentOf(FigmaId figmaId) {
    if (!parents.containsKey(figmaId)) {
      if (figmaId.getKind() == FigmaId.Kind.FOLDER) {
        isFolderOfConfiguredTeam(figmaId.getKey());
      } else if (figmaId.getKind() == FigmaId.Kind.FILE) {
        findFolderOfFile(figmaId.getKey());
      }
    }
    return parents.getOrDefault(figmaId, FigmaId.ROOT);
  }

  /**
   * The team folders response contains all folders of a team, including nested ones,
   * so it is sufficient to look up folders there. Also remembers the parent of the folder.
   */
  private boolean isFolderOfConfiguredTeam(String folderId) {
    for (String teamId : nonNull(settings.getTeamIds())) {
      try {
        for (JsonNode folder : client.getTeamFolders(teamId).path("folders")) {
          if (folder.path("id").asText().equals(folderId)) {
            String parentFolderId = folder.path("parent_folder_id").asText(null);
            parents.put(FigmaId.folder(folderId), parentFolderId == null ? FigmaId.team(teamId) : FigmaId.folder(parentFolderId));
            return true;
          }
        }
      } catch (ContentHubException e) {
        LOG.warn("Cannot read folders of Figma team {}: {}", teamId, e.getMessage());
      }
    }
    return false;
  }

  private void findFolderOfFile(String fileKey) {
    for (String teamId : nonNull(settings.getTeamIds())) {
      try {
        for (JsonNode folder : client.getTeamFolders(teamId).path("folders")) {
          String folderId = folder.path("id").asText();
          for (JsonNode file : client.getFolderFiles(folderId).path("files")) {
            if (file.path("key").asText().equals(fileKey)) {
              parents.put(FigmaId.file(fileKey), FigmaId.folder(folderId));
              return;
            }
          }
        }
      } catch (ContentHubException e) {
        LOG.warn("Cannot read folders of Figma team {}: {}", teamId, e.getMessage());
      }
    }
  }

  // --- navigation, also used by the search service -----------------

  List<? extends ContentHubObject> children(FigmaId figmaId) {
    List<ContentHubObject> result = new ArrayList<>();
    switch (figmaId.getKind()) {
      case ROOT -> {
        for (String teamId : nonNull(settings.getTeamIds())) {
          addSafely(result, () -> teamFolder(teamId, teamName(teamId)));
        }
        for (String folderId : nonNull(settings.getFolderIds())) {
          addSafely(result, () -> folderFolder(folderId, client.getFolderFiles(folderId).path("name").asText(folderId)));
        }
        for (String fileKey : nonNull(settings.getFileKeys())) {
          addSafely(result, () -> fileFolder(fileKey, client.getFile(fileKey)));
        }
      }
      case TEAM -> {
        // the team folders response is flat and contains nested folders, too; only show the top level ones here
        for (JsonNode folder : client.getTeamFolders(figmaId.getKey()).path("folders")) {
          if (folder.path("parent_folder_id").isNull() || folder.path("parent_folder_id").isMissingNode()) {
            result.add(folderFolder(folder.path("id").asText(), folder.path("name").asText()));
          }
        }
      }
      case FOLDER -> {
        for (JsonNode folder : client.getSubFolders(figmaId.getKey()).path("folders")) {
          result.add(folderFolder(folder.path("id").asText(), folder.path("name").asText()));
        }
        // only the folder listing is used here, the file JSON is loaded when the file is opened
        for (JsonNode file : client.getFolderFiles(figmaId.getKey()).path("files")) {
          result.add(fileFolder(file.path("key").asText(), file.path("name").asText(),
                  file.path("last_modified").asText(null), file.path("thumbnail_url").asText(null)));
        }
      }
      case FILE -> result.addAll(pagesOfFile(figmaId.getKey(), client.getFile(figmaId.getKey())));
      case PAGE -> result.addAll(componentsOfPage(figmaId));
      default -> throw new IllegalArgumentException("Not a folder: " + figmaId);
    }
    result.stream()
            .filter(FigmaFolder.class::isInstance)
            .map(child -> ((FigmaFolder) child).getFigmaId())
            .forEach(child -> parents.put(child, figmaId));
    return result;
  }

  @Nullable
  FigmaFolder folder(FigmaId figmaId) {
    String key = figmaId.getKey();
    return switch (figmaId.getKind()) {
      case ROOT -> rootFolder();
      case TEAM -> teamFolder(key, teamName(key));
      case FOLDER -> folderFolder(key, client.getFolderFiles(key).path("name").asText(key));
      case FILE -> fileFolder(key, client.getFile(key));
      case PAGE -> pagesOfFile(key, client.getFile(key)).stream()
              .filter(page -> page.getFigmaId().equals(figmaId))
              .findFirst()
              .orElse(null);
      case COMPONENT -> null;
    };
  }

  /**
   * Returns the item with the given external id, used by the {@link FigmaMappingResource}.
   */
  Optional<FigmaItem> item(String externalId) {
    return Optional.ofNullable(ifExists(() -> {
      FigmaId figmaId = resolve(externalId);
      return figmaId == null ? null : item(figmaId);
    }));
  }

  @Nullable
  FigmaItem item(FigmaId figmaId) {
    if (figmaId.getKind() != FigmaId.Kind.COMPONENT) {
      return null;
    }
    return componentsOfPage(figmaId.asPage()).stream()
            .filter(component -> component.getFigmaId().equals(figmaId))
            .findFirst()
            .orElse(null);
  }

  FigmaFolder rootFolder() {
    String displayName = StringUtils.defaultIfBlank(settings.getDisplayName(), DEFAULT_DISPLAY_NAME);
    return new FigmaFolder(connectionId, FigmaId.ROOT, displayName, null, null, null, null);
  }

  // --- internal ---------------------------------------------------

  private List<FigmaFolder> pagesOfFile(String fileKey, JsonNode file) {
    String lastModified = file.path("lastModified").asText(null);
    List<FigmaFolder> result = new ArrayList<>();
    for (JsonNode page : file.path("document").path("children")) {
      String pageId = page.path("id").asText();
      String pageName = page.path("name").asText(pageId);
      result.add(new FigmaFolder(connectionId, FigmaId.page(fileKey, pageId), pageName,
              FIGMA_WEB + "/design/" + fileKey + "?node-id=" + pageId.replace(':', '-'), lastModified, null, null));
    }
    return result;
  }

  /**
   * The file JSON only contains the top level nodes of the page, so fetch the whole page.
   */
  private List<FigmaComponentItem> componentsOfPage(FigmaId pageId) {
    String fileKey = pageId.getKey();
    JsonNode file = client.getFile(fileKey);
    String fileName = file.path("name").asText(fileKey);
    String lastModified = file.path("lastModified").asText(null);
    JsonNode page = client.getNode(fileKey, Objects.requireNonNull(pageId.getPageId()));
    if (page.isMissingNode()) {
      throw new FigmaClient.FigmaNotFoundException("Figma page " + pageId + " does not exist");
    }
    String pageName = page.path("name").asText(pageId.getPageId());
    List<JsonNode> instances = usedComponents(page);
    List<FigmaComponentData.Sibling> siblings = instances.stream()
            .map(instance -> new FigmaComponentData.Sibling(instance.path("id").asText(), size(instance)[0]))
            .toList();
    return instances.stream()
            .map(instance -> {
              double[] size = size(instance);
              FigmaComponentData data = new FigmaComponentData(fileKey, fileName, pageId.getPageId(), pageName,
                      instance.path("id").asText(), instance.path("name").asText(), size[0], size[1], lastModified,
                      instance, siblings);
              return new FigmaComponentItem(connectionId, data, client, imageFormat, imageScale);
            })
            .toList();
  }

  /**
   * Returns the importable nodes of the page: component definitions, component sets and every visible instance.
   * Nodes are searched depth-first, but not within components or instances, since nested instances
   * are part of the component they are used in. Each instance is listed on its own, also if the page contains
   * several instances of the same component or its definition.
   */
  static List<JsonNode> usedComponents(JsonNode page) {
    List<JsonNode> result = new ArrayList<>();
    collectComponents(page, result);
    return List.copyOf(result);
  }

  private static void collectComponents(JsonNode node, List<JsonNode> result) {
    for (JsonNode child : node.path("children")) {
      if (!child.path("visible").asBoolean(true)) {
        continue;
      }
      switch (child.path("type").asText()) {
        case "INSTANCE", "COMPONENT", "COMPONENT_SET" -> result.add(child);
        default -> collectComponents(child, result);
      }
    }
  }

  private static double[] size(JsonNode node) {
    JsonNode box = node.path("absoluteBoundingBox");
    return new double[]{box.path("width").asDouble(), box.path("height").asDouble()};
  }

  private String teamName(String teamId) {
    return client.getTeamFolders(teamId).path("name").asText(teamId);
  }

  private FigmaFolder teamFolder(String teamId, String name) {
    return new FigmaFolder(connectionId, FigmaId.team(teamId), name, FIGMA_WEB + "/files/team/" + teamId, null, null, null);
  }

  private FigmaFolder folderFolder(String folderId, String name) {
    return new FigmaFolder(connectionId, FigmaId.folder(folderId), name, FIGMA_WEB + "/files/project/" + folderId, null, null, null);
  }

  private FigmaFolder fileFolder(String fileKey, JsonNode file) {
    return fileFolder(fileKey, file.path("name").asText(fileKey), file.path("lastModified").asText(null),
            file.path("thumbnailUrl").asText(null));
  }

  private FigmaFolder fileFolder(String fileKey, String name, @Nullable String lastModified, @Nullable String thumbnailUrl) {
    return new FigmaFolder(connectionId, FigmaId.file(fileKey), name, FIGMA_WEB + "/design/" + fileKey, lastModified,
            thumbnailUrl, client);
  }

  /**
   * A misconfigured team, folder or file must not break the whole connection, so log and skip it.
   */
  private static void addSafely(List<ContentHubObject> result, Supplier<ContentHubObject> supplier) {
    try {
      result.add(supplier.get());
    } catch (ContentHubException e) {
      LOG.warn("Skipping configured Figma entry: {}", e.getMessage());
    }
  }

  @Nullable
  private static <T> T ifExists(Supplier<T> supplier) {
    try {
      return supplier.get();
    } catch (FigmaClient.FigmaNotFoundException e) {
      LOG.debug("Figma object does not exist (anymore): {}", e.getMessage());
      return null;
    }
  }

  private static List<String> nonNull(@Nullable List<String> list) {
    return list == null ? List.of() : list.stream().filter(StringUtils::isNotBlank).map(String::trim).toList();
  }

  static String parseFormat(@Nullable String format) {
    String result = StringUtils.defaultIfBlank(format, "png").trim().toLowerCase();
    if (!SUPPORTED_FORMATS.contains(result)) {
      throw new IllegalArgumentException("Unsupported Figma image format '" + format + "', use one of " + SUPPORTED_FORMATS);
    }
    return result;
  }

  static double parseScale(@Nullable String scale) {
    if (StringUtils.isBlank(scale)) {
      return 1;
    }
    double result = Double.parseDouble(scale.trim());
    if (result < MIN_SCALE || result > MAX_SCALE) {
      throw new IllegalArgumentException("Figma image scale must be between " + MIN_SCALE + " and " + MAX_SCALE + ", but is " + scale);
    }
    return result;
  }
}

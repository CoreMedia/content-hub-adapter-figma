package com.coremedia.blueprint.contenthub.adapters.figma;

import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.Objects;

/**
 * Structured form of the external ids used by the Figma adapter.
 * <p>
 * External ids are the ids returned by the Figma API, without any prefix:
 * <ul>
 *   <li><code>root</code> for the root folder</li>
 *   <li><code>&lt;teamId&gt;</code>, <code>&lt;folderId&gt;</code> (both numeric)</li>
 *   <li><code>&lt;fileKey&gt;</code></li>
 *   <li><code>&lt;fileKey&gt;:&lt;pageId&gt;</code> for pages, since page (node) ids are only unique within
 *   their file. File keys never contain a colon, so the first colon separates the file key from the node id
 *   (which contains colons itself, e.g. <code>12:34</code>).</li>
 *   <li><code>&lt;fileKey&gt;:&lt;pageId&gt;~&lt;nodeId&gt;</code> for components used on a page, i.e. instance nodes.
 *   Node ids never contain a tilde, so the tilde separates the page id from the node id. The page id is part
 *   of the id, so that the parent of a component can be derived from its id.</li>
 * </ul>
 * Since the kind is not part of the id, it is resolved by {@link FigmaContentHubAdapter#resolve(String)}.
 */
@DefaultAnnotation(NonNull.class)
final class FigmaId {

  enum Kind {ROOT, TEAM, FOLDER, FILE, PAGE, COMPONENT}

  private static final char NODE_SEPARATOR = ':';
  private static final char COMPONENT_SEPARATOR = '~';
  static final FigmaId ROOT = new FigmaId(Kind.ROOT, "root", null, null);

  private final Kind kind;
  private final String key;
  @Nullable
  private final String pageId;
  @Nullable
  private final String nodeId;

  private FigmaId(Kind kind, String key, @Nullable String pageId, @Nullable String nodeId) {
    this.kind = kind;
    this.key = key;
    this.pageId = pageId;
    this.nodeId = nodeId;
  }

  static FigmaId team(String teamId) {
    return new FigmaId(Kind.TEAM, teamId, null, null);
  }

  static FigmaId folder(String folderId) {
    return new FigmaId(Kind.FOLDER, folderId, null, null);
  }

  static FigmaId file(String fileKey) {
    return new FigmaId(Kind.FILE, fileKey, null, null);
  }

  static FigmaId page(String fileKey, String pageId) {
    return new FigmaId(Kind.PAGE, fileKey, pageId, null);
  }

  static FigmaId component(String fileKey, String pageId, String nodeId) {
    return new FigmaId(Kind.COMPONENT, fileKey, pageId, nodeId);
  }

  /**
   * Parses <code>&lt;fileKey&gt;:&lt;pageId&gt;</code> and <code>&lt;fileKey&gt;:&lt;pageId&gt;~&lt;nodeId&gt;</code>
   * external ids, returns null if the external id denotes neither a page nor a component.
   */
  @Nullable
  static FigmaId parseNodeId(String externalId) {
    int colon = externalId.indexOf(NODE_SEPARATOR);
    if (colon <= 0 || colon == externalId.length() - 1) {
      return null;
    }
    String fileKey = externalId.substring(0, colon);
    String rest = externalId.substring(colon + 1);
    int tilde = rest.indexOf(COMPONENT_SEPARATOR);
    if (tilde < 0) {
      return page(fileKey, rest);
    }
    if (tilde == 0 || tilde == rest.length() - 1) {
      return null;
    }
    return component(fileKey, rest.substring(0, tilde), rest.substring(tilde + 1));
  }

  static boolean isNumeric(String externalId) {
    return !externalId.isEmpty() && externalId.chars().allMatch(Character::isDigit);
  }

  Kind getKind() {
    return kind;
  }

  /**
   * The team id, folder id or file key, depending on the kind.
   */
  String getKey() {
    return key;
  }

  /**
   * The page id for pages and components, null otherwise.
   */
  @Nullable
  String getPageId() {
    return pageId;
  }

  /**
   * The node id of the instance for components, null otherwise.
   */
  @Nullable
  String getNodeId() {
    return nodeId;
  }

  /**
   * The id of the page of a page or component.
   */
  FigmaId asPage() {
    if (pageId == null) {
      throw new IllegalStateException("Not a page or component: " + this);
    }
    return page(key, pageId);
  }

  String asExternalId() {
    if (pageId == null) {
      return key;
    }
    return nodeId == null ? key + NODE_SEPARATOR + pageId : key + NODE_SEPARATOR + pageId + COMPONENT_SEPARATOR + nodeId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FigmaId other)) {
      return false;
    }
    return kind == other.kind && key.equals(other.key) && Objects.equals(pageId, other.pageId) && Objects.equals(nodeId, other.nodeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, key, pageId, nodeId);
  }

  @Override
  public String toString() {
    return kind.name().toLowerCase() + "[" + asExternalId() + "]";
  }
}

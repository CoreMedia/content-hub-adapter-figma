package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.contenthub.api.ContentHubBlob;
import com.coremedia.contenthub.api.ContentHubObjectId;
import com.coremedia.contenthub.api.ContentHubType;
import com.coremedia.contenthub.api.Folder;
import com.coremedia.contenthub.api.preview.DetailsElement;
import com.coremedia.contenthub.api.preview.DetailsSection;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The root folder, a team, a (possibly nested) Figma folder, a Figma file or a page of a Figma file.
 * Only the components used on a page are items.
 */
@DefaultAnnotation(NonNull.class)
class FigmaFolder implements Folder {

  static final ContentHubType TYPE_TEAM = new ContentHubType("figmaTeam");
  static final ContentHubType TYPE_FOLDER = new ContentHubType("figmaFolder");
  static final ContentHubType TYPE_FILE = new ContentHubType("figmaFile");
  static final ContentHubType TYPE_PAGE = new ContentHubType("figmaPage");

  private final ContentHubObjectId id;
  private final FigmaId figmaId;
  private final String name;
  private final String displayName;
  @Nullable
  private final String figmaUrl;
  @Nullable
  private final String lastModified;
  @Nullable
  private final FigmaBlob thumbnail;

  FigmaFolder(String connectionId, FigmaId figmaId, String name, @Nullable String figmaUrl,
              @Nullable String lastModified, @Nullable String thumbnailUrl, @Nullable FigmaClient client) {
    this(connectionId, figmaId, name, name, figmaUrl, lastModified, thumbnailUrl, client);
  }

  FigmaFolder(String connectionId, FigmaId figmaId, String name, String displayName, @Nullable String figmaUrl,
              @Nullable String lastModified, @Nullable String thumbnailUrl, @Nullable FigmaClient client) {
    this.id = new ContentHubObjectId(connectionId, figmaId.asExternalId());
    this.figmaId = figmaId;
    this.name = name;
    this.displayName = displayName;
    this.figmaUrl = figmaUrl;
    this.lastModified = lastModified;
    this.thumbnail = thumbnailUrl == null || client == null ? null
            : new FigmaBlob(this, ContentHubBlob.THUMBNAIL_BLOB_CLASSIFIER, () -> client.download(thumbnailUrl));
  }

  FigmaId getFigmaId() {
    return figmaId;
  }

  @Override
  public ContentHubObjectId getId() {
    return id;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDisplayName() {
    return displayName;
  }

  @Override
  public ContentHubType getContentHubType() {
    return switch (figmaId.getKind()) {
      case TEAM -> TYPE_TEAM;
      case FOLDER -> TYPE_FOLDER;
      case FILE -> TYPE_FILE;
      case PAGE -> TYPE_PAGE;
      default -> Folder.super.getContentHubType();
    };
  }

  @Nullable
  @Override
  public ContentHubBlob getThumbnailBlob() {
    return thumbnail;
  }

  @Nullable
  @Override
  public ContentHubBlob getBlob(String classifier) {
    return ContentHubBlob.THUMBNAIL_BLOB_CLASSIFIER.equals(classifier) ? thumbnail : null;
  }

  @Override
  public List<DetailsSection> getDetails() {
    List<DetailsSection> sections = new ArrayList<>();
    if (thumbnail != null) {
      sections.add(new DetailsSection("main", List.of(new DetailsElement<>(name, false, thumbnail)), false, false, false));
    }
    List<DetailsElement<?>> metadata = new ArrayList<>();
    metadata.add(new DetailsElement<>("name", name));
    if (lastModified != null) {
      metadata.add(new DetailsElement<>("lastModified", lastModified));
    }
    if (figmaUrl != null) {
      metadata.add(new DetailsElement<>("link", figmaUrl));
    }
    sections.add(new DetailsSection("metadata", metadata));
    return sections;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof FigmaFolder other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + figmaId + ", " + name + "]";
  }
}

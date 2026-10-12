package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.contenthub.api.ContentHubBlob;
import com.coremedia.contenthub.api.ContentHubObjectId;
import com.coremedia.contenthub.api.ContentHubType;
import com.coremedia.contenthub.api.exception.ContentHubException;
import com.coremedia.contenthub.api.preview.DetailsElement;
import com.coremedia.contenthub.api.preview.DetailsSection;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A component used on a Figma page, i.e. an instance node, which is imported as a rendered picture of the instance.
 */
@DefaultAnnotation(NonNull.class)
class FigmaComponentItem implements FigmaItem {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaComponentItem.class);


  static final ContentHubType TYPE_COMPONENT = new ContentHubType("figmaComponent");
  static final ContentHubType TYPE_INSTANCE = new ContentHubType("figmaInstance");
  static final String INSTANCE = "INSTANCE";

  /**
   * Thumbnails are rendered with a scale so that they are at least this wide (unless the component is smaller).
   */
  private static final int THUMBNAIL_MIN_WIDTH = 300;
  private static final double[] THUMBNAIL_SCALES = {1, 0.5, 0.25, 0.1, 0.05};

  private final ContentHubObjectId id;
  private final FigmaId figmaId;
  private final FigmaComponentData component;
  private final FigmaClient client;
  private final String imageFormat;
  private final double imageScale;

  FigmaComponentItem(String connectionId, FigmaComponentData component, FigmaClient client, String imageFormat, double imageScale) {
    this.figmaId = FigmaId.component(component.fileKey(), component.pageId(), component.nodeId());
    this.id = new ContentHubObjectId(connectionId, figmaId.asExternalId());
    this.component = component;
    this.client = client;
    this.imageFormat = imageFormat;
    this.imageScale = imageScale;
  }

  @Override
  public FigmaId getFigmaId() {
    return figmaId;
  }

  FigmaComponentData getComponent() {
    return component;
  }

  @Override
  public String getFigmaUrl() {
    return FigmaContentHubAdapter.FIGMA_WEB + "/design/" + component.fileKey() + "?node-id=" + component.nodeId().replace(':', '-');
  }

  @Override
  public Optional<String> getComponentProperty(String name) {
    return FigmaComponentProperties.find(component.node(), name);
  }

  @Override
  public JsonNode getNode() {
    return component.node();
  }

  /**
   * The classifier contains the <code>imageRef</code>: Content Hub caches blobs by owner and classifier, so that
   * all image fills of the component would otherwise get the data of the first one.
   */
  @Override
  public ContentHubBlob getImageFill(String imageRef) {
    return new FigmaBlob(this, IMAGE_FILL_CLASSIFIER + ":" + imageRef, () -> client.download(
            client.getImageFillUrl(component.fileKey(), imageRef).orElseThrow(() -> new ContentHubException(
                    "Figma file " + component.fileKey() + " has no image " + imageRef))));
  }

  @Override
  public ContentHubObjectId getId() {
    return id;
  }

  @Override
  public String getName() {
    return component.name();
  }

  @Override
  public String getDisplayName() {
    return component.name();
  }

  @Override
  public String getDescription() {
    return component.fileName() + " / " + component.pageName() + " / " + component.name();
  }

  @Override
  public ContentHubType getContentHubType() {
    return INSTANCE.equals(figmaType()) ? TYPE_INSTANCE : TYPE_COMPONENT;
  }

  /**
   * The Figma node type: COMPONENT, COMPONENT_SET or INSTANCE.
   */
  String figmaType() {
    return component.node().path("type").asText();
  }

  @Override
  public String getCoreMediaContentType() {
    return "CMPicture";
  }

  @Override
  public ContentHubBlob getThumbnailBlob() {
    double scale = thumbnailScale(component.width());
    List<String> siblings = component.siblings().stream()
            .filter(sibling -> thumbnailScale(sibling.width()) == scale)
            .map(FigmaComponentData.Sibling::nodeId)
            .toList();
    return render(ContentHubBlob.THUMBNAIL_BLOB_CLASSIFIER, "png", scale, siblings);
  }

  @Nullable
  @Override
  public ContentHubBlob getBlob(String classifier) {
    if (ContentHubBlob.THUMBNAIL_BLOB_CLASSIFIER.equals(classifier)) {
      return getThumbnailBlob();
    }
    if (DATA_CLASSIFIER.equals(classifier)) {
      return render(DATA_CLASSIFIER, imageFormat, imageScale, List.of());
    }
    return null;
  }

  @Override
  public List<DetailsSection> getDetails() {
    List<DetailsElement<?>> metadata = new ArrayList<>();
    metadata.add(new DetailsElement<>("name", component.name()));
    metadata.add(new DetailsElement<>("figmaType", figmaType()));
    metadata.add(new DetailsElement<>("file", component.fileName()));
    metadata.add(new DetailsElement<>("page", component.pageName()));
    if (component.width() > 0 && component.height() > 0) {
      metadata.add(new DetailsElement<>("size", Math.round(component.width()) + " x " + Math.round(component.height())));
    }
    if (component.lastModified() != null) {
      metadata.add(new DetailsElement<>("lastModified", component.lastModified()));
    }
    metadata.add(new DetailsElement<>("link", getFigmaUrl()));
    List<DetailsSection> sections = new ArrayList<>();
    // Studio reads the thumbnail while building the details, so a rendering error must not break the details
    ContentHubBlob thumbnail = getThumbnailBlob();
    try {
      thumbnail.getLength();
      sections.add(new DetailsSection("main", List.of(new DetailsElement<>(component.name(), false, thumbnail)), false, false, false));
    } catch (ContentHubException e) {
      LOG.info("No thumbnail for Figma {}: {}", figmaId, e.getMessage());
      metadata.add(new DetailsElement<>("error", e.getMessage()));
    }
    sections.add(new DetailsSection("metadata", metadata));
    return sections;
  }

  private FigmaBlob render(String classifier, String format, double scale, List<String> batchCandidates) {
    return new FigmaBlob(this, classifier, () -> {
      String url = client.getImageUrl(component.fileKey(), component.nodeId(), format, scale, batchCandidates)
              .orElseThrow(() -> new ContentHubException("Figma could not render " + component.nodeId()
                      + " of file " + component.fileKey() + ". It may have been deleted in Figma, reload the folder."));
      return client.download(url);
    });
  }

  /**
   * Returns the smallest scale that renders the component at least {@link #THUMBNAIL_MIN_WIDTH} wide.
   * Only a few distinct scales are used, so that sibling components can be rendered in one batch.
   */
  static double thumbnailScale(double width) {
    double result = THUMBNAIL_SCALES[0];
    for (double scale : THUMBNAIL_SCALES) {
      if (width * scale < THUMBNAIL_MIN_WIDTH) {
        break;
      }
      result = scale;
    }
    return result;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof FigmaComponentItem other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + figmaId + ", " + component.name() + "]";
  }
}

package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.contenthub.api.ContentHubBlob;
import com.coremedia.contenthub.api.ContentHubObject;
import com.coremedia.contenthub.api.exception.ContentHubException;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import jakarta.activation.MimeType;
import jakarta.activation.MimeTypeParseException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.function.Supplier;

/**
 * A blob which is loaded lazily on first access.
 * <p>
 * The Content Hub asks for thumbnail blobs of every listed object, but only needs the actual data
 * when the thumbnail is displayed. Rendering images is the most expensive (and most rate-limited)
 * Figma operation, so it is deferred until really needed.
 */
@DefaultAnnotation(NonNull.class)
class FigmaBlob implements ContentHubBlob {

  private final ContentHubObject owner;
  private final String classifier;
  private final Supplier<FigmaClient.Download> loader;
  @Nullable
  private volatile FigmaClient.Download download;

  FigmaBlob(ContentHubObject owner, String classifier, Supplier<FigmaClient.Download> loader) {
    this.owner = owner;
    this.classifier = classifier;
    this.loader = loader;
  }

  @Override
  public ContentHubObject getOwner() {
    return owner;
  }

  @Override
  public String getClassifier() {
    return classifier;
  }

  @Override
  public MimeType getContentType() {
    String contentType = load().contentType();
    try {
      return new MimeType(contentType);
    } catch (MimeTypeParseException e) {
      throw new ContentHubException("Invalid content type " + contentType + " of " + owner.getId(), e);
    }
  }

  @Override
  public long getLength() {
    return load().data().length;
  }

  @Override
  public InputStream getInputStream() {
    return new ByteArrayInputStream(load().data());
  }

  private FigmaClient.Download load() {
    FigmaClient.Download result = download;
    if (result == null) {
      synchronized (this) {
        result = download;
        if (result == null) {
          result = loader.get();
          download = result;
        }
      }
    }
    return result;
  }
}

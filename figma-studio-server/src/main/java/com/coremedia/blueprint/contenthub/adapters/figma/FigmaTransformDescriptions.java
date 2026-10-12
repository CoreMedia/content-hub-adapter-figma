package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.content.ContentType;
import com.coremedia.cap.struct.Struct;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Looks up transform descriptions, which define how a Figma component is transformed into content.
 * <p>
 * A transform description is a CMSettings content in {@link #FOLDER}. Its name is the value of the
 * component property {@link #CM_TYPE_PROPERTY} of the imported component, and the description itself
 * is the <code>settings</code> struct of the content, see {@link FigmaTransformDescription}.
 */
@DefaultAnnotation(NonNull.class)
class FigmaTransformDescriptions {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaTransformDescriptions.class);

  static final String FOLDER = "/Settings/Options/Settings/Figma";
  static final String CM_TYPE_PROPERTY = "cm-type";
  private static final String SETTINGS_TYPE = "CMSettings";
  private static final String SETTINGS_PROPERTY = "settings";

  private final ContentRepository contentRepository;

  FigmaTransformDescriptions(ContentRepository contentRepository) {
    this.contentRepository = contentRepository;
  }

  /**
   * Returns the transform description of the item, or empty if the item has no {@link #CM_TYPE_PROPERTY}
   * component property or no matching transform description exists.
   */
  Optional<FigmaTransformDescription> find(FigmaItem item) {
    return item.getComponentProperty(CM_TYPE_PROPERTY).flatMap(this::find);
  }

  /**
   * Returns the transform description of a layer (an instance or component) by its {@link #CM_TYPE_PROPERTY}.
   */
  Optional<FigmaTransformDescription> find(JsonNode layer) {
    return FigmaComponentProperties.find(layer, CM_TYPE_PROPERTY).flatMap(this::find);
  }

  /**
   * Returns the transform description with the given name.
   *
   * @throws IllegalArgumentException if the description is invalid
   */
  Optional<FigmaTransformDescription> find(String name) {
    if (!isValidName(name)) {
      LOG.warn("Invalid name of a Figma transform description: '{}'", name);
      return Optional.empty();
    }
    Content content = contentRepository.getChild(FOLDER + "/" + name);
    if (content == null || content.isDestroyed() || !content.getType().isSubtypeOf(SETTINGS_TYPE)) {
      LOG.debug("No Figma transform description '{}' found in {}", name, FOLDER);
      return Optional.empty();
    }
    @Nullable Struct settings = content.getStruct(SETTINGS_PROPERTY);
    return Optional.ofNullable(settings).map(struct -> FigmaTransformDescription.parse(name, struct.toNestedMaps()));
  }

  /**
   * Names are content names in {@link #FOLDER}, so they must not be empty or contain a path separator.
   */
  static boolean isValidName(String name) {
    return !name.isBlank() && !name.contains("/") && !name.startsWith(".");
  }

  /**
   * Returns the descriptor of a property of a content type, or null if the type or property does not exist.
   */
  @Nullable
  CapPropertyDescriptor property(String contentType, String property) {
    ContentType type = contentRepository.getContentType(contentType);
    return type == null ? null : type.getDescriptorsByName().get(property);
  }
}

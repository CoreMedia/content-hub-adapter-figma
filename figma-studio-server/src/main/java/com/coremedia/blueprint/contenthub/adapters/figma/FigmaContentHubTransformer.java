package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Component;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Contents;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Mapping;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Richtext;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.NewContent;
import com.coremedia.blueprint.contenthub.adapters.figma.FigmaTransformDescription.Value;
import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.common.CapPropertyDescriptorType;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.multisite.Site;
import com.coremedia.contenthub.api.ContentCreationUtil;
import com.coremedia.contenthub.api.ContentHubAdapter;
import com.coremedia.contenthub.api.ContentHubBlob;
import com.coremedia.contenthub.api.ContentHubContentCreationErrorCode;
import com.coremedia.contenthub.api.ContentHubContentCreationException;
import com.coremedia.contenthub.api.ContentHubContext;
import com.coremedia.contenthub.api.ContentHubObject;
import com.coremedia.contenthub.api.ContentHubTransformer;
import com.coremedia.contenthub.api.ContentModel;
import com.coremedia.contenthub.api.ContentModelReference;
import com.coremedia.contenthub.api.Item;
import com.coremedia.contenthub.api.exception.ContentHubException;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transforms a component used on a page into content.
 * <p>
 * If the component has a {@link FigmaTransformDescriptions#CM_TYPE_PROPERTY} component property, the transform
 * description of that name defines the created content and the linked contents, see {@link FigmaTransformDescription}.
 * Linked contents are created by Content Hub with {@link #resolveReference}. Viewtypes are resolved for the preferred
 * site of the user, see {@link FigmaViewtypes}. Markup with embedded contents is written as {@link FigmaMarkup}, which
 * {@link FigmaMarkupWriteInterceptor} converts to richtext once the embedded contents are created. Otherwise, a CMChannel with a rendition rendered by Figma is created.
 */
@DefaultAnnotation(NonNull.class)
class FigmaContentHubTransformer implements ContentHubTransformer {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaContentHubTransformer.class);

  private static final int MAX_NAME_LENGTH = 200;
  /**
   * Maximum nesting of linked contents, protects against deeply nested or recursive descriptions.
   */
  static final int MAX_DEPTH = 5;
  static final String CONTENT_TYPE_PROPERTY = "cType";
  private static final String DATA_PROPERTY = "data";
  /**
   * The last path segment of the hash of an image fill, e.g. <code>fills[0].imageRef</code>.
   */
  static final String IMAGE_REF = "imageRef";

  private final FigmaTransformDescriptions transformDescriptions;
  private final FigmaViewtypes viewtypes;

  FigmaContentHubTransformer(FigmaTransformDescriptions transformDescriptions, FigmaViewtypes viewtypes) {
    this.transformDescriptions = transformDescriptions;
    this.viewtypes = viewtypes;
  }

  /**
   * The data of a linked content, which is transformed in {@link #resolveReference}.
   *
   * @param item         the imported item
   * @param viewtype     the key of the viewtype of the content, see {@link FigmaViewtypes#key}
   * @param root         the layer that the layer ids of the properties refer to
   * @param depth        the nesting depth of the content, the imported content has depth 0
   * @param descriptions the names of the descriptions applied on the way to this content, to detect cycles
   */
  record Nested(FigmaItem item, String name, String type, @Nullable String viewtype, Map<String, Mapping> properties,
                JsonNode root, int depth, Set<String> descriptions) {
  }

  @Override
  public ContentModel transform(Item item, ContentHubAdapter contentHubAdapter, ContentHubContext contentHubContext)
          throws ContentHubContentCreationException {
    if (!(item instanceof FigmaItem figmaItem)) {
      throw new IllegalArgumentException("Not a Figma item: " + item);
    }
    Optional<FigmaTransformDescription> description;
    try {
      description = transformDescriptions.find(figmaItem);
    } catch (IllegalArgumentException e) {
      throw new ContentHubContentCreationException("Cannot create content from Figma " + figmaItem.getFigmaId()
              + ": " + e.getMessage(), e, FigmaContentCreationErrorCode.INVALID_TRANSFORM_DESCRIPTION);
    }
    try {
      return description.isPresent()
              ? transform(figmaItem, description.get(), contentHubContext)
              : transformWithoutDescription(figmaItem);
    } catch (RuntimeException e) {
      throw new ContentHubContentCreationException("Cannot create content from Figma " + figmaItem.getFigmaId()
              + ": " + e.getMessage(), e, FigmaContentCreationErrorCode.RENDERING_FAILED);
    }
  }

  @Override
  public ContentModel resolveReference(ContentHubObject owner, ContentModelReference reference,
                                       ContentHubAdapter contentHubAdapter, ContentHubContext contentHubContext) {
    if (!(reference.getData() instanceof Nested nested)) {
      throw new IllegalArgumentException("Not a Figma reference: " + reference);
    }
    ContentModel model = ContentModel.createReferenceModel(nested.name(), nested.type());
    apply(model, nested, contentHubContext);
    return model;
  }

  private ContentModel transform(FigmaItem item, FigmaTransformDescription description, ContentHubContext context) {
    String type = description.initialType();
    ContentModel model = ContentModel.createContentModel(contentName(item.getName()), item.getId(), type);
    apply(model, new Nested(item, model.getContentName(), type, description.viewtype(), description.properties(),
            item.getNode(), 0, Set.of(description.name())), context);
    CapPropertyDescriptor data = transformDescriptions.property(type, DATA_PROPERTY);
    if (!description.properties().containsKey(DATA_PROPERTY) && data != null && data.getType() == CapPropertyDescriptorType.BLOB) {
      putData(model, item);
    }
    return model;
  }

  /**
   * Without a transform description, a CMChannel with the rendition is created.
   */
  private static ContentModel transformWithoutDescription(FigmaItem item) {
    String name = item.getName();
    ContentModel model = ContentModel.createContentModel(contentName(name), item.getId(), "CMChannel");
    model.put("title", name);
    model.put("alt", name);
    model.put("detailText", ContentCreationUtil.convertStringToRichtext(
            "Imported from Figma: " + item.getDescription() + " (" + item.getFigmaUrl() + ")"));
    putData(model, item);
    return model;
  }

  private static void putData(ContentModel model, FigmaItem item) {
    ContentHubBlob blob = item.getBlob(FigmaItem.DATA_CLASSIFIER);
    if (blob != null) {
      // Access the blob here, so that rendering errors are reported as content creation errors.
      blob.getLength();
      model.put(DATA_PROPERTY, blob);
    }
  }

  // --- mapping ----------------------------------------------------

  /**
   * Sets the properties and the viewtype of the content, mappings that cannot be applied are logged and skipped.
   */
  private void apply(ContentModel model, Nested content, ContentHubContext context) {
    for (Map.Entry<String, Mapping> entry : content.properties().entrySet()) {
      String property = entry.getKey();
      switch (entry.getValue()) {
        case Value value -> value(content, property, value).ifPresent(v -> model.put(property, v));
        case Contents contents -> {
          List<ContentModelReference> references = new ArrayList<>();
          for (Mapping mapping : contents.contents()) {
            String name = content.name() + " - " + property + " " + (references.size() + 1);
            reference(model, content, property, name, mapping).ifPresent(references::add);
          }
          model.put(property, references);
        }
        case NewContent newContent -> reference(model, content, property, content.name() + " - " + property, newContent)
                .ifPresent(reference -> model.put(property, List.of(reference)));
        case Component component -> reference(model, content, property, content.name() + " - " + property, component)
                .ifPresent(reference -> model.put(property, List.of(reference)));
        case Richtext markup -> markup(model, content, property, markup);
      }
    }
    if (content.viewtype() != null) {
      viewtype(content, context).ifPresent(viewtype -> model.put(FigmaViewtypes.VIEWTYPE_PROPERTY, List.of(viewtype)));
    }
  }

  private Optional<Content> viewtype(Nested content, ContentHubContext context) {
    String key = content.viewtype();
    if (!viewtypes.hasViewtype(content.type())) {
      LOG.warn("Figma transform: content type {} has no viewtype, ignoring viewtype '{}'", content.type(), key);
      return Optional.empty();
    }
    @Nullable Site site = viewtypes.site(context);
    Optional<Content> viewtype = viewtypes.find(content.type(), key, site);
    if (viewtype.isEmpty()) {
      LOG.warn("Figma transform: no viewtype '{}' applicable to {} (site {})", key, content.type(),
              site == null ? "none" : site.getName());
    }
    return viewtype;
  }

  private Optional<Object> value(Nested content, String property, Value value) {
    Optional<String> raw = FigmaNodes.findLayer(content.root(), value.layerId())
            .flatMap(layer -> FigmaNodes.value(layer, value.path()));
    if (raw.isEmpty()) {
      LOG.warn("Figma transform: no value at {}->{} for property {} of {}", value.layerId(), value.path(), property,
              content.type());
      return Optional.empty();
    }
    return convert(content, property, value, raw.get());
  }

  /**
   * Converts the value to the type of the property. The <code>imageRef</code> of an image fill
   * is mapped to a blob property as the image itself.
   */
  private Optional<Object> convert(Nested content, String property, Value mapping, String value) {
    String contentType = content.type();
    CapPropertyDescriptor descriptor = transformDescriptions.property(contentType, property);
    if (descriptor == null) {
      LOG.warn("Figma transform: content type {} has no property {}", contentType, property);
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(switch (descriptor.getType()) {
        case STRING -> value;
        case MARKUP -> ContentCreationUtil.convertStringToRichtext(value);
        case INTEGER -> Integer.valueOf(value.trim());
        case LONG -> Long.valueOf(value.trim());
        case BOOLEAN -> Boolean.valueOf(value.trim());
        case BLOB -> {
          if (isImageRef(mapping)) {
            yield imageFill(content.item(), property, value);
          }
          LOG.warn("Figma transform: only an {} can be mapped to blob property {} of {}, but was {}", IMAGE_REF, property,
                  contentType, mapping.path());
          yield null;
        }
        default -> {
          LOG.warn("Figma transform: cannot map a value to property {} of type {} of {}", property, descriptor.getType(),
                  contentType);
          yield null;
        }
      });
    } catch (NumberFormatException e) {
      LOG.warn("Figma transform: '{}' is not a number for property {} of {}", value, property, contentType);
      return Optional.empty();
    }
  }

  /**
   * Texts are resolved here, embedded contents are put as references under keys of their own, so that Content Hub
   * creates them before the owner, see {@link FigmaMarkup}.
   */
  private void markup(ContentModel model, Nested content, String property, Richtext markup) {
    CapPropertyDescriptor descriptor = transformDescriptions.property(content.type(), property);
    if (descriptor == null || descriptor.getType() != CapPropertyDescriptorType.MARKUP) {
      LOG.warn("Figma transform: {} has no markup property {}", content.type(), property);
      return;
    }
    List<FigmaMarkup.Part> parts = new ArrayList<>();
    int embeds = 0;
    for (Mapping item : markup.items()) {
      if (item instanceof Value value) {
        Optional<String> text = FigmaNodes.findLayer(content.root(), value.layerId())
                .flatMap(layer -> FigmaNodes.value(layer, value.path()));
        if (text.isEmpty()) {
          LOG.warn("Figma transform: no value at {}->{} for markup property {} of {}", value.layerId(), value.path(),
                  property, content.type());
        } else if (!text.get().isBlank()) {
          parts.add(new FigmaMarkup.Text(text.get()));
        }
        continue;
      }
      String key = FigmaMarkup.embedKey(property, embeds++);
      reference(model, content, property, content.name() + " - " + property + " " + embeds, item)
              .ifPresent(reference -> {
                model.put(key, reference);
                parts.add(new FigmaMarkup.Embed(key));
              });
    }
    model.put(property, new FigmaMarkup(List.copyOf(parts)));
  }

  static boolean isImageRef(Value mapping) {
    List<String> segments = FigmaNodes.segments(mapping.path());
    return !segments.isEmpty() && IMAGE_REF.equals(segments.get(segments.size() - 1));
  }

  /**
   * Downloads the image here, so that a missing image skips the property instead of failing the whole import.
   */
  @Nullable
  private static ContentHubBlob imageFill(FigmaItem item, String property, String imageRef) {
    ContentHubBlob blob = item.getImageFill(imageRef);
    try {
      blob.getLength();
      return blob;
    } catch (ContentHubException e) {
      LOG.warn("Figma transform: cannot download image {} for property {}: {}", imageRef, property, e.getMessage());
      return null;
    }
  }

  private Optional<ContentModelReference> reference(ContentModel owner, Nested content, String property, String name,
                                                    Mapping mapping) {
    if (content.depth() + 1 > MAX_DEPTH) {
      LOG.warn("Figma transform: skipping property {} of {}, contents are nested deeper than {}", property,
              content.type(), MAX_DEPTH);
      return Optional.empty();
    }
    Nested nested = switch (mapping) {
      case NewContent newContent -> new Nested(content.item(), contentName(name), newContent.type(), newContent.viewtype(),
              newContent.properties(), content.root(), content.depth() + 1, content.descriptions());
      case Component component -> nestedComponent(content, property, name, component);
      default -> throw new IllegalArgumentException("Not a content mapping: " + mapping);
    };
    return Optional.ofNullable(nested).map(n -> ContentModelReference.create(owner, n.type(), n));
  }

  /**
   * A component pointing to another component: the <code>cm-type</code> of the layer names the description.
   */
  @Nullable
  private Nested nestedComponent(Nested content, String property, String name, Component component) {
    Optional<JsonNode> layer = FigmaNodes.findLayer(content.root(), component.layerId());
    if (layer.isEmpty()) {
      LOG.warn("Figma transform: no layer {} for property {} of {}", component.layerId(), property, content.type());
      return null;
    }
    Optional<FigmaTransformDescription> description = transformDescriptions.find(layer.get());
    if (description.isEmpty()) {
      LOG.warn("Figma transform: layer {} for property {} of {} has no {} with a transform description",
              component.layerId(), property, content.type(), FigmaTransformDescriptions.CM_TYPE_PROPERTY);
      return null;
    }
    String descriptionName = description.get().name();
    if (content.descriptions().contains(descriptionName)) {
      LOG.warn("Figma transform: skipping property {} of {}, transform description '{}' refers to itself",
              property, content.type(), descriptionName);
      return null;
    }
    Set<String> descriptions = new HashSet<>(content.descriptions());
    descriptions.add(descriptionName);
    return new Nested(content.item(), contentName(name), description.get().initialType(), description.get().viewtype(),
            description.get().properties(), layer.get(), content.depth() + 1, Set.copyOf(descriptions));
  }

  /**
   * Figma names often contain slashes (e.g. "Button/Primary"), which are not allowed in content names.
   */
  static String contentName(String name) {
    String result = name.replace('/', '-').replace('\\', '-').replaceAll("\\s+", " ").trim();
    while (result.startsWith(".")) {
      result = result.substring(1);
    }
    if (result.length() > MAX_NAME_LENGTH) {
      result = result.substring(0, MAX_NAME_LENGTH).trim();
    }
    return result.isEmpty() ? "Figma Frame" : result;
  }

  enum FigmaContentCreationErrorCode implements ContentHubContentCreationErrorCode {
    RENDERING_FAILED,
    INVALID_TRANSFORM_DESCRIPTION
  }
}

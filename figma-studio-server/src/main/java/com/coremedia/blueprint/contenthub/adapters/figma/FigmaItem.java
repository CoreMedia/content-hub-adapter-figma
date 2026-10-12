package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.contenthub.api.ContentHubBlob;
import com.coremedia.contenthub.api.Item;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * A Figma object that can be imported, i.e. a component used on a page.
 */
interface FigmaItem extends Item {

  String DATA_CLASSIFIER = "data";
  String IMAGE_FILL_CLASSIFIER = "imageFill";

  FigmaId getFigmaId();

  /**
   * Link to the object in the Figma web app.
   */
  String getFigmaUrl();

  /**
   * Value of a component property (e.g. <code>cType</code>) of the first component or instance
   * in the imported node, see {@link FigmaComponentProperties}.
   */
  Optional<String> getComponentProperty(String name);

  /**
   * The node JSON of the imported component, whose layers are mapped by {@link FigmaTransformDescription}s.
   */
  JsonNode getNode();

  /**
   * The original image of an image fill of a layer, by the <code>imageRef</code> of the fill.
   * The image is downloaded lazily.
   */
  ContentHubBlob getImageFill(String imageRef);
}

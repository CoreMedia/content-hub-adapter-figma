package com.coremedia.blueprint.contenthub.adapters.figma;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.List;

/**
 * Data of a component used on a Figma page, i.e. of an instance node, extracted from the page JSON.
 *
 * @param node     the instance node with all its descendants
 * @param siblings the components used on the same page, used to render thumbnails in batches
 */
record FigmaComponentData(String fileKey,
                          String fileName,
                          String pageId,
                          String pageName,
                          String nodeId,
                          String name,
                          double width,
                          double height,
                          @Nullable String lastModified,
                          JsonNode node,
                          List<Sibling> siblings) {

  record Sibling(String nodeId, double width) {
  }
}

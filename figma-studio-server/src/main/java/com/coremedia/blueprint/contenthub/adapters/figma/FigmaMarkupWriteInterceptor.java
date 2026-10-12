package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.rest.cap.intercept.ContentWriteInterceptorBase;
import com.coremedia.rest.cap.intercept.ContentWriteRequest;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * Converts the {@link FigmaMarkup} values of a content created by Content Hub to richtext, once the embedded contents
 * are created, and removes the keys of the embedded contents, which are no content properties.
 */
@DefaultAnnotation(NonNull.class)
class FigmaMarkupWriteInterceptor extends ContentWriteInterceptorBase {

  @Override
  public void intercept(ContentWriteRequest request) {
    Map<String, Object> properties = request.getProperties();
    if (properties == null) {
      return;
    }
    List<String> markups = properties.entrySet().stream()
            .filter(entry -> entry.getValue() instanceof FigmaMarkup)
            .map(Map.Entry::getKey)
            .toList();
    for (String property : markups) {
      properties.put(property, ((FigmaMarkup) properties.get(property)).toMarkup(properties));
    }
    properties.keySet().removeIf(key -> key.startsWith(FigmaMarkup.KEY_PREFIX));
  }
}

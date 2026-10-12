package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cap.common.Blob;
import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.common.CapPropertyDescriptorType;
import com.coremedia.cap.common.IdHelper;
import com.coremedia.cap.content.Content;
import com.coremedia.xml.Markup;
import com.coremedia.xml.MarkupFactory;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * The value of a markup property with embedded contents, see {@link FigmaTransformDescription.Richtext}.
 * <p>
 * Content Hub creates linked contents only for references that are property values, so the transformer puts each
 * embedded content as a reference under a key of its own ({@link #embedKey}). Content Hub creates them before the
 * owning content and replaces the references with the created contents. {@link FigmaMarkupWriteInterceptor} then
 * converts this value to richtext and removes the keys.
 */
@DefaultAnnotation(NonNull.class)
record FigmaMarkup(List<Part> parts) {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaMarkup.class);

  /**
   * The prefix of the keys of embedded contents, which are no content properties.
   */
  static final String KEY_PREFIX = "figma-markup:";
  private static final String RICHTEXT_START = "<div xmlns=\"http://www.coremedia.com/2003/richtext-1.0\""
          + " xmlns:xlink=\"http://www.w3.org/1999/xlink\">";
  private static final String RICHTEXT_END = "</div>";

  sealed interface Part {
  }

  /**
   * A text, written as one paragraph per line.
   */
  record Text(String text) implements Part {
  }

  /**
   * An embedded content, found under the key in the properties.
   */
  record Embed(String key) implements Part {
  }

  static String embedKey(String property, int index) {
    return KEY_PREFIX + property + ":" + index;
  }

  /**
   * Converts the parts to richtext: pictures are embedded as images, other contents as links. Embedded contents that
   * were not created are skipped.
   *
   * @param properties the properties of the content, with the embedded contents under the keys of the embeds
   */
  Markup toMarkup(Map<String, Object> properties) {
    StringBuilder richtext = new StringBuilder(RICHTEXT_START);
    for (Part part : parts) {
      switch (part) {
        case Text text -> text.text().lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .forEach(line -> richtext.append("<p>").append(escape(line)).append("</p>"));
        case Embed embed -> {
          if (properties.get(embed.key()) instanceof Content content) {
            richtext.append("<p>").append(embedded(content)).append("</p>");
          } else {
            LOG.warn("Figma transform: embedded content {} was not created, skipping it", embed.key());
          }
        }
      }
    }
    return MarkupFactory.fromString(richtext.append(RICHTEXT_END).toString());
  }

  private static String embedded(Content content) {
    String name = escape(content.getName());
    if (isPicture(content)) {
      return "<img alt=\"" + name + "\" xlink:href=\"coremedia:///cap/blob/content/"
              + IdHelper.parseContentId(content.getId()) + "#" + FigmaTransformDescription.PICTURE_DATA + "\"/>";
    }
    return "<a xlink:href=\"" + escape(content.getId()) + "\">" + name + "</a>";
  }

  private static boolean isPicture(Content content) {
    CapPropertyDescriptor data = content.getType().getDescriptor(FigmaTransformDescription.PICTURE_DATA);
    if (data == null || data.getType() != CapPropertyDescriptorType.BLOB) {
      return false;
    }
    Blob blob = content.getBlob(FigmaTransformDescription.PICTURE_DATA);
    return blob != null && "image".equals(blob.getContentType().getPrimaryType());
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}

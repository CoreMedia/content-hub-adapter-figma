package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cap.common.CapConnection;
import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.content.ContentType;
import com.coremedia.cap.multisite.Site;
import com.coremedia.cap.struct.Struct;
import com.coremedia.cap.struct.StructBuilder;
import com.coremedia.contenthub.api.ContentHubObjectId;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backend of the Figma mapping editor in Studio, which edits the transform descriptions
 * ({@link FigmaTransformDescription}) graphically:
 * <ul>
 *   <li><code>GET figma/component?id=&lt;contentHubObjectId&gt;</code>: the node JSON of a Figma component</li>
 *   <li><code>GET figma/contenttypes</code>: the content types and their mappable properties</li>
 *   <li><code>GET figma/viewtypes?type=&lt;contentType&gt;[&amp;site=&lt;siteId&gt;]</code>: the viewtypes applicable to
 *   a content type, see {@link FigmaViewtypes}</li>
 *   <li><code>GET figma/descriptions</code>: the names of the transform descriptions</li>
 *   <li><code>GET figma/descriptions/&lt;name&gt;</code>: a transform description</li>
 *   <li><code>PUT figma/descriptions/&lt;name&gt;</code>: creates or updates a transform description</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "figma", produces = MediaType.APPLICATION_JSON_VALUE)
@DefaultAnnotation(NonNull.class)
public class FigmaMappingResource {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaMappingResource.class);

  private static final String SETTINGS_TYPE = "CMSettings";
  private static final String SETTINGS_PROPERTY = "settings";
  /**
   * Converts the Jackson 2 node JSON into plain maps and lists, since the Spring MVC of Studio serializes
   * with Jackson 3, which would serialize a Jackson 2 <code>JsonNode</code> as a bean.
   */
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> MAPPABLE_TYPES = Set.of("STRING", "MARKUP", "INTEGER", "LONG", "BOOLEAN", "BLOB", "LINK");

  private final FigmaContentHubAdapterFactory adapterFactory;
  private final FigmaViewtypes viewtypes;
  private final CapConnection capConnection;

  public FigmaMappingResource(FigmaContentHubAdapterFactory adapterFactory, FigmaViewtypes viewtypes,
                              CapConnection capConnection) {
    this.adapterFactory = adapterFactory;
    this.viewtypes = viewtypes;
    this.capConnection = capConnection;
  }

  @GetMapping("component")
  public Map<String, Object> component(@RequestParam("id") String id) {
    ContentHubObjectId hubId;
    try {
      hubId = ContentHubObjectId.fromString(id);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Content Hub id " + id, e);
    }
    FigmaContentHubAdapter adapter = adapterFactory.adapter(hubId.getConnectionId()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No Figma connection " + hubId.getConnectionId()));
    FigmaItem item = adapter.item(hubId.getExternalId()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No Figma component " + hubId.getExternalId()));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("name", item.getName());
    result.put("cmType", item.getComponentProperty(FigmaTransformDescriptions.CM_TYPE_PROPERTY).orElse(null));
    result.put("node", JSON.convertValue(item.getNode(), Object.class));
    return result;
  }

  @GetMapping("contenttypes")
  public List<Map<String, Object>> contentTypes() {
    List<Map<String, Object>> result = new ArrayList<>();
    capConnection.getContentRepository().getContentTypes().stream()
            .filter(type -> !type.isAbstract() && !"Document_".equals(type.getName()) && !"Folder_".equals(type.getName()))
            .sorted(Comparator.comparing(ContentType::getName))
            .forEach(type -> {
              List<Map<String, Object>> properties = new ArrayList<>();
              for (CapPropertyDescriptor descriptor : type.getDescriptors()) {
                String propertyType = descriptor.getType().name();
                if (MAPPABLE_TYPES.contains(propertyType)) {
                  properties.add(Map.of("name", descriptor.getName(), "type", propertyType));
                }
              }
              properties.sort(Comparator.comparing(property -> (String) property.get("name")));
              result.add(Map.of("name", type.getName(), "properties", properties,
                      "viewtype", viewtypes.hasViewtype(type.getName())));
            });
    return result;
  }

  /**
   * The viewtypes applicable to the content type, by their key. A viewtype of the site hides a global one with the
   * same key, since the transform description refers to the key only.
   */
  @GetMapping("viewtypes")
  public List<Map<String, Object>> viewtypes(@RequestParam("type") String type,
                                             @RequestParam(value = "site", required = false) @Nullable String siteId) {
    Site site = viewtypes.site(siteId);
    Set<String> keys = new HashSet<>();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Content viewtype : viewtypes.applicable(type, site)) {
      String key = FigmaViewtypes.key(viewtype);
      if (keys.add(key)) {
        boolean inSite = site != null && site.getSiteRootFolder() != null
                && viewtype.getPath().startsWith(site.getSiteRootFolder().getPath() + "/");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("name", viewtype.getName());
        entry.put("path", viewtype.getPath());
        entry.put("site", inSite ? site.getName() : null);
        result.add(entry);
      }
    }
    return result;
  }

  @GetMapping("descriptions")
  public List<String> descriptions() {
    Content folder = capConnection.getContentRepository().getChild(FigmaTransformDescriptions.FOLDER);
    if (folder == null) {
      return List.of();
    }
    return folder.getChildDocuments().stream()
            .filter(content -> content.getType().isSubtypeOf(SETTINGS_TYPE))
            .map(Content::getName)
            .sorted()
            .toList();
  }

  @GetMapping("descriptions/{name}")
  public Map<String, Object> description(@PathVariable("name") String name) {
    Content content = settings(name);
    if (content == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No Figma transform description " + name);
    }
    Struct struct = content.getStruct(SETTINGS_PROPERTY);
    return Map.of("name", name, "settings", struct == null ? Map.of() : struct.toNestedMaps());
  }

  @PutMapping(value = "descriptions/{name}", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> saveDescription(@PathVariable("name") String name, @RequestBody Map<String, Object> settings) {
    if (!FigmaTransformDescriptions.isValidName(name)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name of a Figma transform description: " + name);
    }
    try {
      // same validation as on import
      FigmaTransformDescription.parse(name, settings);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
    Struct struct = struct(settings);
    Content content = settings(name);
    if (content == null) {
      ContentRepository repository = capConnection.getContentRepository();
      content = repository.createChild(FigmaTransformDescriptions.FOLDER + "/" + name, SETTINGS_TYPE,
              Map.of(SETTINGS_PROPERTY, struct));
      LOG.info("Created Figma transform description {}", content.getPath());
    } else {
      if (content.isCheckedOut() && !content.isCheckedOutByCurrentSession()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, content.getPath() + " is checked out by another user");
      }
      if (!content.isCheckedOut()) {
        content.checkOut();
      }
      content.set(SETTINGS_PROPERTY, struct);
      LOG.info("Updated Figma transform description {}", content.getPath());
    }
    if (content.isCheckedOut()) {
      content.checkIn();
    }
    return Map.of("name", name, "id", content.getId());
  }

  @Nullable
  private Content settings(String name) {
    if (!FigmaTransformDescriptions.isValidName(name)) {
      return null;
    }
    Content content = capConnection.getContentRepository().getChild(FigmaTransformDescriptions.FOLDER + "/" + name);
    return content != null && !content.isDestroyed() && content.getType().isSubtypeOf(SETTINGS_TYPE) ? content : null;
  }

  /**
   * Converts the nested maps of the editor into a struct: strings, structs and lists of structs.
   */
  @SuppressWarnings("unchecked")
  private Struct struct(Map<String, Object> map) {
    StructBuilder builder = capConnection.getStructService().createStructBuilder();
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      if (value instanceof String string) {
        builder.declareString(key, Integer.MAX_VALUE, string);
      } else if (value instanceof Map<?, ?> nested) {
        builder.declareStruct(key, struct((Map<String, Object>) nested));
      } else if (value instanceof List<?> list) {
        List<Struct> structs = new ArrayList<>();
        for (Object element : list) {
          if (!(element instanceof Map<?, ?> nested)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only lists of structs are supported: " + key);
          }
          structs.add(struct((Map<String, Object>) nested));
        }
        builder.declareStructs(key, structs);
      } else if (value != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported value of " + key + ": " + value);
      }
    }
    return builder.build();
  }
}

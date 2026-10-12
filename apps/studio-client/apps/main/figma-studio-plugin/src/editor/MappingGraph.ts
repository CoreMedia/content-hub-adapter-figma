/**
 * A property of a content type, as delivered by <code>figma/contenttypes</code>.
 */
export interface PropertyInfo {
  name: string;
  /** STRING, MARKUP, INTEGER, LONG, BOOLEAN, BLOB or LINK */
  type: string;
}

export interface ContentTypeInfo {
  name: string;
  properties: Array<PropertyInfo>;
  /** whether the type has a viewtype, which is selected instead of being linked */
  viewtype?: boolean;
}

/**
 * A viewtype applicable to a content type, as delivered by <code>figma/viewtypes</code>.
 */
export interface ViewtypeInfo {
  /** the layout of the viewtype (or its name), which the transform description refers to */
  key: string;
  name: string;
  path: string;
  /** the name of the site of a site-specific viewtype, or null for a global one */
  site: string;
}

/**
 * An output port of the Figma source node: a value of a layer ("value") or a layer which is an
 * instance of another component ("component").
 */
export interface SourcePort {
  id: string;
  kind: string;
  layerId: string;
  path: string;
  label: string;
  /** the type, name and id of the layer, shown as tooltip and matched by the filter */
  hint?: string;
  /** number of enclosing instances and components, for indentation */
  depth: number;
  /** layer ids of the enclosing instances and components, the port is shown if they are expanded */
  ancestors?: Array<string>;
  /** for the port of an instance or component: its layer id, which can be expanded */
  group?: string;
}

export interface GraphNode {
  id: string;
  /** "source" (the Figma component), "content" (a content to create) or "markup" (the richtext of a markup property) */
  kind: string;
  x: number;
  y: number;
  contentType?: string;
  /** the key of the viewtype of the content, see ViewtypeInfo */
  viewtype?: string;
  /** the content created for the component itself, i.e. the initialType */
  root?: boolean;
  /** for a markup node: the ids of its items in their order, each is the target property of one link */
  slots?: Array<string>;
}

/**
 * A link from an output port (a source port or the "content" port of a content node)
 * to a property of a content node.
 */
export interface GraphLink {
  fromNode: string;
  fromPort: string;
  toNode: string;
  toProperty: string;
}

/**
 * The model of the Figma mapping editor and its conversion from and to the <code>settings</code> struct of a
 * transform description (see FigmaTransformDescription.java):
 * <pre>
 * initialType: "CMTeaser"
 * viewtype:    "hero"                                    optional, the layout of a viewtype
 * properties:
 *   title:    "1:4->characters"                          value of a layer
 *   pictures: {type: "CMPicture", viewtype: "portrait", properties: {...}}   a new linked content
 *   related:  [{type: ...}, {layer: "1:9"}]              several linked contents
 *   target:   {layer: "1:9"}                             the layer's cm-type names the description
 *   detailText: {markup: [{value: "1:4->characters"}, {value: "1:3->fills[0]->imageRef"}, {type: ...}, {layer: ...}]}
 *                                                        richtext with texts, images and contents in their order
 * </pre>
 */
class MappingGraph {
  static readonly SOURCE_NODE_ID: string = "source";
  static readonly CONTENT_PORT: string = "content";
  static readonly VALUE: string = "value";
  static readonly COMPONENT: string = "component";
  static readonly LINK: string = "LINK";
  static readonly BLOB: string = "BLOB";
  static readonly IMAGE_REF: string = "imageRef";
  static readonly VIEWTYPE: string = "viewtype";
  static readonly MARKUP: string = "MARKUP";
  static readonly MARKUP_NODE: string = "markup";
  /** the output port of a markup node */
  static readonly MARKUP_PORT: string = "markup";
  /** the target property of a link that appends an item to a markup node */
  static readonly NEW_SLOT: string = "slot:new";

  nodes: Array<GraphNode> = [];
  links: Array<GraphLink> = [];
  sourcePorts: Array<SourcePort> = [];
  /** name of the loaded Figma component, for display */
  componentName: string = null;
  contentTypes: Array<ContentTypeInfo> = [];

  #nextId: number = 1;

  constructor() {
    this.nodes.push({ id: MappingGraph.SOURCE_NODE_ID, kind: "source", x: 20, y: 20 });
  }

  // --- nodes and ports ---------------------------------------------

  getNode(id: string): GraphNode {
    return this.nodes.filter((node: GraphNode): boolean => node.id === id)[0] || null;
  }

  getRoot(): GraphNode {
    return this.nodes.filter((node: GraphNode): boolean => !!node.root)[0] || null;
  }

  addContentNode(contentType: string, x: number, y: number): GraphNode {
    const node: GraphNode = { id: "n" + this.#nextId++, kind: "content", x: x, y: y, contentType: contentType };
    if (!this.getRoot()) {
      node.root = true;
    }
    this.nodes.push(node);
    return node;
  }

  addMarkupNode(x: number, y: number): GraphNode {
    const node: GraphNode = { id: "n" + this.#nextId++, kind: MappingGraph.MARKUP_NODE, x: x, y: y, slots: [] };
    this.nodes.push(node);
    return node;
  }

  /**
   * Moves an item of a markup node up (-1) or down (1).
   */
  moveSlot(nodeId: string, slot: string, delta: number): void {
    const slots = this.getNode(nodeId).slots;
    const index = slots.indexOf(slot);
    const target = index + delta;
    if (index < 0 || target < 0 || target >= slots.length) {
      return;
    }
    slots.splice(index, 1);
    slots.splice(target, 0, slot);
  }

  /**
   * The link to an item of a markup node.
   */
  getSlotLink(nodeId: string, slot: string): GraphLink {
    return (
      this.links.filter((link: GraphLink): boolean => link.toNode === nodeId && link.toProperty === slot)[0] || null
    );
  }

  #newSlot(node: GraphNode): string {
    const slot = "slot:" + this.#nextId++;
    node.slots.push(slot);
    return slot;
  }

  /**
   * Removes the items of markup nodes whose link was removed.
   */
  #pruneSlots(): void {
    this.nodes
      .filter((node: GraphNode): boolean => node.kind === MappingGraph.MARKUP_NODE)
      .forEach((node: GraphNode): void => {
        node.slots = node.slots.filter((slot: string): boolean => !!this.getSlotLink(node.id, slot));
      });
  }

  removeNode(id: string): void {
    if (id === MappingGraph.SOURCE_NODE_ID) {
      return;
    }
    const wasRoot = !!(this.getNode(id) && this.getNode(id).root);
    this.nodes = this.nodes.filter((node: GraphNode): boolean => node.id !== id);
    this.links = this.links.filter((link: GraphLink): boolean => link.fromNode !== id && link.toNode !== id);
    this.#pruneSlots();
    if (wasRoot) {
      const next = this.nodes.filter((node: GraphNode): boolean => node.kind === "content")[0];
      if (next) {
        next.root = true;
      }
    }
  }

  setRoot(id: string): void {
    this.nodes.forEach((node: GraphNode): void => {
      if (node.kind === "content") {
        node.root = node.id === id;
      }
    });
    // the root is never linked from another content
    this.links = this.links.filter((link: GraphLink): boolean => link.fromNode !== id);
    this.#pruneSlots();
  }

  /**
   * Changes the content type and drops the viewtype and the links to properties the new type does not have.
   */
  setContentType(id: string, contentType: string): void {
    const node = this.getNode(id);
    node.contentType = contentType;
    // viewtypes are applicable to a content type
    delete node.viewtype;
    this.links = this.links.filter((link: GraphLink): boolean => {
      if (link.toNode !== id) {
        return true;
      }
      const property = this.getProperty(contentType, link.toProperty);
      return !!property && (!this.#isMarkupNode(link.fromNode) || property.type === MappingGraph.MARKUP);
    });
  }

  #isMarkupNode(id: string): boolean {
    const node = this.getNode(id);
    return !!node && node.kind === MappingGraph.MARKUP_NODE;
  }

  getContentType(name: string): ContentTypeInfo {
    return this.contentTypes.filter((type: ContentTypeInfo): boolean => type.name === name)[0] || null;
  }

  setViewtype(id: string, viewtype: string): void {
    const node = this.getNode(id);
    if (viewtype) {
      node.viewtype = viewtype;
    } else {
      delete node.viewtype;
    }
  }

  /**
   * The properties that can be linked: all but the viewtype, which is selected instead.
   */
  getProperties(contentType: string): Array<PropertyInfo> {
    const type = this.getContentType(contentType);
    if (!type) {
      return [];
    }
    return type.properties.filter(
      (info: PropertyInfo): boolean => !(type.viewtype && info.name === MappingGraph.VIEWTYPE),
    );
  }

  getProperty(contentType: string, property: string): PropertyInfo {
    return this.getProperties(contentType).filter((info: PropertyInfo): boolean => info.name === property)[0] || null;
  }

  getSourcePort(id: string): SourcePort {
    return this.sourcePorts.filter((port: SourcePort): boolean => port.id === id)[0] || null;
  }

  static valuePortId(layerId: string, path: string): string {
    return layerId + "->" + path;
  }

  static componentPortId(layerId: string): string {
    return "layer:" + layerId;
  }

  /**
   * Adds a value port for a path that is not derived from the Figma JSON, e.g. entered by the user
   * or used by a loaded description.
   */
  addValuePort(layerId: string, path: string): SourcePort {
    const id = MappingGraph.valuePortId(layerId, path);
    let port = this.getSourcePort(id);
    if (!port) {
      port = {
        id: id,
        kind: MappingGraph.VALUE,
        layerId: layerId,
        path: path,
        label: layerId + " → " + path,
        depth: 0,
      };
      this.sourcePorts.push(port);
    }
    return port;
  }

  addComponentPort(layerId: string): SourcePort {
    const id = MappingGraph.componentPortId(layerId);
    let port = this.getSourcePort(id);
    if (!port) {
      port = { id: id, kind: MappingGraph.COMPONENT, layerId: layerId, path: null, label: "⧉ " + layerId, depth: 0 };
      this.sourcePorts.push(port);
    }
    return port;
  }

  /**
   * Derives the source ports from the node JSON of a Figma component: the characters of text layers, the imageRefs of
   * image, GIF and video fills, the values of component properties, and the instances of other components.
   */
  setFigmaNode(node: any, componentName: string): void {
    const ports: Array<SourcePort> = [];
    // the children of an imported instance are prefixed with its id, e.g. I13:8;1:4
    const rootPrefix = node && node.type === "INSTANCE" ? "I" + node.id + ";" : null;
    MappingGraph.#collectPorts(node, ports, true, [], rootPrefix);
    // keep ports of the current mapping which are not part of the JSON
    const linked = this.sourcePorts.filter(
      (port: SourcePort): boolean =>
        this.links.some((link: GraphLink): boolean => link.fromPort === port.id) &&
        !ports.some((other: SourcePort): boolean => other.id === port.id),
    );
    this.sourcePorts = ports.concat(linked);
    this.componentName = componentName;
  }

  /**
   * The id of a layer as used in transform descriptions: the id in the component definition, so that the mapping
   * applies to all instances of the component. <code>I13:8;1:4</code> (in the imported instance 13:8) becomes
   * <code>1:4</code>, <code>I5:9;3:1</code> (in the nested instance 5:9) becomes <code>5:9;3:1</code>.
   */
  static canonicalLayerId(id: string, rootPrefix: string): string {
    if (rootPrefix && id.indexOf(rootPrefix) === 0) {
      return id.substring(rootPrefix.length);
    }
    return id.charAt(0) === "I" ? id.substring(1) : id;
  }

  static #collectPorts(
    node: any,
    ports: Array<SourcePort>,
    isRoot: boolean,
    ancestors: Array<string>,
    rootPrefix: string,
  ): void {
    if (!node || node.visible === false) {
      return;
    }
    // an imported instance is addressed by its component, so that the mapping applies to all instances
    const id: string = isRoot
      ? node.type === "INSTANCE" && node.componentId
        ? node.componentId
        : node.id
      : MappingGraph.canonicalLayerId(node.id, rootPrefix);
    const name: string = node.name || id;
    const title = "[" + (node.type || "?") + "] " + name + " (" + id + ")";
    const isComponent = node.type === "INSTANCE" || node.type === "COMPONENT" || node.type === "COMPONENT_SET";
    let inner = ancestors;
    if (!isRoot && isComponent) {
      ports.push({
        id: MappingGraph.componentPortId(id),
        kind: MappingGraph.COMPONENT,
        layerId: id,
        path: null,
        label: "⧉ " + title,
        depth: ancestors.length,
        ancestors: ancestors,
        group: id,
      });
      // the layers of a nested instance or component can be expanded
      inner = ancestors.concat([id]);
    }
    // only the values worth mapping are offered, structural layers (frames, groups, vectors, ...) are skipped,
    // so the layers are indented by the enclosing instances only
    const add = (path: string, label: string): void => {
      ports.push({
        id: MappingGraph.valuePortId(id, path),
        kind: MappingGraph.VALUE,
        layerId: id,
        path: path,
        label: label,
        hint: title,
        depth: inner.length,
        ancestors: inner,
      });
    };
    if (typeof node.characters === "string") {
      const text = node.characters.replace(/\s+/g, " ").trim();
      if (text) {
        add("characters", 'Text "' + MappingGraph.#shorten(text) + '"');
      }
    }
    const fills: Array<any> = node.fills || [];
    for (let i = 0; i < fills.length; i++) {
      const fill = fills[i];
      if (!fill || fill.visible === false) {
        continue;
      }
      const kind = fill.type === "VIDEO" ? "Video" : fill.gifRef ? "GIF" : "Image";
      if (fill.imageRef) {
        add("fills[" + i + "]->" + MappingGraph.IMAGE_REF, kind + ": " + name);
      } else if (fill.videoRef) {
        add("fills[" + i + "]->videoRef", "Video: " + name);
      }
    }
    // values of component properties, e.g. texts or variants set on an instance
    MappingGraph.#addComponentProperties(node.componentProperties, "value", name, add);
    MappingGraph.#addComponentProperties(node.componentPropertyDefinitions, "defaultValue", name, add);
    const children: Array<any> = node.children || [];
    for (let i = 0; i < children.length; i++) {
      MappingGraph.#collectPorts(children[i], ports, false, inner, rootPrefix);
    }
  }

  static #addComponentProperties(
    properties: any,
    valueField: string,
    layerName: string,
    add: (path: string, label: string) => void,
  ): void {
    if (!properties) {
      return;
    }
    const field = valueField === "value" ? "componentProperties" : "componentPropertyDefinitions";
    Object.keys(properties).forEach((key: string): void => {
      const property = properties[key];
      // keys with path separators cannot be addressed by a path
      if (!property || key.indexOf(".") >= 0 || key.indexOf("[") >= 0 || key.indexOf("->") >= 0) {
        return;
      }
      const value = property[valueField];
      if (value === undefined || value === null || typeof value === "object") {
        return;
      }
      // Figma appends a unique suffix to the names of text, boolean and instance swap properties
      const label = key.split("#")[0];
      add(
        field + "." + key + "." + valueField,
        layerName + " ⚙ " + label + " = " + MappingGraph.#shorten(String(value)),
      );
    });
  }

  /**
   * Whether the port of an instance or component has ports within its group.
   */
  hasGroupContent(group: string): boolean {
    return this.sourcePorts.some((port: SourcePort): boolean => !!port.ancestors && port.ancestors.indexOf(group) >= 0);
  }

  static #shorten(text: string): string {
    return text.length > 30 ? text.substring(0, 30) + "…" : text;
  }

  // --- links -------------------------------------------------------

  /**
   * Returns an error message if the link is not allowed, or null.
   */
  checkLink(fromNode: string, fromPort: string, toNode: string, toProperty: string): string {
    const target = this.getNode(toNode);
    if (target && target.kind === MappingGraph.MARKUP_NODE) {
      return this.#checkMarkupItem(fromNode, fromPort, toNode);
    }
    if (!target || target.kind !== "content") {
      return "Links end at a property of a content node.";
    }
    const property = this.getProperty(target.contentType, toProperty);
    if (!property) {
      return "Unknown property " + toProperty;
    }
    if (fromNode === MappingGraph.SOURCE_NODE_ID) {
      const port = this.getSourcePort(fromPort);
      if (!port) {
        return "Unknown Figma port";
      }
      if (port.kind === MappingGraph.COMPONENT) {
        return property.type === MappingGraph.LINK ? null : "A component can only be linked to a link property.";
      }
      if (property.type === MappingGraph.LINK) {
        return "A Figma value cannot be linked to a link property, link a content node instead.";
      }
      if (property.type === MappingGraph.BLOB && !MappingGraph.isImageRef(port.path)) {
        return "Only an imageRef can be mapped to a blob property.";
      }
      return null;
    }
    if (this.#isMarkupNode(fromNode)) {
      if (property.type !== MappingGraph.MARKUP) {
        return "A richtext can only be linked to a markup property.";
      }
      return this.#reaches(fromNode, toNode) ? "This link would create a cycle." : null;
    }
    if (property.type !== MappingGraph.LINK) {
      return "A content can only be linked to a link property.";
    }
    if (fromNode === toNode) {
      return "A content cannot link itself.";
    }
    const source = this.getNode(fromNode);
    if (source && source.root) {
      return "The initial content cannot be linked from another content.";
    }
    if (this.#reaches(fromNode, toNode)) {
      return "This link would create a cycle.";
    }
    return null;
  }

  /**
   * Items of a markup node are texts and images of the Figma node, components, and contents.
   */
  #checkMarkupItem(fromNode: string, fromPort: string, toNode: string): string {
    if (fromNode === MappingGraph.SOURCE_NODE_ID) {
      return this.getSourcePort(fromPort) ? null : "Unknown Figma port";
    }
    if (this.#isMarkupNode(fromNode)) {
      return "A richtext cannot be embedded in a richtext, link it to a markup property.";
    }
    const source = this.getNode(fromNode);
    if (source && source.root) {
      return "The initial content cannot be embedded in a richtext.";
    }
    if (this.#reaches(fromNode, toNode)) {
      return "This link would create a cycle.";
    }
    return null;
  }

  /**
   * Adds a link. Values replace an existing link to the same property; link properties may have several links.
   */
  addLink(fromNode: string, fromPort: string, toNode: string, toProperty: string): string {
    const error = this.checkLink(fromNode, fromPort, toNode, toProperty);
    if (error) {
      return error;
    }
    const target = this.getNode(toNode);
    if (target.kind === MappingGraph.MARKUP_NODE) {
      return this.#addMarkupItem(fromNode, fromPort, target, toProperty);
    }
    const property = this.getProperty(target.contentType, toProperty);
    this.links = this.links.filter((link: GraphLink): boolean => {
      const sameProperty = link.toNode === toNode && link.toProperty === toProperty;
      if (!sameProperty) {
        return true;
      }
      // a content is linked from one property only, a value property has one value
      return property.type === MappingGraph.LINK && !(link.fromNode === fromNode && link.fromPort === fromPort);
    });
    if (fromNode !== MappingGraph.SOURCE_NODE_ID) {
      this.links = this.links.filter((link: GraphLink): boolean => link.fromNode !== fromNode);
    }
    this.links.push({ fromNode: fromNode, fromPort: fromPort, toNode: toNode, toProperty: toProperty });
    this.#pruneSlots();
    return null;
  }

  /**
   * Links an item into a slot of the markup node, or appends it for {@link NEW_SLOT}. The same Figma value may be
   * used several times, a content is embedded once.
   */
  #addMarkupItem(fromNode: string, fromPort: string, target: GraphNode, slot: string): string {
    if (fromNode !== MappingGraph.SOURCE_NODE_ID) {
      this.links = this.links.filter((link: GraphLink): boolean => link.fromNode !== fromNode);
    }
    if (slot === MappingGraph.NEW_SLOT || target.slots.indexOf(slot) < 0) {
      slot = this.#newSlot(target);
    }
    this.links = this.links.filter(
      (link: GraphLink): boolean => !(link.toNode === target.id && link.toProperty === slot),
    );
    this.links.push({ fromNode: fromNode, fromPort: fromPort, toNode: target.id, toProperty: slot });
    this.#pruneSlots();
    return null;
  }

  removeLink(link: GraphLink): void {
    this.links = this.links.filter((other: GraphLink): boolean => other !== link);
    this.#pruneSlots();
  }

  /**
   * Whether <code>to</code> reaches <code>from</code> through content links, i.e. from -&gt; to would close a cycle.
   */
  #reaches(from: string, to: string): boolean {
    const visited: Record<string, boolean> = {};
    const queue: Array<string> = [to];
    while (queue.length > 0) {
      const current = queue.shift();
      if (current === from) {
        return true;
      }
      if (visited[current]) {
        continue;
      }
      visited[current] = true;
      // parents of the current node, i.e. nodes linking the current one
      this.links
        .filter((link: GraphLink): boolean => link.fromNode === current)
        .forEach((link: GraphLink): void => {
          queue.push(link.toNode);
        });
    }
    return false;
  }

  static isImageRef(path: string): boolean {
    if (!path) {
      return false;
    }
    const segments = path.split(/->|\.|\[|\]/).filter((segment: string): boolean => segment.trim().length > 0);
    return segments.length > 0 && segments[segments.length - 1].trim() === MappingGraph.IMAGE_REF;
  }

  // --- conversion to the settings struct ----------------------------

  /**
   * Converts the graph into the settings struct. Content nodes which are not reachable from the root are ignored.
   *
   * @throws Error if there is no initial content
   */
  toSettings(): any {
    const root = this.getRoot();
    if (!root) {
      throw new Error("Add a content node for the initial content first.");
    }
    const settings: any = { initialType: root.contentType };
    if (root.viewtype) {
      settings.viewtype = root.viewtype;
    }
    settings.properties = this.#properties(root);
    return settings;
  }

  /**
   * Content nodes which are neither the root nor linked, so that the editor can warn about them.
   */
  getUnlinkedNodes(): Array<GraphNode> {
    return this.nodes.filter(
      (node: GraphNode): boolean =>
        (node.kind === "content" || node.kind === MappingGraph.MARKUP_NODE) &&
        !node.root &&
        !this.links.some((link: GraphLink): boolean => link.fromNode === node.id),
    );
  }

  #properties(node: GraphNode): any {
    const result: any = {};
    this.getProperties(node.contentType).forEach((property: PropertyInfo): void => {
      const links = this.links.filter(
        (link: GraphLink): boolean => link.toNode === node.id && link.toProperty === property.name,
      );
      if (links.length === 0) {
        return;
      }
      if (property.type !== MappingGraph.LINK) {
        const last = links[links.length - 1];
        if (this.#isMarkupNode(last.fromNode)) {
          result[property.name] = { markup: this.#markupItems(this.getNode(last.fromNode)) };
          return;
        }
        const port = this.getSourcePort(last.fromPort);
        if (port) {
          result[property.name] = MappingGraph.valuePortId(port.layerId, port.path);
        }
        return;
      }
      const contents: Array<any> = [];
      links.forEach((link: GraphLink): void => {
        if (link.fromNode === MappingGraph.SOURCE_NODE_ID) {
          const port = this.getSourcePort(link.fromPort);
          if (port) {
            contents.push({ layer: port.layerId });
          }
        } else {
          const child = this.getNode(link.fromNode);
          const content: any = { type: child.contentType };
          if (child.viewtype) {
            content.viewtype = child.viewtype;
          }
          content.properties = this.#properties(child);
          contents.push(content);
        }
      });
      result[property.name] = contents.length === 1 ? contents[0] : contents;
    });
    return result;
  }

  #markupItems(node: GraphNode): Array<any> {
    const items: Array<any> = [];
    node.slots.forEach((slot: string): void => {
      const link = this.getSlotLink(node.id, slot);
      if (!link) {
        return;
      }
      if (link.fromNode === MappingGraph.SOURCE_NODE_ID) {
        const port = this.getSourcePort(link.fromPort);
        if (port) {
          items.push(
            port.kind === MappingGraph.COMPONENT
              ? { layer: port.layerId }
              : { value: MappingGraph.valuePortId(port.layerId, port.path) },
          );
        }
        return;
      }
      const child = this.getNode(link.fromNode);
      const content: any = { type: child.contentType };
      if (child.viewtype) {
        content.viewtype = child.viewtype;
      }
      content.properties = this.#properties(child);
      items.push(content);
    });
    return items;
  }

  // --- conversion from the settings struct --------------------------

  /**
   * Replaces the content nodes by the ones of the settings struct and lays them out.
   */
  fromSettings(settings: any): void {
    this.nodes = this.nodes.filter((node: GraphNode): boolean => node.kind === "source");
    this.links = [];
    if (!settings || !settings.initialType) {
      return;
    }
    const root = this.addContentNode(settings.initialType, 0, 0);
    this.setRoot(root.id);
    this.setViewtype(root.id, settings.viewtype);
    this.#addProperties(root, settings.properties || {});
    this.layout();
  }

  #addProperties(node: GraphNode, properties: any): void {
    Object.keys(properties).forEach((name: string): void => {
      const value = properties[name];
      if (typeof value === "string") {
        const separator = value.indexOf("->");
        if (separator > 0) {
          const port = this.addValuePort(value.substring(0, separator).trim(), value.substring(separator + 2).trim());
          this.links.push({
            fromNode: MappingGraph.SOURCE_NODE_ID,
            fromPort: port.id,
            toNode: node.id,
            toProperty: name,
          });
        }
      } else if (value && Array.isArray(value.markup)) {
        this.#addMarkup(node, name, value.markup);
      } else if (Array.isArray(value)) {
        value.forEach((content: any): void => {
          this.#addContent(node, name, content);
        });
      } else if (value && typeof value === "object") {
        this.#addContent(node, name, value);
      }
    });
  }

  #addMarkup(parent: GraphNode, property: string, items: Array<any>): void {
    const markup = this.addMarkupNode(0, 0);
    this.links.push({
      fromNode: markup.id,
      fromPort: MappingGraph.MARKUP_PORT,
      toNode: parent.id,
      toProperty: property,
    });
    items.forEach((item: any): void => {
      if (!item) {
        return;
      }
      if (typeof item.value === "string") {
        const separator = item.value.indexOf("->");
        if (separator > 0) {
          const port = this.addValuePort(
            item.value.substring(0, separator).trim(),
            item.value.substring(separator + 2).trim(),
          );
          this.links.push({
            fromNode: MappingGraph.SOURCE_NODE_ID,
            fromPort: port.id,
            toNode: markup.id,
            toProperty: this.#newSlot(markup),
          });
        }
      } else {
        this.#addContent(markup, this.#newSlot(markup), item);
      }
    });
    this.#pruneSlots();
  }

  #addContent(parent: GraphNode, property: string, content: any): void {
    if (content.layer) {
      const port = this.addComponentPort(content.layer);
      this.links.push({
        fromNode: MappingGraph.SOURCE_NODE_ID,
        fromPort: port.id,
        toNode: parent.id,
        toProperty: property,
      });
    } else if (content.type) {
      const child = this.addContentNode(content.type, 0, 0);
      this.setViewtype(child.id, content.viewtype);
      this.links.push({
        fromNode: child.id,
        fromPort: MappingGraph.CONTENT_PORT,
        toNode: parent.id,
        toProperty: property,
      });
      this.#addProperties(child, content.properties || {});
    }
  }

  /**
   * Lays out the content nodes in columns by their distance to the root: the root next to the Figma node,
   * linked contents further right.
   */
  layout(): void {
    const columns: Record<string, number> = {};
    const root = this.getRoot();
    if (root) {
      const queue: Array<GraphNode> = [root];
      columns[root.id] = 0;
      while (queue.length > 0) {
        const current = queue.shift();
        this.links
          .filter(
            (link: GraphLink): boolean => link.toNode === current.id && link.fromNode !== MappingGraph.SOURCE_NODE_ID,
          )
          .forEach((link: GraphLink): void => {
            if (columns[link.fromNode] === undefined) {
              columns[link.fromNode] = columns[current.id] + 1;
              queue.push(this.getNode(link.fromNode));
            }
          });
      }
    }
    const rows: Record<number, number> = {};
    this.nodes.forEach((node: GraphNode): void => {
      if (node.kind === "source") {
        node.x = 20;
        node.y = 20;
        return;
      }
      const column = columns[node.id] !== undefined ? columns[node.id] : 1;
      const row = rows[column] || 0;
      rows[column] = row + 1;
      node.x = 460 + column * 320;
      node.y = 20 + row * 260;
    });
  }
}

export default MappingGraph;

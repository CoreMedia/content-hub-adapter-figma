import Component from "@jangaroo/ext-ts/Component";
import Config from "@jangaroo/runtime/Config";
import ConfigUtils from "@jangaroo/runtime/ConfigUtils";
import MappingGraph, { GraphLink, GraphNode, PropertyInfo, SourcePort, ViewtypeInfo } from "./MappingGraph";

interface FigmaNodeEditorConfig extends Config<Component> {}

/**
 * A node editor for Figma transform descriptions, rendered with plain DOM and SVG:
 * <ul>
 *   <li>the Figma node on the left provides the layer values and nested components as output ports,</li>
 *   <li>each content node has a document type selector, a viewtype selector (if the type has a viewtype), its
 *   properties as input ports, and a "content" output port to be linked into a link property of another content
 *   node,</li>
 *   <li>a richtext node collects texts, images, components and contents in an ordered list, which is written into a
 *   markup property in that order,</li>
 *   <li>a linked port is placed on the side of its node that faces the linked node(s), unlinked ports keep their
 *   default side (outputs right, inputs left),</li>
 *   <li>links are created by dragging from an output port to a property, and removed by clicking them,</li>
 *   <li>nodes are moved by their header, the canvas is panned by dragging the background and zoomed with the wheel.</li>
 * </ul>
 */
class FigmaNodeEditor extends Component {
  declare Config: FigmaNodeEditorConfig;

  static override readonly xtype: string = "com.coremedia.blueprint.studio.contenthub.figma.figmaNodeEditor";

  static readonly #SVG_NS: string = "http://www.w3.org/2000/svg";

  #graph: MappingGraph = new MappingGraph();
  #world: HTMLElement = null;
  #svg: SVGSVGElement = null;
  #panX: number = 0;
  #panY: number = 0;
  #scale: number = 1;
  #filters: Record<string, string> = {};
  /** the expanded instances and components of the Figma node, by layer id */
  #expanded: Record<string, boolean> = {};
  #dragMove: (event: MouseEvent) => void = null;
  #dragEnd: (event: MouseEvent) => void = null;
  #messageHandler: (message: string) => void = null;
  #viewtypeLoader: (contentType: string) => Promise<Array<ViewtypeInfo>> = null;
  /** the applicable viewtypes by content type, loaded on demand */
  #viewtypes: Record<string, Array<ViewtypeInfo>> = {};
  #viewtypesLoading: Record<string, boolean> = {};
  /** incremented when the viewtypes are reset, so that responses of earlier requests are ignored */
  #viewtypesGeneration: number = 0;

  constructor(config: Config<FigmaNodeEditor> = null) {
    super(
      ConfigUtils.apply(
        Config(FigmaNodeEditor, {
          cls: "figma-node-editor",
          html: "",
        }),
        config,
      ),
    );
  }

  getGraph(): MappingGraph {
    return this.#graph;
  }

  setGraph(graph: MappingGraph): void {
    this.#graph = graph;
    this.redraw();
  }

  /**
   * Called with validation messages, e.g. for links that are not allowed.
   */
  setMessageHandler(handler: (message: string) => void): void {
    this.#messageHandler = handler;
  }

  /**
   * Sets the function that loads the viewtypes applicable to a content type.
   */
  setViewtypeLoader(loader: (contentType: string) => Promise<Array<ViewtypeInfo>>): void {
    this.#viewtypeLoader = loader;
    this.resetViewtypes();
  }

  /**
   * Discards the loaded viewtypes, e.g. when the preferred site changes, and reloads them as needed.
   */
  resetViewtypes(): void {
    this.#viewtypes = {};
    this.#viewtypesLoading = {};
    this.#viewtypesGeneration++;
    this.redraw();
  }

  /**
   * Adds a content node in the visible area.
   */
  addContentNode(): void {
    const type = this.#graph.contentTypes.length > 0 ? this.#defaultType() : "CMTeaser";
    const x = (-this.#panX + 460) / this.#scale;
    const y = (-this.#panY + 40 + 30 * (this.#graph.nodes.length % 8)) / this.#scale;
    this.#graph.addContentNode(type, x, y);
    this.redraw();
  }

  /**
   * Adds a richtext node in the visible area, to be linked into a markup property.
   */
  addMarkupNode(): void {
    const x = (-this.#panX + 460) / this.#scale;
    const y = (-this.#panY + 40 + 30 * (this.#graph.nodes.length % 8)) / this.#scale;
    this.#graph.addMarkupNode(x, y);
    this.redraw();
  }

  layout(): void {
    this.#graph.layout();
    this.#panX = 0;
    this.#panY = 0;
    this.redraw();
  }

  #defaultType(): string {
    const teaser = this.#graph.getContentType("CMTeaser");
    return teaser ? teaser.name : this.#graph.contentTypes[0].name;
  }

  protected override afterRender(): void {
    super.afterRender();
    const dom: HTMLElement = this.el.dom;
    dom.innerHTML = "";
    this.#world = document.createElement("div");
    this.#world.className = "fne-world";
    this.#svg = document.createElementNS(FigmaNodeEditor.#SVG_NS, "svg") as SVGSVGElement;
    this.#svg.setAttribute("class", "fne-links");
    this.#world.appendChild(this.#svg);
    dom.appendChild(this.#world);
    dom.addEventListener("mousedown", (event: MouseEvent): void => this.#startPan(event));
    dom.addEventListener("wheel", (event: WheelEvent): void => this.#zoom(event), { passive: false });
    this.redraw();
  }

  protected override onDestroy(): void {
    this.#stopDrag();
    super.onDestroy();
  }

  // --- rendering ---------------------------------------------------

  redraw(): void {
    if (!this.#world) {
      return;
    }
    Array.prototype.slice.call(this.#world.querySelectorAll(".fne-node")).forEach((element: HTMLElement): void => {
      element.parentNode.removeChild(element);
    });
    this.#graph.nodes.forEach((node: GraphNode): void => {
      this.#world.appendChild(
        node.kind === "source"
          ? this.#renderSource(node)
          : node.kind === MappingGraph.MARKUP_NODE
            ? this.#renderMarkup(node)
            : this.#renderContent(node),
      );
    });
    this.#applyTransform();
    this.#drawLinks();
  }

  #applyTransform(): void {
    this.#world.style.transform = "translate(" + this.#panX + "px, " + this.#panY + "px) scale(" + this.#scale + ")";
  }

  #renderSource(node: GraphNode): HTMLElement {
    const element = this.#nodeElement(node, "fne-node fne-source");
    const header = this.#header(node, element, "Figma: " + (this.#graph.componentName || "no component loaded"));
    element.appendChild(header);

    const filter = this.#filterField(node.id, "Filter layers…");
    element.appendChild(filter);

    const body = document.createElement("div");
    body.className = "fne-body";
    const term = (this.#filters[node.id] || "").toLowerCase();
    const linked = (port: SourcePort): boolean =>
      this.#graph.links.some((link: GraphLink): boolean => link.fromPort === port.id);
    // matches of the filter and linked ports are shown even if their instance or component is collapsed
    const ports = this.#graph.sourcePorts.filter((port: SourcePort): boolean =>
      term
        ? (port.label + " " + (port.hint || "")).toLowerCase().indexOf(term) >= 0
        : this.#isExpanded(port) || linked(port),
    );
    if (ports.length === 0) {
      body.appendChild(
        this.#hint(
          this.#graph.sourcePorts.length === 0
            ? "Load a component from the library, or paste Figma JSON."
            : "No matching layers.",
        ),
      );
    }
    ports.forEach((port: SourcePort): void => {
      const row = document.createElement("div");
      row.className = "fne-row fne-out-row" + (port.kind === MappingGraph.COMPONENT ? " fne-component" : "");
      // the indentation is applied to the first element, so that a port on the left stays at the edge
      const indent = Math.min(port.depth, 8) * 10 + "px";
      if (port.group && this.#graph.hasGroupContent(port.group)) {
        row.appendChild(this.#toggle(port.group)).style.marginLeft = indent;
      }
      const label = document.createElement("span");
      if (!row.firstChild) {
        label.style.marginLeft = indent;
      }
      label.className = "fne-label";
      label.textContent = port.label;
      label.title =
        port.kind === MappingGraph.COMPONENT
          ? "Nested component " +
            port.layerId +
            ", transformed with the description named by its cm-type." +
            " Expand it to link its layers and properties directly."
          : (port.hint ? port.hint + "\n" : "") + port.layerId + "->" + port.path;
      row.appendChild(label);
      row.appendChild(this.#outPort(node.id, port.id, port.kind));
      body.appendChild(row);
    });
    element.appendChild(body);

    // custom paths, e.g. style.fontSize
    const custom = document.createElement("div");
    custom.className = "fne-custom";
    const input = document.createElement("input");
    input.type = "text";
    input.placeholder = "layerId->path, e.g. 1:4->style.fontSize";
    const add = document.createElement("button");
    add.textContent = "+";
    add.title = "Add a value port";
    add.addEventListener("click", (): void => {
      const value = input.value.trim();
      const separator = value.indexOf("->");
      if (separator <= 0 || separator === value.length - 2) {
        this.#message("Enter a value as <layerId>-><path>, e.g. 1:4->characters.");
        return;
      }
      this.#graph.addValuePort(value.substring(0, separator).trim(), value.substring(separator + 2).trim());
      this.redraw();
    });
    custom.appendChild(input);
    custom.appendChild(add);
    element.appendChild(custom);
    return element;
  }

  #renderContent(node: GraphNode): HTMLElement {
    const element = this.#nodeElement(node, "fne-node fne-content" + (node.root ? " fne-root" : ""));
    const header = this.#header(node, element, node.root ? "Initial content" : "Linked content");

    const rootToggle = document.createElement("label");
    rootToggle.className = "fne-root-toggle";
    rootToggle.title = "The content created for the component itself (initialType)";
    const radio = document.createElement("input");
    radio.type = "radio";
    radio.checked = !!node.root;
    radio.addEventListener("change", (): void => {
      this.#graph.setRoot(node.id);
      this.redraw();
    });
    rootToggle.appendChild(radio);
    rootToggle.appendChild(document.createTextNode("initial"));
    header.appendChild(rootToggle);

    const remove = document.createElement("button");
    remove.className = "fne-remove";
    remove.textContent = "×";
    remove.title = "Remove node";
    remove.addEventListener("click", (): void => {
      this.#graph.removeNode(node.id);
      this.redraw();
    });
    header.appendChild(remove);
    if (!node.root) {
      // linked contents are linked into a link property of another content
      header.appendChild(this.#outPort(node.id, MappingGraph.CONTENT_PORT, "content"));
    }
    element.appendChild(header);

    const select = document.createElement("select");
    select.className = "fne-type";
    const types = this.#graph.contentTypes.map((type: { name: string }): string => type.name);
    if (types.indexOf(node.contentType) < 0) {
      types.unshift(node.contentType);
    }
    types.forEach((name: string): void => {
      const option = document.createElement("option");
      option.value = name;
      option.textContent = name;
      option.selected = name === node.contentType;
      select.appendChild(option);
    });
    select.addEventListener("change", (): void => {
      this.#graph.setContentType(node.id, select.value);
      this.redraw();
    });
    select.addEventListener("mousedown", (event: MouseEvent): void => event.stopPropagation());
    element.appendChild(select);

    const type = this.#graph.getContentType(node.contentType);
    if (type && type.viewtype) {
      element.appendChild(this.#viewtypeSelect(node));
    }

    element.appendChild(this.#filterField(node.id, "Filter properties…"));

    const body = document.createElement("div");
    body.className = "fne-body";
    const term = (this.#filters[node.id] || "").toLowerCase();
    const properties: Array<PropertyInfo> = this.#graph.getProperties(node.contentType);
    const linked = (property: PropertyInfo): boolean =>
      this.#graph.links.some(
        (link: GraphLink): boolean => link.toNode === node.id && link.toProperty === property.name,
      );
    // linked properties first, so that the mapping is visible at a glance
    properties
      .filter((property: PropertyInfo): boolean => !term || property.name.toLowerCase().indexOf(term) >= 0)
      .sort((a: PropertyInfo, b: PropertyInfo): number => (linked(a) === linked(b) ? 0 : linked(a) ? -1 : 1))
      .forEach((property: PropertyInfo): void => {
        const row = document.createElement("div");
        row.className = "fne-row fne-in-row" + (linked(property) ? " fne-linked" : "");
        row.appendChild(this.#inPort(node.id, property.name, property.type));
        const label = document.createElement("span");
        label.className = "fne-label";
        label.textContent = property.name;
        row.appendChild(label);
        const badge = document.createElement("span");
        badge.className = "fne-badge fne-badge-" + property.type.toLowerCase();
        badge.textContent = property.type;
        row.appendChild(badge);
        body.appendChild(row);
      });
    if (!type) {
      body.appendChild(this.#hint("Unknown content type " + node.contentType));
    }
    element.appendChild(body);
    return element;
  }

  /**
   * A richtext node: its items in their order, each with an input port and buttons to move or remove it, and a last
   * port to append an item. The output port is linked into a markup property.
   */
  #renderMarkup(node: GraphNode): HTMLElement {
    const element = this.#nodeElement(node, "fne-node fne-markup");
    const header = this.#header(node, element, "Richtext");
    header.title = "Texts, images, components and contents, written into a markup property in this order";
    const remove = document.createElement("button");
    remove.className = "fne-remove";
    remove.textContent = "×";
    remove.title = "Remove node";
    remove.addEventListener("click", (): void => {
      this.#graph.removeNode(node.id);
      this.redraw();
    });
    header.appendChild(remove);
    header.appendChild(this.#outPort(node.id, MappingGraph.MARKUP_PORT, "markup"));
    element.appendChild(header);

    const body = document.createElement("div");
    body.className = "fne-body";
    node.slots.forEach((slot: string, index: number): void => {
      const link = this.#graph.getSlotLink(node.id, slot);
      const row = document.createElement("div");
      row.className = "fne-row fne-in-row fne-linked";
      row.appendChild(this.#inPort(node.id, slot, "markup"));
      const label = document.createElement("span");
      label.className = "fne-label";
      label.textContent = index + 1 + ". " + this.#itemLabel(link);
      label.title = label.textContent;
      row.appendChild(label);
      row.appendChild(this.#slotButton("▲", "Move up", index > 0, (): void => this.#graph.moveSlot(node.id, slot, -1)));
      row.appendChild(
        this.#slotButton("▼", "Move down", index < node.slots.length - 1, (): void =>
          this.#graph.moveSlot(node.id, slot, 1),
        ),
      );
      row.appendChild(this.#slotButton("×", "Remove", true, (): void => this.#graph.removeLink(link)));
      body.appendChild(row);
    });
    const append = document.createElement("div");
    append.className = "fne-row fne-in-row fne-append";
    append.appendChild(this.#inPort(node.id, MappingGraph.NEW_SLOT, "markup"));
    const hint = document.createElement("span");
    hint.className = "fne-label";
    hint.textContent = "+ drop a text, image, video or content";
    append.appendChild(hint);
    body.appendChild(append);
    element.appendChild(body);
    return element;
  }

  #itemLabel(link: GraphLink): string {
    if (link.fromNode === MappingGraph.SOURCE_NODE_ID) {
      const port = this.#graph.getSourcePort(link.fromPort);
      return port ? port.label : link.fromPort;
    }
    const content = this.#graph.getNode(link.fromNode);
    return content
      ? "Content: " + content.contentType + (content.viewtype ? " (" + content.viewtype + ")" : "")
      : link.fromNode;
  }

  #slotButton(text: string, title: string, enabled: boolean, action: () => void): HTMLElement {
    const button = document.createElement("button");
    button.className = "fne-slot-button";
    button.textContent = text;
    button.title = title;
    button.disabled = !enabled;
    button.addEventListener("mousedown", (event: MouseEvent): void => event.stopPropagation());
    button.addEventListener("click", (): void => {
      action();
      this.redraw();
    });
    return button;
  }

  /**
   * A selector for the viewtypes applicable to the type of the node. A viewtype that is not applicable (anymore)
   * stays selected, so that it is not dropped silently.
   */
  #viewtypeSelect(node: GraphNode): HTMLElement {
    const select = document.createElement("select");
    select.className = "fne-type fne-viewtype";
    select.title = "Viewtype of the content: the global viewtypes and the ones of the preferred site";
    const viewtypes = this.#getViewtypes(node.contentType);
    const selected = node.viewtype || "";
    const add = (value: string, text: string, title: string): void => {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = text;
      option.title = title;
      option.selected = value === selected;
      select.appendChild(option);
    };
    add("", viewtypes ? "Default viewtype" : "Loading viewtypes…", "No viewtype, the default rendering");
    (viewtypes || []).forEach((viewtype: ViewtypeInfo): void => {
      add(
        viewtype.key,
        viewtype.name + (viewtype.site ? " (" + viewtype.site + ")" : ""),
        viewtype.path + " (" + viewtype.key + ")",
      );
    });
    if (selected && !(viewtypes || []).some((viewtype: ViewtypeInfo): boolean => viewtype.key === selected)) {
      add(
        selected,
        selected + (viewtypes ? " (not applicable)" : ""),
        "No viewtype '" + selected + "' is applicable to " + node.contentType + " globally or in the preferred site",
      );
    }
    select.addEventListener("change", (): void => {
      this.#graph.setViewtype(node.id, select.value);
      this.redraw();
    });
    select.addEventListener("mousedown", (event: MouseEvent): void => event.stopPropagation());
    return select;
  }

  /**
   * The viewtypes applicable to the content type, or null while they are loaded.
   */
  #getViewtypes(contentType: string): Array<ViewtypeInfo> {
    if (this.#viewtypes[contentType]) {
      return this.#viewtypes[contentType];
    }
    if (this.#viewtypeLoader && !this.#viewtypesLoading[contentType]) {
      this.#viewtypesLoading[contentType] = true;
      const generation = this.#viewtypesGeneration;
      this.#viewtypeLoader(contentType)
        .then((viewtypes: Array<ViewtypeInfo>): void => {
          if (generation === this.#viewtypesGeneration) {
            this.#viewtypes[contentType] = Array.isArray(viewtypes) ? viewtypes : [];
            this.redraw();
          }
        })
        .catch((error: Error): void => {
          if (generation === this.#viewtypesGeneration) {
            this.#viewtypes[contentType] = [];
            this.#message("Cannot load the viewtypes of " + contentType + ": " + (error && error.message));
            this.redraw();
          }
        });
    }
    return null;
  }

  /**
   * Whether all instances and components enclosing the port are expanded.
   */
  #isExpanded(port: SourcePort): boolean {
    return !port.ancestors || port.ancestors.every((group: string): boolean => !!this.#expanded[group]);
  }

  #toggle(group: string): HTMLElement {
    const toggle = document.createElement("span");
    toggle.className = "fne-toggle";
    toggle.textContent = this.#expanded[group] ? "▾" : "▸";
    toggle.title = this.#expanded[group] ? "Collapse" : "Expand the layers and properties";
    toggle.addEventListener("mousedown", (event: MouseEvent): void => event.stopPropagation());
    toggle.addEventListener("click", (): void => {
      this.#expanded[group] = !this.#expanded[group];
      this.redraw();
    });
    return toggle;
  }

  #nodeElement(node: GraphNode, cls: string): HTMLElement {
    const element = document.createElement("div");
    element.className = cls;
    element.setAttribute("data-node", node.id);
    element.style.left = node.x + "px";
    element.style.top = node.y + "px";
    element.addEventListener("mousedown", (event: MouseEvent): void => event.stopPropagation());
    return element;
  }

  #header(node: GraphNode, element: HTMLElement, title: string): HTMLElement {
    const header = document.createElement("div");
    header.className = "fne-header";
    const text = document.createElement("span");
    text.className = "fne-title";
    text.textContent = title;
    header.appendChild(text);
    header.addEventListener("mousedown", (event: MouseEvent): void => this.#startMove(event, node, element));
    return header;
  }

  #filterField(nodeId: string, placeholder: string): HTMLElement {
    const input = document.createElement("input");
    input.type = "search";
    input.className = "fne-filter";
    input.placeholder = placeholder;
    input.value = this.#filters[nodeId] || "";
    input.addEventListener("input", (): void => {
      this.#filters[nodeId] = input.value;
      this.redraw();
      const focused = this.#world.querySelector("[data-node='" + nodeId + "'] .fne-filter") as HTMLInputElement;
      if (focused) {
        focused.focus();
        focused.setSelectionRange(focused.value.length, focused.value.length);
      }
    });
    return input;
  }

  #hint(text: string): HTMLElement {
    const hint = document.createElement("div");
    hint.className = "fne-hint";
    hint.textContent = text;
    return hint;
  }

  #outPort(nodeId: string, portId: string, kind: string): HTMLElement {
    const port = document.createElement("span");
    port.className = "fne-port fne-port-out fne-port-right fne-port-" + kind;
    port.setAttribute("data-out-node", nodeId);
    port.setAttribute("data-out-port", portId);
    port.addEventListener("mousedown", (event: MouseEvent): void => this.#startLink(event, nodeId, portId, port));
    return port;
  }

  #inPort(nodeId: string, property: string, type: string): HTMLElement {
    const port = document.createElement("span");
    port.className = "fne-port fne-port-in fne-port-left fne-port-" + type.toLowerCase();
    port.setAttribute("data-in-node", nodeId);
    port.setAttribute("data-in-property", property);
    return port;
  }

  // --- links -------------------------------------------------------

  #drawLinks(): void {
    while (this.#svg.firstChild) {
      this.#svg.removeChild(this.#svg.firstChild);
    }
    this.#placePorts();
    this.#graph.links.forEach((link: GraphLink): void => {
      const from = this.#portCenter(
        "[data-out-node='" + link.fromNode + "'][data-out-port='" + FigmaNodeEditor.#escape(link.fromPort) + "']",
      );
      const to = this.#portCenter(
        "[data-in-node='" + link.toNode + "'][data-in-property='" + FigmaNodeEditor.#escape(link.toProperty) + "']",
      );
      if (!from || !to) {
        // the port is filtered out
        return;
      }
      const path = this.#path(
        from,
        to,
        "fne-link" +
          (link.fromNode === MappingGraph.SOURCE_NODE_ID
            ? " fne-link-value"
            : link.fromPort === MappingGraph.MARKUP_PORT
              ? " fne-link-markup"
              : " fne-link-content"),
      );
      path.addEventListener("click", (): void => {
        this.#graph.removeLink(link);
        this.redraw();
      });
      const title = document.createElementNS(FigmaNodeEditor.#SVG_NS, "title");
      const intoMarkup = this.#graph.getNode(link.toNode).kind === MappingGraph.MARKUP_NODE;
      title.textContent = "Click to remove the link " + (intoMarkup ? "into the richtext" : "to " + link.toProperty);
      path.appendChild(title);
    });
  }

  /**
   * Places each linked port on the side of its node that faces the linked nodes (on average), so that links do not
   * cross their own node. Unlinked ports keep their default side.
   */
  #placePorts(): void {
    const centers: Record<string, number> = {};
    this.#graph.nodes.forEach((node: GraphNode): void => {
      const element = this.#world.querySelector("[data-node='" + node.id + "']") as HTMLElement;
      if (element) {
        centers[node.id] = element.offsetLeft + element.offsetWidth / 2;
      }
    });
    const place = (port: HTMLElement, nodeId: string, others: Array<string>, defaultRight: boolean): void => {
      const known = others.filter((other: string): boolean => centers[other] !== undefined && other !== nodeId);
      let right = defaultRight;
      if (known.length > 0 && centers[nodeId] !== undefined) {
        const average = known.reduce((sum: number, other: string): number => sum + centers[other], 0) / known.length;
        right = average > centers[nodeId];
      }
      port.classList.toggle("fne-port-right", right);
      port.classList.toggle("fne-port-left", !right);
    };
    this.#world.querySelectorAll("[data-out-node]").forEach((element: Element): void => {
      const port = element as HTMLElement;
      const nodeId = port.getAttribute("data-out-node");
      const portId = port.getAttribute("data-out-port");
      const others = this.#graph.links
        .filter((link: GraphLink): boolean => link.fromNode === nodeId && link.fromPort === portId)
        .map((link: GraphLink): string => link.toNode);
      place(port, nodeId, others, true);
    });
    this.#world.querySelectorAll("[data-in-node]").forEach((element: Element): void => {
      const port = element as HTMLElement;
      const nodeId = port.getAttribute("data-in-node");
      const property = port.getAttribute("data-in-property");
      const others = this.#graph.links
        .filter((link: GraphLink): boolean => link.toNode === nodeId && link.toProperty === property)
        .map((link: GraphLink): string => link.fromNode);
      place(port, nodeId, others, false);
    });
  }

  /**
   * A link leaves and enters its ports horizontally, in the direction of the side the port is on
   * (<code>dir</code> 1 for right, -1 for left).
   */
  #path(
    from: { x: number; y: number; dir: number },
    to: { x: number; y: number; dir: number },
    cls: string,
  ): SVGPathElement {
    const path = document.createElementNS(FigmaNodeEditor.#SVG_NS, "path") as SVGPathElement;
    const dx = Math.max(40, Math.abs(to.x - from.x) / 2);
    path.setAttribute(
      "d",
      "M " +
        from.x +
        " " +
        from.y +
        " C " +
        (from.x + from.dir * dx) +
        " " +
        from.y +
        ", " +
        (to.x + to.dir * dx) +
        " " +
        to.y +
        ", " +
        to.x +
        " " +
        to.y,
    );
    path.setAttribute("class", cls);
    this.#svg.appendChild(path);
    return path;
  }

  /**
   * The center of a port in world coordinates, and the direction of the side it is on.
   */
  #portCenter(selector: string): { x: number; y: number; dir: number } {
    const port = this.#world.querySelector(selector) as HTMLElement;
    if (!port) {
      return null;
    }
    const worldRect = this.#world.getBoundingClientRect();
    const rect = port.getBoundingClientRect();
    return {
      x: (rect.left + rect.width / 2 - worldRect.left) / this.#scale,
      y: (rect.top + rect.height / 2 - worldRect.top) / this.#scale,
      dir: port.classList.contains("fne-port-left") ? -1 : 1,
    };
  }

  static #escape(value: string): string {
    return value.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
  }

  // --- interaction -------------------------------------------------

  #startLink(event: MouseEvent, nodeId: string, portId: string, port: HTMLElement): void {
    event.stopPropagation();
    event.preventDefault();
    const start = this.#portCenter(
      "[data-out-node='" + nodeId + "'][data-out-port='" + FigmaNodeEditor.#escape(portId) + "']",
    );
    let temp: SVGPathElement = null;
    port.classList.add("fne-port-active");
    this.#startDrag(
      (move: MouseEvent): void => {
        if (temp) {
          this.#svg.removeChild(temp);
        }
        const end = this.#toWorld(move);
        temp = this.#path(start, { x: end.x, y: end.y, dir: end.x < start.x ? 1 : -1 }, "fne-link fne-link-temp");
      },
      (end: MouseEvent): void => {
        if (temp) {
          this.#svg.removeChild(temp);
        }
        port.classList.remove("fne-port-active");
        const target = (end.target as HTMLElement).closest
          ? (end.target as HTMLElement).closest("[data-in-node]")
          : null;
        if (!target) {
          return;
        }
        const error = this.#graph.addLink(
          nodeId,
          portId,
          target.getAttribute("data-in-node"),
          target.getAttribute("data-in-property"),
        );
        if (error) {
          this.#message(error);
        }
        this.redraw();
      },
    );
  }

  #startMove(event: MouseEvent, node: GraphNode, element: HTMLElement): void {
    if ((event.target as HTMLElement).tagName === "INPUT" || (event.target as HTMLElement).tagName === "BUTTON") {
      return;
    }
    event.stopPropagation();
    event.preventDefault();
    const startX = event.clientX;
    const startY = event.clientY;
    const nodeX = node.x;
    const nodeY = node.y;
    this.#startDrag(
      (move: MouseEvent): void => {
        node.x = nodeX + (move.clientX - startX) / this.#scale;
        node.y = nodeY + (move.clientY - startY) / this.#scale;
        element.style.left = node.x + "px";
        element.style.top = node.y + "px";
        this.#drawLinks();
      },
      (): void => {},
    );
  }

  #startPan(event: MouseEvent): void {
    if (event.button !== 0) {
      return;
    }
    const startX = event.clientX;
    const startY = event.clientY;
    const panX = this.#panX;
    const panY = this.#panY;
    this.el.dom.classList.add("fne-panning");
    this.#startDrag(
      (move: MouseEvent): void => {
        this.#panX = panX + move.clientX - startX;
        this.#panY = panY + move.clientY - startY;
        this.#applyTransform();
      },
      (): void => {
        this.el.dom.classList.remove("fne-panning");
      },
    );
  }

  #zoom(event: WheelEvent): void {
    if (!event.ctrlKey && !event.metaKey) {
      // scrolling within nodes stays possible, zoom with ctrl/cmd + wheel
      return;
    }
    event.preventDefault();
    const rect = this.el.dom.getBoundingClientRect();
    const mouseX = event.clientX - rect.left;
    const mouseY = event.clientY - rect.top;
    const scale = Math.min(1.5, Math.max(0.3, this.#scale * (event.deltaY < 0 ? 1.1 : 0.9)));
    // keep the point under the mouse in place
    this.#panX = mouseX - (mouseX - this.#panX) * (scale / this.#scale);
    this.#panY = mouseY - (mouseY - this.#panY) * (scale / this.#scale);
    this.#scale = scale;
    this.#applyTransform();
  }

  #toWorld(event: MouseEvent): { x: number; y: number } {
    const worldRect = this.#world.getBoundingClientRect();
    return { x: (event.clientX - worldRect.left) / this.#scale, y: (event.clientY - worldRect.top) / this.#scale };
  }

  #startDrag(move: (event: MouseEvent) => void, end: (event: MouseEvent) => void): void {
    this.#stopDrag();
    this.#dragMove = move;
    this.#dragEnd = (event: MouseEvent): void => {
      this.#stopDrag();
      end(event);
    };
    document.addEventListener("mousemove", this.#dragMove);
    document.addEventListener("mouseup", this.#dragEnd);
  }

  #stopDrag(): void {
    if (this.#dragMove) {
      document.removeEventListener("mousemove", this.#dragMove);
      document.removeEventListener("mouseup", this.#dragEnd);
    }
    this.#dragMove = null;
    this.#dragEnd = null;
  }

  #message(message: string): void {
    if (this.#messageHandler) {
      this.#messageHandler(message);
    }
  }
}

export default FigmaNodeEditor;

/**
 * Interface values for ResourceBundle "FigmaStudioPlugin".
 * @see FigmaStudioPlugin_properties#INSTANCE
 */
interface FigmaStudioPlugin_properties {
  FigmaMapping_button_text: string;
  FigmaMapping_button_tooltip: string;
  FigmaMapping_title: string;
  FigmaMapping_loadSelection_text: string;
  FigmaMapping_loadSelection_tooltip: string;
  FigmaMapping_pasteJson_text: string;
  FigmaMapping_pasteJson_prompt: string;
  FigmaMapping_name_emptyText: string;
  FigmaMapping_loadMapping_text: string;
  FigmaMapping_addNode_text: string;
  FigmaMapping_addMarkup_text: string;
  FigmaMapping_addMarkup_tooltip: string;
  FigmaMapping_layout_text: string;
  FigmaMapping_save_text: string;
  FigmaMapping_help: string;
}

/**
 * Singleton for the current user Locale's instance of ResourceBundle "FigmaStudioPlugin".
 * @see FigmaStudioPlugin_properties
 */
const FigmaStudioPlugin_properties: FigmaStudioPlugin_properties = {
  FigmaMapping_button_text: "Figma Mapping",
  FigmaMapping_button_tooltip: "Map the selected Figma component to content",
  FigmaMapping_title: "Figma Mapping",
  FigmaMapping_loadSelection_text: "Load selected component",
  FigmaMapping_loadSelection_tooltip: "Load the JSON of the component selected in the library",
  FigmaMapping_pasteJson_text: "Paste JSON",
  FigmaMapping_pasteJson_prompt: "Paste the JSON of a Figma node (the document of GET /v1/files/:key/nodes):",
  FigmaMapping_name_emptyText: "cm-type (description name)",
  FigmaMapping_loadMapping_text: "Load mapping",
  FigmaMapping_addNode_text: "Add content node",
  FigmaMapping_addMarkup_text: "Add richtext",
  FigmaMapping_addMarkup_tooltip:
    "Add a richtext node: texts, images, videos and contents linked into it are written into a markup property in their order",
  FigmaMapping_layout_text: "Arrange",
  FigmaMapping_save_text: "Save",
  FigmaMapping_help:
    "Drag from a port to a property to link • click a link to remove it • drag the background to pan • Ctrl/Cmd + wheel to zoom",
};

export default FigmaStudioPlugin_properties;

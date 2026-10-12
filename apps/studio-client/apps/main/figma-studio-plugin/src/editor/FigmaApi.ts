import RemoteServiceMethod from "@coremedia/studio-client.client-core/data/impl/RemoteServiceMethod";
import RemoteServiceMethodResponse from "@coremedia/studio-client.client-core/data/impl/RemoteServiceMethodResponse";

/**
 * Calls of the FigmaMappingResource in the Studio server (<code>/rest/api/figma</code>).
 */
class FigmaApi {
  static #BASE: string = "figma/";

  /**
   * The node JSON of a Figma component: <code>{id, name, cmType, node}</code>.
   */
  static loadComponent(contentHubObjectId: string): Promise<any> {
    return FigmaApi.#call("GET", "component?id=" + encodeURIComponent(contentHubObjectId));
  }

  /**
   * The content types and their mappable properties: <code>[{name, properties: [{name, type}]}]</code>.
   */
  static loadContentTypes(): Promise<any> {
    return FigmaApi.#call("GET", "contenttypes");
  }

  /**
   * The viewtypes applicable to a content type, the global ones and the ones of the site:
   * <code>[{key, name, path, site}]</code>.
   */
  static loadViewtypes(contentType: string, siteId: string): Promise<any> {
    return FigmaApi.#call(
      "GET",
      "viewtypes?type=" + encodeURIComponent(contentType) + (siteId ? "&site=" + encodeURIComponent(siteId) : ""),
    );
  }

  /**
   * The names of the existing transform descriptions.
   */
  static loadDescriptionNames(): Promise<any> {
    return FigmaApi.#call("GET", "descriptions");
  }

  /**
   * A transform description <code>{name, settings}</code>, rejects if it does not exist.
   */
  static loadDescription(name: string): Promise<any> {
    return FigmaApi.#call("GET", "descriptions/" + encodeURIComponent(name));
  }

  /**
   * Creates or updates the transform description.
   */
  static saveDescription(name: string, settings: any): Promise<any> {
    return FigmaApi.#call("PUT", "descriptions/" + encodeURIComponent(name), settings);
  }

  static #call(method: string, path: string, body: any = null): Promise<any> {
    return new Promise((resolve: (value: any) => void, reject: (error: Error) => void): void => {
      new RemoteServiceMethod(FigmaApi.#BASE + path, method, true).request(
        body,
        (response: RemoteServiceMethodResponse): void => {
          // getResponseJSON() resolves the response into Studio beans, but these responses are plain data
          try {
            resolve(response.text ? JSON.parse(response.text) : null);
          } catch (e) {
            reject(new Error("Invalid response of " + path + ": " + (e as Error).message));
          }
        },
        (response: RemoteServiceMethodResponse): void => {
          reject(new Error(FigmaApi.#errorMessage(response)));
        },
      );
    });
  }

  static #errorMessage(response: RemoteServiceMethodResponse): string {
    let message: string = response.statusText || "Request failed";
    try {
      const json = JSON.parse(response.text);
      message = json.message || json.error || message;
    } catch (e) {
      // not JSON
    }
    return response.status + " " + message;
  }
}

export default FigmaApi;

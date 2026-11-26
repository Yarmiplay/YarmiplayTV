/// <reference types="svelte" />
/// <reference types="vite/client" />

declare module "virtual:build-info" {
  const info: {
    version: string;
    built: string;
    releases: { version: string; date: string; text: string }[];
    malClientId: string;
    anilistClientId: string;
    openSubtitlesApiKey: string;
    subdlApiKey: string;
  };
  export default info;
}

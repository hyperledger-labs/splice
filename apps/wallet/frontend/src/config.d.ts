export {};

declare global {
  interface Window {
    canton_network_config: import('utils/config.ts').Config;
  }
}

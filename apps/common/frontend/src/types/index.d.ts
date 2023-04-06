import { Config } from '../config';

export {};

declare global {
  interface Window {
    canton_network_config: Config;
  }
  type Currency = 'CC' | 'USD';
  type Conversion = 'CCtoUSD' | 'USDtoCC';
}

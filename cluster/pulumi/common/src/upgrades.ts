import { config } from './config';

export function failOnAppVersionMismatch(): boolean {
  return config.envFlag('FAIL_ON_APP_VERSION_MISMATCH', true);
}

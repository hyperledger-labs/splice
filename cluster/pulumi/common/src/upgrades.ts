import { envFlag } from './utils';

export function failOnAppVersionMismatch(): boolean {
  return envFlag('FAIL_ON_APP_VERSION_MISMATCH', true);
}

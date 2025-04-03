import { Secret } from '@pulumi/gcp/secretmanager';

import { installUserConfigs } from './user-configs';
import { installInternalWhitelists } from './whitelists';

export function installAll(): Secret[] {
  const userConfigs = installUserConfigs();
  const whitelists = installInternalWhitelists();
  return userConfigs.concat(whitelists);
}

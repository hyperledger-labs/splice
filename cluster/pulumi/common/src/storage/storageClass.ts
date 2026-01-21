import { hyperdiskSupportConfig } from '../config/hyperdiskSupportConfig';

export const standardStorageClassName = hyperdiskSupportConfig.hyperdiskSupport.enabled
  ? 'hyperdisk-standard-rwo'
  : 'standard-rwo';

export const pvcSuffix = hyperdiskSupportConfig.hyperdiskSupport.enabled ? 'hd-pvc' : 'pvc';

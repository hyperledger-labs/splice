import { config } from './config';
import { loadYamlFromFile, SPLICE_ROOT } from './utils';

export const spliceInstanceNames = config.envFlag('ENABLE_CN_INSTANCE_NAMES')
  ? loadYamlFromFile(SPLICE_ROOT + '/cluster/configs/configs/ui-config-values.yaml')
  : {
      spliceInstanceNames: {
        networkName: 'Splice',
        networkFaviconUrl: 'https://www.hyperledger.org/hubfs/hyperledgerfavicon.png',
        amuletName: 'Amulet',
        amuletNameAcronym: 'AMT',
        nameServiceName: 'Amulet Name Service',
        nameServiceNameAcronym: 'ANS',
      },
    };

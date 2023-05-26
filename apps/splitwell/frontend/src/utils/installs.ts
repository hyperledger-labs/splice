import { SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

export type SplitwellInstalls = Pick<Map<string, ContractId<SplitwellInstall>>, 'get'>;

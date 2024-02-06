import { Contract } from 'common-frontend-utils';

import { SplitwellInstall, SplitwellRules } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

export type SplitwellInstalls = Pick<Map<string, ContractId<SplitwellInstall>>, 'get'>;

export type SplitwellRulesMap = Pick<Map<string, Contract<SplitwellRules>>, 'get'>;

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';

import { SplitwellInstall, SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';
import { ContractId } from '@daml/types';

export type SplitwellInstalls = Pick<Map<string, ContractId<SplitwellInstall>>, 'get'>;

export type SplitwellRulesMap = Pick<Map<string, Contract<SplitwellRules>>, 'get'>;

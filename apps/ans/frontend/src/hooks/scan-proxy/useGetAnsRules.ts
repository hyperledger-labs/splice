// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';
import { useGetAnsRulesFromResponse } from 'common-frontend/scan-api';

import { AnsRules } from '@daml.js/ans/lib/Splice/Ans/';

import { useValidatorScanProxyClient } from '../../context/ValidatorScanProxyContext';

//TODO(#8571) deduplicate this and reuse from specific libraries instead of all on common frontend
const useGetAnsRules = (): UseQueryResult<Contract<AnsRules>> => {
  const scanClient = useValidatorScanProxyClient();
  return useGetAnsRulesFromResponse(() => scanClient.getAnsRules({}));
};

export default useGetAnsRules;

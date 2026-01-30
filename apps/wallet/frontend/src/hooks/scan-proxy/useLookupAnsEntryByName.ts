// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useLookupAnsEntryByNameFromResponse } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { UseQueryResult } from '@tanstack/react-query';
import { AnsEntry } from '@lfdecentralizedtrust/scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupAnsEntryByName = (
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<AnsEntry | null> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupAnsEntryByNameFromResponse(
    name => scanClient.lookupAnsEntryByName(name),
    name,
    enabled,
    retryWhenNotFound,
    retry
  );
};

export default useLookupAnsEntryByName;

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useListAnsEntriesFromResponse } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { UseQueryResult } from '@tanstack/react-query';
import { AnsEntry } from 'scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useListAnsEntries = (pageSize: number, namePrefix?: string): UseQueryResult<AnsEntry[]> => {
  const scanClient = useValidatorScanProxyClient();
  return useListAnsEntriesFromResponse(
    (pageSize, namePrefix) => scanClient.listAnsEntries(pageSize, namePrefix),
    pageSize,
    namePrefix
  );
};

export default useListAnsEntries;

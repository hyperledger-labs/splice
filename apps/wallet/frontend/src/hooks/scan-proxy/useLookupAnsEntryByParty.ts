// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useLookupAnsEntryByPartyFromResponse } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { UseQueryResult } from '@tanstack/react-query';
import { AnsEntry } from '@lfdecentralizedtrust/scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupAnsEntryByParty = (party?: string): UseQueryResult<AnsEntry | null> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupAnsEntryByPartyFromResponse(p => scanClient.lookupAnsEntryByParty(p), party);
};

export default useLookupAnsEntryByParty;

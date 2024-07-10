// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import { useLookupAnsEntryByPartyFromResponse } from 'common-frontend/scan-api';
import { AnsEntry } from 'scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupAnsEntryByParty = (party?: string): UseQueryResult<AnsEntry> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupAnsEntryByPartyFromResponse(p => scanClient.lookupAnsEntryByParty(p), party);
};

export default useLookupAnsEntryByParty;

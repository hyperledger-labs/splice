import { UseQueryResult } from '@tanstack/react-query';
import { useLookupCnsEntryByPartyFromResponse } from 'common-frontend/scan-api';
import { CnsEntry } from 'scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupCnsEntryByParty = (party?: string): UseQueryResult<CnsEntry> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupCnsEntryByPartyFromResponse(p => scanClient.lookupCnsEntryByParty(p), party);
};

export default useLookupCnsEntryByParty;

import { UseQueryResult } from '@tanstack/react-query';
import { useLookupAnsEntryByPartyFromResponse } from 'common-frontend/scan-api';
import { AnsEntry } from 'scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupAnsEntryByParty = (party?: string): UseQueryResult<AnsEntry> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupAnsEntryByPartyFromResponse(p => scanClient.lookupAnsEntryByParty(p), party);
};

export default useLookupAnsEntryByParty;

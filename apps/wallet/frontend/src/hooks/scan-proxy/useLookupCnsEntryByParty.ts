import { UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';
import { useLookupCnsEntryByPartyFromResponse } from 'common-frontend/scan-api';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns/';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupCnsEntryByParty = (party?: string): UseQueryResult<Contract<CnsEntry>> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupCnsEntryByPartyFromResponse(p => scanClient.lookupCnsEntryByParty(p), party);
};

export default useLookupCnsEntryByParty;

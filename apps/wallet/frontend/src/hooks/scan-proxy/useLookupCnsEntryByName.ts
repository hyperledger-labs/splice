import { UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';
import { useLookupCnsEntryByNameFromResponse } from 'common-frontend/scan-api';

import { CnsEntry } from '@daml.js/cns/lib/CN/Cns/';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupCnsEntryByName = (
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<Contract<CnsEntry>> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupCnsEntryByNameFromResponse(
    name => scanClient.lookupCnsEntryByName(name),
    name,
    enabled,
    retryWhenNotFound,
    retry
  );
};

export default useLookupCnsEntryByName;

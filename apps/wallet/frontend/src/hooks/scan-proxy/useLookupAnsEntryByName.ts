import { UseQueryResult } from '@tanstack/react-query';
import { useLookupAnsEntryByNameFromResponse } from 'common-frontend/scan-api';
import { AnsEntry } from 'scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupAnsEntryByName = (
  name: string,
  enabled: boolean = true,
  retryWhenNotFound: boolean = false,
  retry: number = 3
): UseQueryResult<AnsEntry> => {
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

import { UseQueryResult } from '@tanstack/react-query';
import { useListAnsEntriesFromResponse } from 'common-frontend/scan-api';
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

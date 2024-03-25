import { UseQueryResult } from '@tanstack/react-query';
import { useListCnsEntriesFromResponse } from 'common-frontend/scan-api';
import { CnsEntry } from 'scan-openapi';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useListCnsEntries = (pageSize: number, namePrefix?: string): UseQueryResult<CnsEntry[]> => {
  const scanClient = useValidatorScanProxyClient();
  return useListCnsEntriesFromResponse(
    (pageSize, namePrefix) => scanClient.listCnsEntries(pageSize, namePrefix),
    pageSize,
    namePrefix
  );
};

export default useListCnsEntries;

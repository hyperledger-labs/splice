import { UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend';
import { useListCnsEntriesFromResponse } from 'common-frontend/scan-api';

import { CnsEntry } from '@daml.js/canton-name-service-0.2.0/lib/CN/Cns';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useListCnsEntries = (
  pageSize: number,
  namePrefix?: string
): UseQueryResult<Contract<CnsEntry>[]> => {
  const scanClient = useValidatorScanProxyClient();
  return useListCnsEntriesFromResponse(
    (pageSize, namePrefix) => scanClient.listCnsEntries(pageSize, namePrefix),
    pageSize,
    namePrefix
  );
};

export default useListCnsEntries;

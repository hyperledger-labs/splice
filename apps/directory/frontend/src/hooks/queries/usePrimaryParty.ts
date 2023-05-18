import { UseQueryResult, useQuery } from '@tanstack/react-query';

import { useLedgerApiClient } from '../../contexts/LedgerApiContext';

const usePrimaryParty = (): UseQueryResult<string> => {
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: ['fetchPrimaryParty', ledgerApi],
    refetchInterval: false, // primary party ID is static
    queryFn: async () => {
      return ledgerApi!.getPrimaryParty();
    },
    enabled: !!ledgerApi, // wait for dependencies to be defined
  });
};

export default usePrimaryParty;

import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { ListRecentActivityResponse } from 'scan-openapi';

import { useScanClient } from './ScanClientContext';

const useRecentActivity = (): UseQueryResult<ListRecentActivityResponse> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['scan-api', 'listRecentActivity'],
    queryFn: async () => scanClient.listRecentActivity(),
  });
};

export default useRecentActivity;

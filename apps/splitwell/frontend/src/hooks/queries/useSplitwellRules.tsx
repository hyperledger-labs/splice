import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { AssignedContract, usePrimaryParty } from 'common-frontend';

import { SplitwellRules } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const QuerySplitwellRulesOperationName = 'querySplitwellRules';
export const useSplitwellRules = (): UseQueryResult<AssignedContract<SplitwellRules>[]> => {
  const splitwellClient = useSplitwellClient();
  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;

  return useQuery({
    queryKey: [QuerySplitwellRulesOperationName, primaryPartyId],
    queryFn: async () => {
      if (primaryPartyId) {
        const response = await splitwellClient.listSplitwellRules();
        return response.rules.map(c => AssignedContract.decodeAssignedContract(c, SplitwellRules));
      } else {
        return null;
      }
    },
    enabled: !!primaryPartyId,
  });
};

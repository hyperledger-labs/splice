import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useSvClient } from 'common-frontend';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { AmuletRules } from '@daml.js/canton-amulet/lib/CC/AmuletRules';
import { ElectionRequest, SvcRules } from '@daml.js/svc-governance/lib/CN/SvcRules';

import { useSvAdminClient } from './SvAdminServiceContext';

type SvUiState =
  | {
      svUser: string;
      svPartyId: string;
      svcPartyId: string;
      votingThreshold: bigint;
      amuletRules: Contract<AmuletRules>;
      svcRules: Contract<SvcRules>;
    }
  | undefined;

export const useSvcInfos = (): UseQueryResult<SvUiState> => {
  const { getSvcInfo } = useSvClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getSvcInfo', SvcRules, AmuletRules],
    queryFn: async () => {
      const resp = await getSvcInfo();
      return {
        svUser: resp.sv_user,
        svPartyId: resp.sv_party_id,
        svcPartyId: resp.svc_party_id,
        votingThreshold: resp.voting_threshold,
        amuletRules: Contract.decodeOpenAPI(resp.amulet_rules, AmuletRules),
        svcRules: Contract.decodeOpenAPI(resp.svc_rules, SvcRules),
      };
    },
  });
};

export const useElectionContext = ():
  | UseQueryResult<{ ranking: Contract<ElectionRequest>[] }>
  | undefined => {
  const { getElectionRequest } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getElectionRequest'],
    queryFn: async () => {
      const { ranking } = await getElectionRequest();
      return {
        ranking: ranking.map(c => Contract.decodeOpenAPI(c, ElectionRequest)),
      };
    },
  });
};

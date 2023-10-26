import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy, useSvClient } from 'common-frontend';

import { CoinRules } from '@daml.js/canton-coin/lib/CC/Coin';
import { ElectionRequest, SvcRules } from '@daml.js/svc-governance/lib/CN/SvcRules';

import { useSvAdminClient } from './SvAdminServiceContext';

type SvUiState =
  | {
      svUser: string;
      svPartyId: string;
      svcPartyId: string;
      votingThreshold: bigint;
      coinRules: Contract<CoinRules>;
      svcRules: Contract<SvcRules>;
    }
  | undefined;

export const useSvcInfos = (): UseQueryResult<SvUiState> => {
  const { getSvcInfo } = useSvClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getSvcInfo', SvcRules, CoinRules],
    queryFn: async () => {
      const resp = await getSvcInfo();
      return {
        svUser: resp.sv_user,
        svPartyId: resp.sv_party_id,
        svcPartyId: resp.svc_party_id,
        votingThreshold: resp.voting_threshold,
        coinRules: Contract.decodeOpenAPI(resp.coin_rules, CoinRules),
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

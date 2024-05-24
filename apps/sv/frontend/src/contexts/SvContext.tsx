import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useSvClient } from 'common-frontend';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { SvNodeState } from '@daml.js/splice-dso-governance/lib/Splice/DSO/SvState';
import { ElectionRequest, DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { useSvAdminClient } from './SvAdminServiceContext';

type SvUiState =
  | {
      svUser: string;
      svPartyId: string;
      dsoPartyId: string;
      votingThreshold: bigint;
      amuletRules: Contract<AmuletRules>;
      dsoRules: Contract<DsoRules>;
      nodeStates: Contract<SvNodeState>[];
    }
  | undefined;

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const SvUiState = {
  encode: (uiState: SvUiState): unknown => {
    if (!uiState) {
      return undefined;
    }
    return {
      ...uiState,
      amuletRules: Contract.encode(AmuletRules, uiState.amuletRules),
      dsoRules: Contract.encode(DsoRules, uiState.dsoRules),
      nodeStates: uiState.nodeStates.map(c => Contract.encode(SvNodeState, c)),
    };
  },
};

export const useDsoInfos = (): UseQueryResult<SvUiState> => {
  const { getDsoInfo } = useSvClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['getDsoInfo', DsoRules, AmuletRules],
    queryFn: async () => {
      const resp = await getDsoInfo();
      return {
        svUser: resp.sv_user,
        svPartyId: resp.sv_party_id,
        dsoPartyId: resp.dso_party_id,
        votingThreshold: resp.voting_threshold,
        amuletRules: Contract.decodeOpenAPI(resp.amulet_rules, AmuletRules),
        dsoRules: Contract.decodeOpenAPI(resp.dso_rules, DsoRules),
        nodeStates: resp.sv_node_states.map(c => Contract.decodeOpenAPI(c, SvNodeState)),
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

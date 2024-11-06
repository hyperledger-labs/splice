// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useSvClient, DsoInfo } from 'common-frontend';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { SvNodeState } from '@daml.js/splice-dso-governance/lib/Splice/DSO/SvState';
import { ElectionRequest, DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { useSvAdminClient } from './SvAdminServiceContext';

export const useDsoInfos = (): UseQueryResult<DsoInfo> => {
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
        amuletRules: Contract.decodeOpenAPI(resp.amulet_rules.contract, AmuletRules),
        dsoRules: Contract.decodeOpenAPI(resp.dso_rules.contract, DsoRules),
        nodeStates: resp.sv_node_states.map(c => Contract.decodeOpenAPI(c.contract, SvNodeState)),
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

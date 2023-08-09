import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, useSvClient } from 'common-frontend';

import { CoinRules } from '@daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { SvcRules } from '@daml.js/svc-governance/lib/CN/SvcRules';

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
    queryKey: ['getSvcInfo', SvcRules, CoinRules],
    queryFn: async () => {
      const resp = await getSvcInfo();
      return {
        svUser: resp.svUser,
        svPartyId: resp.svPartyId,
        svcPartyId: resp.svcPartyId,
        votingThreshold: resp.votingThreshold,
        coinRules: Contract.decodeOpenAPI(resp.coinRules, CoinRules),
        svcRules: Contract.decodeOpenAPI(resp.svcRules, SvcRules),
      };
    },
  });
};

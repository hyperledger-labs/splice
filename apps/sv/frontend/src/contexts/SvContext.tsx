import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { useSvClient } from 'common-frontend';
import { Contract } from 'common-frontend/lib/utils/interfaces';

import { CoinRules } from '@daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { SvcRules } from '@daml.js/svc-governance/lib/CN/SvcRules';
import { ContractId } from '@daml/types';

type SvUiState =
  | {
      svUser: string;
      svPartyId: string;
      svcPartyId: string;
      coinRulesContractId: ContractId<CoinRules>;
      svcRules: Contract<SvcRules>;
    }
  | undefined;

export const useSvcInfos = (): UseQueryResult<SvUiState> => {
  const { getSvcInfo } = useSvClient();
  return useQuery({
    queryKey: ['getSvcInfo', SvcRules],
    queryFn: async () => {
      const resp = await getSvcInfo();
      return {
        svUser: resp.svUser,
        svPartyId: resp.svPartyId,
        svcPartyId: resp.svcPartyId,
        coinRulesContractId: resp.coinRulesContractId as ContractId<CoinRules>,
        svcRules: Contract.decodeOpenAPI(resp.svcRules, SvcRules),
      };
    },
  });
};

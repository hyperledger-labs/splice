import { useSvClient } from 'common-frontend';
import { Contract } from 'common-frontend/lib/utils/interfaces';
import React, { useContext, useEffect, useState } from 'react';

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

const SvUiContext = React.createContext<SvUiState | undefined>(undefined);

export const SvUiStateProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const svClient = useSvClient();
  const [SvcInfo, setSvcInfo] = useState<SvUiState>();
  useEffect(() => {
    svClient.getSvcInfo().then(resp =>
      setSvcInfo({
        svUser: resp.svUser,
        svPartyId: resp.svPartyId,
        svcPartyId: resp.svcPartyId,
        coinRulesContractId: resp.coinRulesContractId as ContractId<CoinRules>,
        svcRules: Contract.decodeOpenAPI(resp.svcRules, SvcRules),
      })
    );
  }, [svClient]);
  return <SvUiContext.Provider value={SvcInfo}>{children}</SvUiContext.Provider>;
};

export const useSvUiState: () => SvUiState | undefined = () => {
  const client = useContext<SvUiState>(SvUiContext);
  if (!client) {
    console.debug('SV state not initialized');
  }
  return client;
};

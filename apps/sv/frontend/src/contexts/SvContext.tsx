import { useSvClient } from 'common-frontend';
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
      svcRulesContractId: ContractId<SvcRules>;
    }
  | undefined;

const SvUiContext = React.createContext<SvUiState | undefined>(undefined);

export const SvUiStateProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const svClient = useSvClient();
  const [debugInfo, setDebugInfo] = useState<SvUiState>();
  useEffect(() => {
    svClient.getDebugInfo().then(resp =>
      setDebugInfo({
        svUser: resp.svUser,
        svPartyId: resp.svPartyId,
        svcPartyId: resp.svcPartyId,
        coinRulesContractId: resp.coinRulesContractId as ContractId<CoinRules>,
        svcRulesContractId: resp.svcRulesContractId as ContractId<SvcRules>,
      })
    );
  }, [svClient]);
  return <SvUiContext.Provider value={debugInfo}>{children}</SvUiContext.Provider>;
};

export const useSvUiState: () => SvUiState | undefined = () => {
  const client = useContext<SvUiState>(SvUiContext);
  if (!client) {
    console.debug('SV state not initialized');
  }
  return client;
};

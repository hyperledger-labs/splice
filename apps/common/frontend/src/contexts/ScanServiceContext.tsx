import * as openapi from 'scan-openapi';
import BigNumber from 'bignumber.js';
import React, { useContext, useMemo } from 'react';
import { GetOpenAndIssuingMiningRoundsRequest } from 'scan-openapi';

import { CoinRules, FeaturedAppRight } from '@daml.js/canton-coin/lib/CC/Coin';
import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';
import { Party } from '@daml/types';

import { Contract, OpenAPILoggingMiddleware } from '../utils';

const ScanContext = React.createContext<ScanClient | undefined>(undefined);

export interface ScanProps {
  url: string;
}

export interface ScanClient {
  /**
   * Expressed as USD/CC
   */
  getCoinPrice: () => Promise<BigNumber>;
  getCoinRules: () => Promise<Contract<CoinRules>>;
  lookupFeaturedAppRight: (partyId: Party) => Promise<Contract<FeaturedAppRight> | undefined>;
  getSvcPartyId: () => Promise<string>;
}

export const ScanClientProvider: React.FC<React.PropsWithChildren<ScanProps>> = ({
  url,
  children,
}) => {
  const friendlyClient: ScanClient | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new openapi.ServerConfiguration(url, {}),
      promiseMiddleware: [new OpenAPILoggingMiddleware('scan')],
    });
    const scanClient = new openapi.ScanApi(configuration);

    return {
      getCoinPrice: async () => {
        const request: GetOpenAndIssuingMiningRoundsRequest = {
          cached_open_mining_round_contract_ids: [],
          cached_issuing_round_contract_ids: [],
        };
        const openAndIssuingMiningRounds = await scanClient.getOpenAndIssuingMiningRounds(request);

        const openOpenRounds = Object.values(openAndIssuingMiningRounds.open_mining_rounds)
          .map(mybCached => Contract.decodeOpenAPI(mybCached.contract!, OpenMiningRound))
          .filter(omr => Date.parse(omr.payload.opensAt) <= Date.now());

        if (openOpenRounds.length > 0) {
          const latestOpenRound = openOpenRounds.reduce((prevOmr, currentOmr) =>
            prevOmr.payload.round.number > currentOmr.payload.round.number ? prevOmr : currentOmr
          );
          return new BigNumber(latestOpenRound.payload.coinPrice);
        } else {
          return new BigNumber(0);
        }
      },
      getCoinRules: async () => {
        const response = await scanClient.getCoinRules({});
        if (!response.coin_rules_update.contract) {
          throw new Error(
            `There was no coin rules contract in response: ${JSON.stringify(response)}`
          );
        }
        return Contract.decodeOpenAPI(response.coin_rules_update.contract, CoinRules);
      },
      lookupFeaturedAppRight: async (partyId: Party) => {
        const response = await scanClient.lookupFeaturedAppRight(partyId);
        return (
          response.featured_app_right &&
          Contract.decodeOpenAPI(response.featured_app_right, FeaturedAppRight)
        );
      },
      getSvcPartyId: async () => {
        const response = await scanClient.getSvcPartyId();
        return response.svc_party_id;
      },
    };
  }, [url]);

  return <ScanContext.Provider value={friendlyClient}>{children}</ScanContext.Provider>;
};

export const useScanClient: () => ScanClient = () => {
  const client = useContext<ScanClient | undefined>(ScanContext);
  if (!client) {
    throw new Error('Scan client not initialized');
  }
  return client;
};

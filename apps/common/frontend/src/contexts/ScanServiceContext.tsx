import * as openapi from 'scan-openapi';
import BigNumber from 'bignumber.js';
import React, { useContext, useMemo } from 'react';
import { GetOpenAndIssuingMiningRoundsRequest } from 'scan-openapi';

import { Party } from '@daml/types';

import { CoinRules, FeaturedAppRight } from '../../daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { OpenMiningRound } from '../../daml.js/canton-coin-0.1.0/lib/CC/Round';
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
          cachedOpenMiningRoundContractIds: [],
          cachedIssuingRoundContractIds: [],
        };
        const openAndIssuingMiningRounds = await scanClient.getOpenAndIssuingMiningRounds(request);

        const openOpenRounds = Object.values(openAndIssuingMiningRounds.openMiningRounds)
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
        if (!response.coinRulesUpdate.contract) {
          throw new Error(
            `There was no coin rules contract in response: ${JSON.stringify(response)}`
          );
        }
        return Contract.decodeOpenAPI(response.coinRulesUpdate.contract, CoinRules);
      },
      lookupFeaturedAppRight: async (partyId: Party) => {
        const response = await scanClient.lookupFeaturedAppRight(partyId);
        return (
          response.featuredAppRight &&
          Contract.decodeOpenAPI(response.featuredAppRight, FeaturedAppRight)
        );
      },
      getSvcPartyId: async () => {
        const response = await scanClient.getSvcPartyId();
        return response.svcPartyId;
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

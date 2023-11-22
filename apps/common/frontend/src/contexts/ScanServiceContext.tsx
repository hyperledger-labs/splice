import * as openapi from 'scan-openapi';
import BigNumber from 'bignumber.js';
import React, { useContext, useMemo } from 'react';
import {
  ApiException,
  GetOpenAndIssuingMiningRoundsRequest,
  ListEntriesResponse,
} from 'scan-openapi';

import { FeaturedAppRight } from '@daml.js/canton-coin/lib/CC/Coin';
import { CoinRules } from '@daml.js/canton-coin/lib/CC/CoinRules';
import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';
import { CnsEntry } from '@daml.js/cns/lib/CN/Cns';
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
  lookupEntryByName: (name: string) => Promise<Contract<CnsEntry>>;
  listEntries: (pageSize: number, namePrefix?: string) => Promise<ListEntriesResponse>;
  lookupEntryByParty: (partyId: string) => Promise<CnsEntry | undefined>;
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
      // TODO: (#8692) replace callers of these function with react query
      lookupEntryByName: async (name: string): Promise<Contract<CnsEntry>> => {
        const response = await scanClient.lookupCnsEntryByName(name);
        return Contract.decodeOpenAPI(response.entry, CnsEntry);
      },
      listEntries: async (pageSize: number, namePrefix?: string): Promise<ListEntriesResponse> => {
        return await scanClient.listCnsEntries(pageSize, namePrefix);
      },
      lookupEntryByParty: async (partyId: string): Promise<CnsEntry | undefined> => {
        try {
          const entryByPartyResponse = await scanClient.lookupCnsEntryByParty(partyId);
          const entry = entryByPartyResponse.entry;
          return Contract.decodeOpenAPI(entry, CnsEntry).payload;
        } catch (e) {
          if (e instanceof Error) {
            if ((e as ApiException<undefined>).code === 404) {
              console.debug(`No cns entry for partyId ${partyId} found`);
              return undefined;
            }
          }
          throw e;
        }
      },
      ApiException,
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

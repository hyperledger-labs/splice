import * as openapi from 'directory-openapi';
import {
  ApiException,
  GetProviderPartyIdResponse,
  ListEntriesResponse,
  LookupEntryByNameResponse,
  ServerConfiguration,
} from 'directory-openapi';
import React, { useContext, useMemo } from 'react';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory/module';

import { Contract, OpenAPILoggingMiddleware } from '../utils';

const DirectoryContext = React.createContext<DirectoryClient | undefined>(undefined);

export interface DirectoryProps {
  url: string;
}

export interface DirectoryClient {
  getProviderPartyId: () => Promise<GetProviderPartyIdResponse>;
  lookupEntryByParty: (partyId: string) => Promise<DirectoryEntry | undefined>;
  listEntries: (pageSize: number, namePrefix?: string) => Promise<ListEntriesResponse>;
  lookupEntryByName: (name: string) => Promise<LookupEntryByNameResponse>;
}

export const DirectoryClientProvider: React.FC<React.PropsWithChildren<DirectoryProps>> = ({
  url,
  children,
}) => {
  const friendlyClient: DirectoryClient | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [new OpenAPILoggingMiddleware('directory')],
    });
    const directoryClient = new openapi.DirectoryApi(configuration);

    return {
      getProviderPartyId: async (): Promise<GetProviderPartyIdResponse> => {
        return await directoryClient.getProviderPartyId();
      },
      lookupEntryByParty: async (partyId: string): Promise<DirectoryEntry | undefined> => {
        try {
          const entryByPartyResponse = await directoryClient.lookupEntryByParty(partyId);
          const entry = entryByPartyResponse.entry;
          return Contract.decodeOpenAPI(entry, DirectoryEntry).payload;
        } catch (e) {
          if (e instanceof Error) {
            if ((e as ApiException<undefined>).code === 404) {
              console.debug(`No directory entry for partyId ${partyId} found`);
              return undefined;
            }
          }
          throw e;
        }
      },
      listEntries: async (pageSize: number, namePrefix?: string): Promise<ListEntriesResponse> => {
        return await directoryClient.listEntries(pageSize, namePrefix);
      },
      lookupEntryByName: async (name: string): Promise<LookupEntryByNameResponse> => {
        return await directoryClient.lookupEntryByName(name);
      },
    };
  }, [url]);

  return <DirectoryContext.Provider value={friendlyClient}>{children}</DirectoryContext.Provider>;
};

export const useDirectoryClient: () => DirectoryClient = () => {
  const client = useContext<DirectoryClient | undefined>(DirectoryContext);
  if (!client) {
    throw new Error('Directory client not initialized');
  }
  return client;
};

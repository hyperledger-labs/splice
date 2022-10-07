import { RpcError, StatusCode } from 'grpc-web';
import React, { useState, useEffect, useMemo } from 'react';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { DirectoryServiceClient } from '../com/daml/network/directory/v0/Directory_serviceServiceClientPb';
import { LookupEntryByPartyRequest } from '../com/daml/network/directory/v0/directory_service_pb';
import { Contract } from '../utils';

const Party: React.FC<{ partyId: string }> = ({ partyId }) => {
  const directoryClient = useMemo(() => new DirectoryServiceClient('http://localhost:8084'), []);

  const [party, setParty] = useState<string>('...');
  useEffect(() => {
    const getName = async () => {
      const req = new LookupEntryByPartyRequest().setUser(partyId);
      try {
        const value = await directoryClient.lookupEntryByParty(req, null);
        const entry = value.getEntry();
        if (entry === undefined) {
          throw new Error('directory lookup unexpectedly returned undefined');
        }
        const name = Contract.decode(entry, DirectoryEntry).payload.name;
        setParty(name);
      } catch (e) {
        if (e instanceof RpcError) {
          if (e.code === StatusCode.NOT_FOUND) {
            setParty(partyId);
            return;
          }
        }
        throw e;
      }
    };
    getName();
  }, [directoryClient, partyId]);

  return <div>{party}</div>;
};

export default Party;

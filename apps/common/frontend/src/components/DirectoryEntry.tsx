import { LookupEntryByPartyRequest } from 'common-protobuf/com/daml/network/directory/v0/directory_service_pb';
import { RpcError, StatusCode } from 'grpc-web';
import React, { useState, useEffect } from 'react';

import Tooltip from '@mui/material/Tooltip';

import { DirectoryEntry as damlDirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryClient } from '../contexts/DirectoryServiceContext';
import { Contract } from '../utils';

interface Entry {
  user: string;
  entry: damlDirectoryEntry | undefined; // undefined if no entry exists for this user
}

const DirectoryEntry: React.FC<{ partyId: string }> = ({ partyId }) => {
  const directoryClient = useDirectoryClient();

  const [entry, setParty] = useState<Entry | undefined>(undefined); // undefined state represents the directory lookup still being pending
  useEffect(() => {
    const getEntry = async () => {
      const req = new LookupEntryByPartyRequest().setUser(partyId);
      try {
        const value = await directoryClient.lookupEntryByParty(req, undefined);
        const entry = value.getEntry();
        if (entry === undefined) {
          throw new Error('directory lookup unexpectedly returned undefined');
        }
        const decoded = Contract.decode(entry, damlDirectoryEntry).payload;
        setParty({ user: partyId, entry: decoded });
      } catch (e) {
        if (e instanceof RpcError) {
          if (e.code === StatusCode.NOT_FOUND) {
            setParty({ user: partyId, entry: undefined });
            return;
          }
        }
        throw e;
      }
    };
    getEntry();
  }, [directoryClient, partyId]);

  const truncate = (s: string) => {
    if (s.length > 10) {
      return s.substring(0, 4) + '...' + s.substring(s.length - 4);
    } else {
      return s;
    }
  };

  if (entry === undefined) {
    return <div>...</div>;
  } else if (entry.entry === undefined) {
    return <span className="party-id">{entry.user}</span>;
  } else {
    return (
      <div>
        <Tooltip title="Directory Entry">
          <span className="dir-entry">{entry.entry.name}</span>
        </Tooltip>{' '}
        (
        <Tooltip title={'Party ID: ' + entry.user}>
          <span style={{ fontWeight: 'lighter' }}>{truncate(entry.user)}</span>
        </Tooltip>
        )
      </div>
    );
  }
};

export default DirectoryEntry;

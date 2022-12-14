import { ApiException } from 'common-openapi';
import React, { useState, useEffect } from 'react';

import Tooltip from '@mui/material/Tooltip';

import { DirectoryEntry as damlDirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryClient } from '../contexts/DirectoryServiceContext';
import { Contract } from '../utils';
import PartyId from './PartyId';

interface Entry {
  user: string;
  entry: damlDirectoryEntry | undefined; // undefined if no entry exists for this user
}

const DirectoryEntry: React.FC<{ partyId: string; noCopy?: boolean }> = ({ partyId, noCopy }) => {
  const directoryClient = useDirectoryClient();

  const [entry, setParty] = useState<Entry | undefined>(undefined); // undefined state represents the directory lookup still being pending
  useEffect(() => {
    const getEntry = async () => {
      try {
        const value = await directoryClient.lookupEntryByParty(partyId);
        const entry = value.entry;
        if (entry === undefined) {
          throw new Error('directory lookup unexpectedly returned undefined');
        }
        const decoded = Contract.decodeOpenAPI(entry, damlDirectoryEntry).payload;
        setParty({ user: partyId, entry: decoded });
      } catch (e) {
        if (e instanceof Error) {
          if ((e as ApiException<undefined>).code === 404) {
            setParty({ user: partyId, entry: undefined });
            return;
          }
        }
        throw e;
      }
    };
    getEntry();
  }, [directoryClient, partyId]);

  if (entry === undefined) {
    return <div>...</div>;
  }

  if (entry.entry === undefined) {
    return <PartyId partyId={partyId} noCopy={noCopy} />;
  } else {
    return (
      <div style={{ display: 'flex' }}>
        <Tooltip title="Directory Entry" style={{ marginRight: '4px' }}>
          <div className="dir-entry" style={{ display: 'flex', alignItems: 'center' }}>
            {entry.entry.name}
          </div>
        </Tooltip>
        {'('}
        <PartyId partyId={partyId} noCopy={noCopy} />
        {')'}
      </div>
    );
  }
};

export default DirectoryEntry;

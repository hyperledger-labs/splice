import React, { useState, useEffect } from 'react';

import Tooltip from '@mui/material/Tooltip';

import { DirectoryEntry as damlDirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryClient } from '../contexts';
import PartyId from './PartyId';

interface Entry {
  user: string;
  entry: damlDirectoryEntry | undefined; // undefined if no entry exists for this user
}

const DirectoryEntry: React.FC<{ partyId: string; noCopy?: boolean; classNames?: string }> = ({
  partyId,
  noCopy,
  classNames,
}) => {
  const directoryClient = useDirectoryClient();

  const [entry, setParty] = useState<Entry | undefined>(undefined); // undefined state represents the directory lookup still being pending
  useEffect(() => {
    const getEntry = async () => {
      const value = await directoryClient.lookupEntryByParty(partyId);
      setParty({ user: partyId, entry: value });
    };
    getEntry();
  }, [directoryClient, partyId]);

  if (entry === undefined) {
    return <div>...</div>;
  }

  if (entry.entry === undefined) {
    return <PartyId partyId={partyId} noCopy={noCopy} classNames={classNames} />;
  } else {
    return (
      <div style={{ display: 'flex' }} className={classNames}>
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

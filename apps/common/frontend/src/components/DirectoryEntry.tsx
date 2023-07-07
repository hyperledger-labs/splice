import React, { useState, useEffect } from 'react';

import { Typography, TypographyProps } from '@mui/material';
import Tooltip from '@mui/material/Tooltip';

import { DirectoryEntry as damlDirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryClient } from '../contexts';
import PartyId, { PartyIdProps } from './PartyId';

interface Entry {
  user: string;
  entry: damlDirectoryEntry | undefined; // undefined if no entry exists for this user
}

type DirectoryEntryProps = PartyIdProps & TypographyProps;

const DirectoryEntry: React.FC<DirectoryEntryProps> = props => {
  const { partyId, className, noCopy: _, ...typographyProps } = props;
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
    return <PartyId {...props} />;
  } else {
    return (
      <div
        style={{ display: 'flex', alignItems: 'center', whiteSpace: 'nowrap' }}
        className={`directory-entry ${className}`}
        data-selenium-text={`${entry.entry.name} (${partyId})`}
      >
        <Tooltip title="Directory Entry" style={{ marginRight: '4px' }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <Typography {...typographyProps}>{entry.entry.name}</Typography>
          </div>
        </Tooltip>
        <Typography {...typographyProps}>(</Typography>
        <PartyId {...props} />
        <Typography {...typographyProps}>)</Typography>
      </div>
    );
  }
};

export default DirectoryEntry;

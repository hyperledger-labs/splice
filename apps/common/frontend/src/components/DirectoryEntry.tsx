import { LookupEntryByPartyRequest } from 'common-protobuf/com/daml/network/directory/v0/directory_service_pb';
import { RpcError, StatusCode } from 'grpc-web';
import React, { useState, useEffect } from 'react';

import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';

import { DirectoryEntry as damlDirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useDirectoryClient } from '../contexts/DirectoryServiceContext';
import { Contract } from '../utils';

interface Entry {
  user: string;
  entry: damlDirectoryEntry | undefined; // undefined if no entry exists for this user
}

const DirectoryEntry: React.FC<{ partyId: string; noCopy?: boolean }> = ({ partyId, noCopy }) => {
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

  if (entry === undefined) {
    return <div>...</div>;
  }

  const handleClick = () => navigator.clipboard.writeText(entry.user);

  const partyIdComponent = (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <Tooltip title={'Party ID: ' + entry.user}>
        <div
          style={{
            display: 'inline-flex',
            maxWidth: '300px',
          }}
        >
          <div
            style={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              fontWeight: 'lighter',
            }}
            className="party-id"
          >
            {entry.user}
          </div>
        </div>
      </Tooltip>
      {!noCopy && (
        <IconButton onClick={handleClick}>
          <ContentCopyIcon fontSize={'small'} />
        </IconButton>
      )}
    </div>
  );

  if (entry.entry === undefined) {
    return partyIdComponent;
  } else {
    return (
      <div style={{ display: 'flex' }}>
        <Tooltip title="Directory Entry" style={{ marginRight: '4px' }}>
          <div className="dir-entry" style={{ display: 'flex', alignItems: 'center' }}>
            {entry.entry.name}
          </div>
        </Tooltip>
        {'('}
        {partyIdComponent}
        {')'}
      </div>
    );
  }
};

export default DirectoryEntry;

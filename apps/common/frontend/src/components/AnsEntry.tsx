import React from 'react';
import { AnsEntry as AnsEntryC } from 'scan-openapi';

import { Typography, TypographyProps } from '@mui/material';
import Tooltip from '@mui/material/Tooltip';

import useLookupAnsEntryByParty from '../api/scan/useLookupAnsEntryByParty';
import PartyId, { PartyIdProps } from './PartyId';

export type AnsEntryProps = PartyIdProps & TypographyProps;

const AnsEntry: React.FC<AnsEntryProps> = props => {
  const { partyId } = props;
  const { data: ansEntry, isLoading, isError } = useLookupAnsEntryByParty(partyId);

  if (isLoading || isError) {
    return <div>...</div>;
  } else {
    return <AnsEntryDisplay ansEntry={ansEntry} {...props} />;
  }
};

export const AnsEntryDisplay: React.FC<AnsEntryProps & { ansEntry: AnsEntryC | null }> = props => {
  const { ansEntry, partyId, className, noCopy: _, ...typographyProps } = props;

  if (ansEntry === null) {
    return <PartyId {...props} />;
  } else {
    return (
      <div
        style={{ display: 'flex', alignItems: 'center', whiteSpace: 'nowrap' }}
        className={`ans-entry ${className}`}
        data-selenium-text={`${ansEntry.name} (${partyId})`}
      >
        <Tooltip title="Directory Entry" style={{ marginRight: '4px' }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <Typography {...typographyProps}>{ansEntry.name}</Typography>
          </div>
        </Tooltip>
        <Typography {...typographyProps}>(</Typography>
        <PartyId {...props} />
        <Typography {...typographyProps}>)</Typography>
      </div>
    );
  }
};

export default AnsEntry;

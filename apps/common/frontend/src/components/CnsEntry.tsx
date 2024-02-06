import { Contract } from 'common-frontend-utils';
import React from 'react';

import { Typography, TypographyProps } from '@mui/material';
import Tooltip from '@mui/material/Tooltip';

import { CnsEntry as CnsEntryC } from '@daml.js/cns/lib/CN/Cns/';

import useLookupCnsEntryByParty from '../api/scan/useLookupCnsEntryByParty';
import PartyId, { PartyIdProps } from './PartyId';

export type CnsEntryProps = PartyIdProps & TypographyProps;

const CnsEntry: React.FC<CnsEntryProps> = props => {
  const { partyId } = props;
  const { data: cnsEntry, isLoading, isError } = useLookupCnsEntryByParty(partyId);

  if (isLoading || isError) {
    return <div>...</div>;
  } else {
    return <CnsEntryDisplay cnsEntry={cnsEntry} {...props} />;
  }
};

export const CnsEntryDisplay: React.FC<
  CnsEntryProps & { cnsEntry: Contract<CnsEntryC> | null }
> = props => {
  const { cnsEntry, partyId, className, noCopy: _, ...typographyProps } = props;

  if (cnsEntry === null) {
    return <PartyId {...props} />;
  } else {
    return (
      <div
        style={{ display: 'flex', alignItems: 'center', whiteSpace: 'nowrap' }}
        className={`cns-entry ${className}`}
        data-selenium-text={`${cnsEntry.payload.name} (${partyId})`}
      >
        <Tooltip title="Directory Entry" style={{ marginRight: '4px' }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <Typography {...typographyProps}>{cnsEntry.payload.name}</Typography>
          </div>
        </Tooltip>
        <Typography {...typographyProps}>(</Typography>
        <PartyId {...props} />
        <Typography {...typographyProps}>)</Typography>
      </div>
    );
  }
};

export default CnsEntry;

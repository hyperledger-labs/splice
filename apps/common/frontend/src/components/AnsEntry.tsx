// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import { Typography, TypographyProps } from '@mui/material';
import Tooltip from '@mui/material/Tooltip';

import useLookupAnsEntryByParty from '../api/scan/useLookupAnsEntryByParty';
import PartyId, { PartyIdProps } from './PartyId';

export type AnsEntryProps = PartyIdProps & TypographyProps;

const AnsEntry: React.FC<AnsEntryProps> = props => {
  const { partyId } = props;
  const { data: ansEntry, isPending, isError } = useLookupAnsEntryByParty(partyId);

  if (isPending || isError) {
    return <div>...</div>;
  } else {
    return <AnsEntryDisplay ansEntryName={ansEntry?.name} {...props} />;
  }
};

export const AnsEntryDisplay: React.FC<AnsEntryProps & { ansEntryName?: string }> = props => {
  const { ansEntryName, partyId, className, noCopy: _, ...typographyProps } = props;

  if (!ansEntryName) {
    return <PartyId {...props} />;
  } else {
    return (
      <div
        style={{ display: 'flex', alignItems: 'center', whiteSpace: 'nowrap' }}
        className={`ans-entry ${className}`}
        data-selenium-text={`${ansEntryName} (${partyId})`}
      >
        <Tooltip title="Directory Entry" style={{ marginRight: '4px' }}>
          <div style={{ display: 'flex', alignItems: 'center' }}>
            <Typography {...typographyProps}>{ansEntryName}</Typography>
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

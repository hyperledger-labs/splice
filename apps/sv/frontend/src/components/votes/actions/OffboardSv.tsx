// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import React, { SelectHTMLAttributes, useState } from 'react';

import { FormControl, NativeSelect, Stack, Typography } from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { useDsoInfos } from '../../../contexts/SvContext';

function createRow(key: string, value: string, isParty: boolean = false) {
  return { key, value, isParty };
}

const OffboardSv: React.FC<{ chooseAction: (action: ActionRequiringConfirmation) => void }> = ({
  chooseAction,
}) => {
  const dsoInfosQuery = useDsoInfos();
  const [member, setMember] = useState<string | undefined>(undefined);

  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  const memberOptions: { key: string; value: string }[] = [];
  dsoInfosQuery.data!.dsoRules.payload.svs.forEach((value, key) =>
    memberOptions.push(createRow(key, value.name))
  );
  function setMemberAction(member: string) {
    setMember(member);
    chooseAction({
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_OffboardSv',
          value: { sv: member },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Member</Typography>
      <FormControl fullWidth>
        <NativeSelect
          inputProps={
            {
              id: 'display-members',
              'data-testid': 'display-members',
            } as SelectHTMLAttributes<HTMLSelectElement>
          }
          value={member}
          onChange={e => setMemberAction(e.target.value)}
        >
          <option>No member selected</option>
          {memberOptions &&
            memberOptions.map((member, index) => (
              <option
                key={'member-option-' + index}
                value={member.key}
                data-testid={'display-members-option'}
              >
                {member.value}
              </option>
            ))}
        </NativeSelect>
      </FormControl>
    </Stack>
  );
};

export default OffboardSv;

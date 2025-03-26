// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import React, { useState } from 'react';

import { FormControl, Stack, TextField, Typography } from '@mui/material';

import { FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import { useDsoInfos } from '../../../contexts/SvContext';

const RevokeFeaturedAppRight: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const dsoInfosQuery = useDsoInfos();
  const [rightCid, setRightCid] = useState<string>('');

  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  function setRightCidAction(rightCid: string) {
    setRightCid(rightCid);
    chooseAction({
      tag: 'ARC_DsoRules',
      value: {
        dsoAction: {
          tag: 'SRARC_RevokeFeaturedAppRight',
          value: { rightCid: rightCid as ContractId<FeaturedAppRight> },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Featured Application Right Contract Id</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <TextField
          id="set-application-rightcid"
          onChange={e => setRightCidAction(e.target.value)}
          value={rightCid}
        />
      </FormControl>
    </Stack>
  );
};

export default RevokeFeaturedAppRight;

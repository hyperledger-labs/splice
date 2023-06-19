import { Loading } from 'common-frontend';
import React, { useState } from 'react';

import { FormControl, Stack, TextField, Typography } from '@mui/material';

import { FeaturedAppRight } from '@daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { ContractId } from '@daml/types';

import { useSvcInfos } from '../../../contexts/SvContext';

const RevokeFeaturedAppRight: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();
  const [rightCid, setRightCid] = useState<string>('');

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Not yet implemented.</p>;
  }

  function setRightCidAction(rightCid: string) {
    setRightCid(rightCid);
    chooseAction({
      tag: 'ARC_SvcRules',
      value: {
        svcAction: {
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

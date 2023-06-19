import { Loading } from 'common-frontend';
import React, { useState } from 'react';

import { FormControl, Stack, TextField, Typography } from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';

const GrantFeaturedAppRight: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();
  const [provider, setProvider] = useState<string>('');

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Not yet implemented.</p>;
  }

  function setProviderAction(provider: string) {
    setProvider(provider);
    chooseAction({
      tag: 'ARC_SvcRules',
      value: {
        svcAction: {
          tag: 'SRARC_GrantFeaturedAppRight',
          value: { provider: provider },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Provider</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <TextField
          id="set-application-provider"
          onChange={e => setProviderAction(e.target.value)}
          value={provider}
        />
      </FormControl>
    </Stack>
  );
};

export default GrantFeaturedAppRight;

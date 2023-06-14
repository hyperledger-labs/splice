import { Loading } from 'common-frontend';
import React, { useState } from 'react';

import { FormControl, Stack, TextField, Typography } from '@mui/material';

import { useSvcInfos } from '../../../contexts/SvContext';

const RevokeFeaturedAppRight: React.FC<{ chooseAction: (action: object) => void }> = ({
  chooseAction,
}) => {
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
    chooseAction({ action: 'SRARC_RevokeFeaturedAppRight', rightCid: rightCid });
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

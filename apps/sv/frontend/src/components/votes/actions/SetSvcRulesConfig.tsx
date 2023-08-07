import { JSONValue, JsonEditor, Loading } from 'common-frontend';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import {
  ActionRequiringConfirmation,
  SvcRulesConfig,
} from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';

const SetSvcRulesConfig: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Not yet implemented.</p>;
  }

  async function setSvcRulesConfigAction(svcRulesConfig: Record<string, JSONValue>) {
    const decodedConfig = SvcRulesConfig.decoder.runWithException(svcRulesConfig);
    chooseAction({
      tag: 'ARC_SvcRules',
      value: {
        svcAction: {
          tag: 'SRARC_SetConfig',
          value: { newConfig: decodedConfig },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Configuration</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <JsonEditor
          data={
            SvcRulesConfig.encode(svcInfosQuery.data?.svcRules.payload.config!) as Record<
              string,
              JSONValue
            >
          }
          onChange={setSvcRulesConfigAction}
        />
      </FormControl>
    </Stack>
  );
};

export default SetSvcRulesConfig;

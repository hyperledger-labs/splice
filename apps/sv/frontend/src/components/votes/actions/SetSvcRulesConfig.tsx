import { Loading } from 'common-frontend';
import { JSONValue, JsonEditor } from 'common-frontend-utils';
import React, { useState, useEffect } from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { SvcRulesConfig } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';

const SetSvcRulesConfig: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();
  // TODO (#10209): remove this intermediate state by lifting it to VoteRequest.tsx
  const [configuration, setConfiguration] = useState<Record<string, JSONValue> | undefined>(
    undefined
  );
  useEffect(() => {
    if (!configuration && svcInfosQuery.isSuccess) {
      setConfiguration(
        SvcRulesConfig.encode(svcInfosQuery.data?.svcRules.payload.config!) as Record<
          string,
          JSONValue
        >
      );
    }
  }, [configuration, svcInfosQuery]);

  if (svcInfosQuery.isError) {
    return <p>Error: {JSON.stringify(svcInfosQuery.error)}</p>;
  }

  if (!configuration) {
    return <Loading />;
  }

  async function setSvcRulesConfigAction(svcRulesConfig: Record<string, JSONValue>) {
    setConfiguration(svcRulesConfig);
    const decoded = SvcRulesConfig.decoder.run(svcRulesConfig);
    if (decoded.ok) {
      chooseAction({
        tag: 'ARC_SvcRules',
        value: {
          svcAction: {
            tag: 'SRARC_SetConfig',
            value: { newConfig: decoded.result },
          },
        },
      });
    } else {
      chooseAction({ formError: decoded.error });
    }
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Configuration</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <JsonEditor data={configuration} onChange={setSvcRulesConfigAction} />
      </FormControl>
    </Stack>
  );
};

export default SetSvcRulesConfig;

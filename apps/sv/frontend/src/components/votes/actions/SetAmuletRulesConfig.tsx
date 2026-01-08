// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { JsonEditor, JSONValue } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useEffect, useState } from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';

import { useDsoInfos } from '../../../contexts/SvContext';
import { ActionFromForm } from '../VoteRequest';
import BigNumber from 'bignumber.js';

dayjs.extend(utc);

const SetAmuletRulesConfig: React.FC<{
  chooseAction: (action: ActionFromForm) => void;
}> = ({ chooseAction }) => {
  const dsoInfosQuery = useDsoInfos();

  // TODO (#967): remove this intermediate state by lifting it to VoteRequest.tsx
  const [configuration, setConfiguration] = useState<Record<string, JSONValue>>();

  useEffect(() => {
    if (configuration == null && dsoInfosQuery.data) {
      const amuletConfig = dsoInfosQuery.data.amuletRules.payload.configSchedule.initialValue;
      const currentConfig = AmuletConfig(USD).encode(amuletConfig) as Record<string, JSONValue>;
      setConfiguration(currentConfig);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dsoInfosQuery.data]);

  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  if (!dsoInfosQuery.data) {
    return <p>undefined query data</p>;
  }

  if (configuration == null) {
    return <Loading />;
  }

  function setAmuletConfigAction(config: Record<string, JSONValue>) {
    setConfiguration(config);
    const decoded = AmuletConfig(USD).decoder.run(config);
    if (decoded.ok) {
      const result = decoded.result;
      const newConfig: AmuletConfig<USD> = {
        ...result,
        transferConfig: {
          ...result.transferConfig,
          transferFee: {
            initialRate: result.transferConfig.transferFee.initialRate,
            steps: result.transferConfig.transferFee.steps.filter(
              step => !BigNumber(step._2).eq(0)
            ),
          },
        },
      };
      console.log('New config transferFee', newConfig.transferConfig.transferFee);
      chooseAction({
        tag: 'ARC_AmuletRules',
        value: {
          amuletRulesAction: {
            tag: 'CRARC_SetConfig',
            value: {
              newConfig,
              baseConfig: dsoInfosQuery.data!.amuletRules.payload.configSchedule.initialValue,
            },
          },
        },
      });
    } else {
      chooseAction({ formError: decoded.error });
    }
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <Typography variant="h6" mt={4} data-testid="set-amulet-rules-config-header">
          Configuration
        </Typography>
        <JsonEditor data={configuration} onChange={setAmuletConfigAction} />
      </FormControl>
    </Stack>
  );
};

export default SetAmuletRulesConfig;

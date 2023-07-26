import { Loading } from 'common-frontend';
import React from 'react';

import { FormControl, Stack, Typography } from '@mui/material';

import { EnabledChoices } from '@daml.js/canton-coin-api-0.1.0/lib/CC/API/V1/Coin';
import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../contexts/SvContext';
import SwitchGroupComponent from '../../../utils/SwitchGroupComponent';

const SetEnabledChoices: React.FC<{
  chooseAction: (action: ActionRequiringConfirmation) => void;
}> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Not yet implemented.</p>;
  }

  if (!svcInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  async function setEnabledChoicesAction(choices: EnabledChoices) {
    if (svcInfosQuery.isLoading) {
      return <Loading />;
    }

    if (svcInfosQuery.isError) {
      return <p>Not yet implemented.</p>;
    }

    if (!svcInfosQuery.data) {
      return <p>no VoteRequest contractId is specified</p>;
    }

    chooseAction({
      tag: 'ARC_CoinRules',
      value: {
        coinRulesAction: {
          tag: 'CRARC_SetEnabledChoices',
          value: { newEnabledChoices: choices },
        },
      },
    });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Choices</Typography>
      <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
        <SwitchGroupComponent
          data={svcInfosQuery.data.coinRules.payload.enabledChoices}
          isDevNet={svcInfosQuery.data?.coinRules.payload.isDevNet}
          onChange={setEnabledChoicesAction}
        />
      </FormControl>
    </Stack>
  );
};

export default SetEnabledChoices;

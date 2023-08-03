import { useMutation } from '@tanstack/react-query';
import { SvClientProvider } from 'common-frontend';
import React, { useState } from 'react';

import {
  Box,
  Button,
  Card,
  CardContent,
  FormControl,
  NativeSelect,
  Stack,
  TextField,
  Typography,
} from '@mui/material';

import { ActionRequiringConfirmation } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useSvcInfos } from '../../contexts/SvContext';
import { config } from '../../utils';
import ListVoteRequests from './ListVoteRequests';
import AddFutureCoinConfigSchedule from './actions/AddFutureCoinConfigSchedule';
import GrantFeaturedAppRight from './actions/GrantFeaturedAppRight';
import RemoveFutureCoinConfigSchedule from './actions/RemoveFutureCoinConfigSchedule';
import RemoveMember from './actions/RemoveMember';
import RevokeFeaturedAppRight from './actions/RevokeFeaturedAppRight';
import SetCoinRulesConfig from './actions/SetCoinRulesConfig';
import SetCoinRulesEnabledChoices from './actions/SetCoinRulesEnabledChoices';
import SetSvcRulesConfig from './actions/SetSvcRulesConfig';
import UpdateFutureCoinConfigSchedule from './actions/UpdateFutureCoinConfigSchedule';

const VoteRequest: React.FC = () => {
  const [actionName, setActionName] = useState('SRARC_RemoveMember');
  const [summary, setSummary] = useState<string>('');
  const [url, setUrl] = useState<string>('');
  const svcInfosQuery = useSvcInfos();

  const actionNameOptions = [
    { name: 'Remove Member', value: 'SRARC_RemoveMember' },
    { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
    { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
    { name: 'Set SvcRules Configuration', value: 'SRARC_SetConfig' },
    { name: 'Set CoinRules Configuration', value: 'CRARC_SetConfigSchedule' },
    { name: 'Set CoinRules Enabled Choices', value: 'CRARC_SetEnabledChoices' },
    { name: 'Add Coin Configuration Schedule', value: 'CRARC_AddFutureCoinConfigSchedule' },
    { name: 'Remove Coin Configuration Schedule', value: 'CRARC_RemoveFutureCoinConfigSchedule' },
    { name: 'Update Coin Configuration Schedule', value: 'CRARC_UpdateFutureCoinConfigSchedule' },
  ];

  const [action, setAction] = useState<ActionRequiringConfirmation | undefined>(undefined);
  const chooseAction = (action: ActionRequiringConfirmation) => {
    setAction(action);
  };

  const { createVoteRequest } = useSvAdminClient();
  const createVoteRequestMutation = useMutation({
    mutationFn: async () => {
      const requester = svcInfosQuery.data?.svPartyId!;
      if (actionNameOptions.map(e => e.value).includes(actionName)) {
        return await createVoteRequest(requester, action!, url, summary)
          .then(() => setUrl(''))
          .then(() => setSummary(''))
          .then(() => setActionName('SRARC_RemoveMember'))
          .then(() => setAction(undefined));
      }
    },

    onError: error => {
      // TODO (#5491): show an error to the user.
      console.error(`Failed to send vote request to svc`, error);
    },
  });

  // TODO (#4966): add a popup to ask confirmation
  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={4} variant="h4">
        Create Vote Request
      </Typography>
      <Card variant="elevation">
        <CardContent sx={{ paddingX: '64px' }}>
          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h5">Action</Typography>
            <FormControl fullWidth>
              <NativeSelect
                inputProps={{ id: 'display-actions' }}
                value={actionName}
                onChange={e => setActionName(e.target.value)}
              >
                {actionNameOptions.map((actionName, index) => (
                  <option key={'action-option-' + index} value={actionName.value}>
                    {actionName.name}
                  </option>
                ))}
              </NativeSelect>
            </FormControl>
          </Stack>
          {actionName === 'SRARC_RemoveMember' && <RemoveMember chooseAction={chooseAction} />}
          {actionName === 'SRARC_GrantFeaturedAppRight' && (
            <GrantFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_RevokeFeaturedAppRight' && (
            <RevokeFeaturedAppRight chooseAction={chooseAction} />
          )}
          {actionName === 'SRARC_SetConfig' && <SetSvcRulesConfig chooseAction={chooseAction} />}
          {actionName === 'CRARC_SetConfigSchedule' && (
            <SetCoinRulesConfig chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_SetEnabledChoices' && (
            <SetCoinRulesEnabledChoices chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_AddFutureCoinConfigSchedule' && (
            <AddFutureCoinConfigSchedule chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_RemoveFutureCoinConfigSchedule' && (
            <RemoveFutureCoinConfigSchedule chooseAction={chooseAction} />
          )}
          {actionName === 'CRARC_UpdateFutureCoinConfigSchedule' && (
            <UpdateFutureCoinConfigSchedule chooseAction={chooseAction} />
          )}
          <Typography variant="h5">Reason</Typography>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Summary</Typography>
            <TextField
              id="create-reason-summary"
              rows={4}
              multiline
              onChange={e => setSummary(e.target.value)}
              value={summary}
            />
          </Stack>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">URL:</Typography>
            <Box display="flex">
              <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
                <TextField
                  id="create-reason-url"
                  onChange={e => setUrl(e.target.value)}
                  value={url}
                />
              </FormControl>
              <Button size={'small'} onClick={() => window.open(url, '_blank')}>
                Open
              </Button>
            </Box>
          </Stack>

          <Button
            id="create-voterequest-submit-button"
            fullWidth
            type={'submit'}
            size="large"
            onClick={() => {
              createVoteRequestMutation.mutate();
            }}
            disabled={createVoteRequestMutation.isLoading || action === undefined}
          >
            Send request to collective
          </Button>
        </CardContent>
      </Card>
    </Stack>
  );
};

const VoteRequestWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <VoteRequest />
      <ListVoteRequests />
    </SvClientProvider>
  );
};

export default VoteRequestWithContexts;

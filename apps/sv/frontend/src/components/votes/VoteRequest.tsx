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

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useSvcInfos } from '../../contexts/SvContext';
import { config } from '../../utils';
import ListVoteRequests from './ListVoteRequests';
import RemoveMember from './actions/RemoveMember';

const VoteRequest: React.FC = () => {
  const [actionName, setActionName] = useState('SRARC_RemoveMember');
  const [description, setDescription] = useState<string>('');
  const [url, setUrl] = useState<string>('');
  const svcInfosQuery = useSvcInfos();

  const actionNameOptions = [{ name: 'Remove Member', value: 'SRARC_RemoveMember' }];

  const [action, setAction] = useState<object | undefined>(undefined);
  const chooseAction = (action: object) => {
    setAction(action);
  };

  const { createVoteRequest } = useSvAdminClient();
  const createVoteRequestMutation = useMutation({
    mutationFn: async () => {
      const requester = svcInfosQuery.data?.svPartyId!;
      if (actionName === 'SRARC_RemoveMember') {
        return await createVoteRequest(requester, JSON.stringify(action), url, description);
      }
    },

    onError: error => {
      // TODO (#2831): show an error to the user.
      console.error(`Failed to send vote request to svc`, error);
    },
  });

  // TODO (#4966): add a popup to ask confirmation
  return (
    <Stack mt={4} spacing={4} direction="column" justifyContent="center">
      <Typography mt={6} variant="h4">
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

          <Typography variant="h5">Reason</Typography>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">URL</Typography>
            <Box display="flex">
              <FormControl sx={{ marginRight: '32px', flexGrow: '1' }}>
                <TextField
                  id="create-reason-url"
                  onChange={e => setUrl(e.target.value)}
                  value={url}
                />
              </FormControl>
            </Box>
          </Stack>

          <Stack direction="column" mb={4} spacing={1}>
            <Typography variant="h6">Description</Typography>
            <TextField
              id="create-reason-description"
              rows={4}
              multiline
              onChange={e => setDescription(e.target.value)}
              value={description}
            />
          </Stack>

          <Button
            id="create-voterequest-submit-button"
            fullWidth
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

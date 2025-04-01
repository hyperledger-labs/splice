// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ConfirmationDialog,
  DisableConditionally,
  SvClientProvider,
  SvVote,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation } from '@tanstack/react-query';
import React, { useState } from 'react';

import CheckIcon from '@mui/icons-material/Check';
import ClearIcon from '@mui/icons-material/Clear';
import EditIcon from '@mui/icons-material/Edit';
import SendIcon from '@mui/icons-material/Send';
import {
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
  Typography,
  ToggleButtonGroup,
  ToggleButton,
  TextField,
} from '@mui/material';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useSvConfig } from '../../utils';

interface VoteFormProps {
  vote?: SvVote;
  voteRequestCid: ContractId<VoteRequest>;
}

const VoteForm: React.FC<VoteFormProps> = ({ vote, voteRequestCid }) => {
  const [isEditing, setIsEditing] = useState<boolean>(false);
  const [voteEditing, setVoteEditing] = useState<'accept' | 'reject' | undefined>(undefined);
  const [reasonUrl, setReasonUrl] = useState<string>('');
  const [reasonBody, setReasonBody] = useState<string>('');
  const { castVote } = useSvAdminClient();

  const castOrUpdateVoteMutation = useMutation({
    mutationFn: () => {
      return castVote(voteRequestCid, voteEditing === 'accept', reasonUrl, reasonBody);
    },
    onSettled: async () => {
      setIsEditing(false);
    },
  });

  const handleChange = (
    _event: React.MouseEvent<HTMLElement>,
    value: 'accept' | 'reject' | undefined
  ) => {
    if (value) {
      setVoteEditing(value);
    }
  };

  const voteFromLedger = vote ? (vote.accept ? 'accept' : 'reject') : undefined;

  const startEditing = () => {
    setVoteEditing(voteFromLedger);
    setReasonUrl(vote ? vote.reason.url : '');
    setReasonBody(vote ? vote.reason.body : '');
    setIsEditing(true);
  };

  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);

  const handleConfirmationAccept = () => {
    castOrUpdateVoteMutation.mutate();
    setConfirmDialogOpen(false);
  };

  const handleConfirmationClose = () => {
    setConfirmDialogOpen(false);
  };

  return (
    <>
      <Typography variant="h5">
        {vote ? 'Your Vote ' : 'You have not voted yet '}
        {!isEditing &&
          (vote ? (
            <Button
              id="edit-vote-button"
              onClick={startEditing}
              variant="outlined"
              size="small"
              sx={{ marginLeft: 1 }}
              startIcon={<EditIcon fontSize={'small'} />}
            >
              Edit
            </Button>
          ) : (
            <DisableConditionally
              conditions={[{ disabled: castOrUpdateVoteMutation.isLoading, reason: 'Loading...' }]}
            >
              <Button id="cast-vote-button" size="small" variant="contained" onClick={startEditing}>
                vote
              </Button>
            </DisableConditionally>
          ))}
      </Typography>
      <Stack direction="column" mb={4} spacing={1}>
        <TableContainer>
          <Table style={{ tableLayout: 'auto' }} className="sv-voting-table">
            <TableBody>
              <TableRow>
                <TableCell>
                  <Typography variant="h6">Your Vote</Typography>
                </TableCell>
                <TableCell>
                  <DisableConditionally
                    conditions={[
                      { disabled: castOrUpdateVoteMutation.isLoading, reason: 'Loading...' },
                      { disabled: !isEditing, reason: 'Not editing' },
                    ]}
                  >
                    <ToggleButtonGroup
                      value={isEditing ? voteEditing : voteFromLedger}
                      exclusive
                      onChange={handleChange}
                      aria-label="Platform"
                    >
                      <ToggleButton id="reject-vote-button" color="error" value="reject">
                        <ClearIcon />
                        Reject
                      </ToggleButton>
                      <ToggleButton id="accept-vote-button" color="success" value="accept">
                        <CheckIcon />
                        Accept
                      </ToggleButton>
                    </ToggleButtonGroup>
                  </DisableConditionally>
                </TableCell>
              </TableRow>
              <TableRow>
                <TableCell>
                  <Typography variant="h6">Vote Reason Summary</Typography>
                </TableCell>
                <TableCell>
                  {isEditing ? (
                    <DisableConditionally
                      conditions={[
                        { disabled: castOrUpdateVoteMutation.isLoading, reason: 'Loading...' },
                      ]}
                    >
                      <TextField
                        sx={{ width: '100%' }}
                        id="vote-reason-body"
                        rows={4}
                        multiline
                        onChange={e => setReasonBody(e.target.value)}
                        value={reasonBody}
                      />
                    </DisableConditionally>
                  ) : (
                    <Typography id="vote-request-modal-vote-reason-body" variant="h6">
                      {vote ? vote.reason.body : ''}
                    </Typography>
                  )}
                </TableCell>
              </TableRow>
              <TableRow>
                <TableCell>
                  <Typography variant="h6">Vote Reason URL</Typography>
                </TableCell>
                <TableCell>
                  {isEditing ? (
                    <DisableConditionally
                      conditions={[
                        { disabled: castOrUpdateVoteMutation.isLoading, reason: 'Loading...' },
                      ]}
                    >
                      <TextField
                        sx={{ width: '100%' }}
                        id="vote-reason-url"
                        onChange={e => setReasonUrl(e.target.value)}
                        value={reasonUrl}
                      />
                    </DisableConditionally>
                  ) : (
                    <Typography id="vote-request-modal-vote-reason-url" variant="h6">
                      {vote ? vote.reason.url : ''}
                    </Typography>
                  )}
                </TableCell>
              </TableRow>
              {isEditing && (
                <TableRow>
                  <TableCell>
                    <Typography variant="h6"></Typography>
                  </TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={2}>
                      <DisableConditionally
                        conditions={[
                          { disabled: castOrUpdateVoteMutation.isLoading, reason: 'Loading...' },
                        ]}
                      >
                        <Button variant="outlined" onClick={() => setIsEditing(false)}>
                          Cancel
                        </Button>
                      </DisableConditionally>
                      <DisableConditionally
                        conditions={[
                          { disabled: castOrUpdateVoteMutation.isLoading, reason: 'Loading...' },
                          { disabled: voteEditing === undefined, reason: 'No vote to edit' },
                        ]}
                      >
                        <Button
                          id="save-vote-button"
                          variant="contained"
                          endIcon={<SendIcon />}
                          onClick={() => setConfirmDialogOpen(true)}
                        >
                          Save
                        </Button>
                      </DisableConditionally>
                    </Stack>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>

        <ConfirmationDialog
          showDialog={confirmDialogOpen}
          onClose={handleConfirmationClose}
          onAccept={handleConfirmationAccept}
          title="Confirm Your Vote"
          attributePrefix="vote"
        >
          <Typography variant="h5">
            Are you sure you want to {voteEditing} the changes proposed in this vote?
          </Typography>
        </ConfirmationDialog>
      </Stack>
    </>
  );
};

const VoteFormWithContexts: React.FC<VoteFormProps> = props => {
  const config = useSvConfig();
  return (
    <SvClientProvider url={config.services.sv.url}>
      <VoteForm {...props} />
    </SvClientProvider>
  );
};

export default VoteFormWithContexts;

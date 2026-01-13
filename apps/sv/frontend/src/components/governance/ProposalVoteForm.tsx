// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useForm } from '@tanstack/react-form';
import { z } from 'zod';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useMutation, UseMutationResult } from '@tanstack/react-query';
import { isValidUrl } from '../../utils/validations';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ProposalVote, VoteStatus } from '../../utils/types';
import { Alert, Box, Button, FormControlLabel, Radio, RadioGroup, TextField } from '@mui/material';
interface CastVoteArgs {
  accepted: boolean;
  url: string;
  reason: string;
}

interface ProposalVoteFormProps {
  voteRequestContractId: ContractId<VoteRequest>;
  currentSvPartyId: string;
  votes: ProposalVote[];
}

export const ProposalVoteForm: React.FC<ProposalVoteFormProps> = props => {
  const { voteRequestContractId, currentSvPartyId, votes } = props;
  const { castVote } = useSvAdminClient();
  const yourVote = votes.find(vote => vote.sv === currentSvPartyId);
  const previouslyVoted = yourVote?.vote !== 'no-vote';

  const castVoteMutation: UseMutationResult<void, string, CastVoteArgs> = useMutation({
    mutationKey: ['castVote', voteRequestContractId],
    mutationFn: async ({ accepted, url, reason }) => {
      return castVote(voteRequestContractId, accepted, url, reason);
    },
  });

  const form = useForm({
    defaultValues: {
      url: yourVote?.reason?.url || '',
      reason: yourVote?.reason?.body || '',
      vote: yourVote?.vote || 'no-vote',
    },

    onSubmit: async ({ value }) => {
      await castVoteMutation
        .mutateAsync({
          accepted: value.vote === 'accepted',
          url: value.url,
          reason: value.reason,
        })
        .catch(e => {
          console.error(`Failed to submit vote`, e);
        });
    },
  });

  if (castVoteMutation.isSuccess || castVoteMutation.isError) {
    return (
      <Box
        sx={{ p: 4, display: 'flex', justifyContent: 'center', alignItems: 'center' }}
        data-testid="submission-message"
      >
        {castVoteMutation.isSuccess && (
          <Alert severity="success" data-testid="vote-submission-success">
            Vote successfully updated!
          </Alert>
        )}

        {castVoteMutation.isError && (
          <Alert severity="error" data-testid="vote-submission-error">
            Something went wrong, unable to cast vote.
          </Alert>
        )}
      </Box>
    );
  }

  return (
    <Box data-testid="your-vote-form">
      <form
        onSubmit={e => {
          e.preventDefault();
          e.stopPropagation();
          form.handleSubmit();
        }}
      >
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <form.Field
            name="reason"
            validators={{
              onChange: ({ value }) => {
                const result = z.string().safeParse(value);
                return result.success ? undefined : result.error.issues[0].message;
              },
            }}
            children={field => {
              return (
                <TextField
                  label="Reason"
                  multiline
                  rows={4}
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={e => field.handleChange(e.target.value)}
                  error={!field.state.meta.isValid}
                  helperText={field.state.meta.errors?.[0]}
                  slotProps={{ htmlInput: { 'data-testid': 'your-vote-reason-input' } }}
                  id='your-vote-reason-input'
                />
              );
            }}
          />
          <form.Field
            name="url"
            validators={{
              onChange: ({ value }) => {
                const result = z
                  .string()
                  .optional()
                  // URL is optional so we allow undefined or empty string here as it's the default value
                  .refine(url => !url || url.trim() === '' || isValidUrl(url), {
                    message: 'Invalid URL',
                  })
                  .safeParse(value);
                return result.success ? undefined : result.error.issues[0].message;
              },
            }}
            children={field => {
              return (
                <TextField
                  label="URL"
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={e => field.handleChange(e.target.value)}
                  error={!field.state.meta.isValid}
                  helperText={
                    <span data-testid="your-vote-url-helper-text">
                      {field.state.meta.errors?.[0]}
                    </span>
                  }
                  slotProps={{ htmlInput: { 'data-testid': 'your-vote-url-input' } }}
                  id='your-vote-url-input'
                />
              );
            }}
          />
        </Box>

        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
            justifyContent: 'center',
            alignItems: 'center',
            mt: 4,
          }}
        >
          <form.Field
            name="vote"
            validators={{
              // Not using onChange here because by default, no vote is selected
              onMount: ({ value }) => {
                const result = z.enum(['accepted', 'rejected']).safeParse(value);
                return result.success ? undefined : result.error.issues[0].message;
              },
            }}
          >
            {field => (
              <RadioGroup
                row
                aria-labelledby="demo-row-radio-buttons-group-label"
                name="row-radio-buttons-group"
                value={field.state.value}
                onChange={e => field.handleChange(e.target.value as VoteStatus)}
                onBlur={field.handleBlur}
                sx={{ gap: 4 }}
              >
                <FormControlLabel
                  value="accepted"
                  data-testid="your-vote-accept"
                  control={<Radio color="success" />}
                  label="Accept"
                />
                <FormControlLabel
                  value="rejected"
                  data-testid="your-vote-reject"
                  control={<Radio color="error" />}
                  label="Reject"
                  color="error"
                />
              </RadioGroup>
            )}
          </form.Field>

          <form.Subscribe
            selector={state => [state.canSubmit, state.isSubmitting]}
            children={([canSubmit, isSubmitting]) => (
              <Button
                type="submit"
                disabled={!canSubmit || isSubmitting}
                variant="contained"
                sx={{ minWidth: 100 }}
                data-testid="submit-vote-button"
                id="submit-vote-button"
              >
                {isSubmitting ? 'Submitting...' : previouslyVoted ? 'Update' : 'Submit'}
              </Button>
            )}
          />
        </Box>
      </form>
    </Box>
  );
};

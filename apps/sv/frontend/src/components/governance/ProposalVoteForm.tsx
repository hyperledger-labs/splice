// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useForm } from '@tanstack/react-form';
import { retryOnRateLimit } from '@canton-network/splice-common-frontend';
import { z } from 'zod';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useMutation, UseMutationResult } from '@tanstack/react-query';
import { isValidUrl } from '../../utils/validations';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ProposalVote } from '../../utils/types';
import { Alert, Box, Button, TextField, Typography } from '@mui/material';
import { CREATE_PROPOSAL_FIELD_LABEL_SX } from '../../constants/createProposalLayout';
import { proposalSummaryFieldSx, singleLineFieldSx } from '../../themes/fieldStyles';
import {
  VOTE_REASON_PLACEHOLDER,
  VOTE_REASON_SUMMARY_LABEL,
  VOTE_REASON_URL_LABEL,
  VOTE_REASON_URL_PLACEHOLDER,
} from '../../utils/constants';
interface CastVoteArgs {
  accepted: boolean;
  url: string;
  reason: string;
}

interface ProposalVoteFormProps {
  voteRequestContractId: ContractId<VoteRequest>;
  currentSvPartyId: string;
  votes: ProposalVote[];
  onSubmissionStart?: () => void;
}

export const ProposalVoteForm: React.FC<ProposalVoteFormProps> = props => {
  const { voteRequestContractId, currentSvPartyId, votes, onSubmissionStart } = props;
  const { castVote } = useSvAdminClient();
  const yourVote = votes.find(vote => vote.sv === currentSvPartyId);

  const castVoteMutation: UseMutationResult<void, Error, CastVoteArgs> = useMutation({
    mutationKey: ['castVote', voteRequestContractId],
    mutationFn: async ({ accepted, url, reason }) => {
      return castVote(voteRequestContractId, accepted, url, reason);
    },
    onMutate: () => onSubmissionStart?.(),
    retry: retryOnRateLimit,
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
    <Box
      data-testid="your-vote-form"
      sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '100%' }}
    >
      <form
        onSubmit={e => {
          e.preventDefault();
          e.stopPropagation();
          void form.handleSubmit();
        }}
        style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '100%' }}
      >
        <Box sx={{ display: 'flex', flexDirection: 'column', width: '100%', gap: 3 }}>
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
                <Box>
                  <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}>
                    {VOTE_REASON_SUMMARY_LABEL}
                  </Typography>
                  <TextField
                    fullWidth
                    variant="outlined"
                    multiline
                    autoComplete="off"
                    name={field.name}
                    value={field.state.value}
                    onBlur={field.handleBlur}
                    onChange={e => field.handleChange(e.target.value)}
                    error={!field.state.meta.isValid}
                    helperText={field.state.meta.errors?.[0]}
                    placeholder={VOTE_REASON_PLACEHOLDER}
                    inputProps={{ 'data-testid': 'your-vote-reason-input' }}
                    sx={proposalSummaryFieldSx}
                  />
                </Box>
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
                <Box>
                  <Typography component="p" sx={{ ...CREATE_PROPOSAL_FIELD_LABEL_SX, mb: 1 }}>
                    {VOTE_REASON_URL_LABEL}
                  </Typography>
                  <TextField
                    fullWidth
                    variant="outlined"
                    autoComplete="off"
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
                    placeholder={VOTE_REASON_URL_PLACEHOLDER}
                    inputProps={{ 'data-testid': 'your-vote-url-input' }}
                    sx={singleLineFieldSx}
                  />
                </Box>
              );
            }}
          />
        </Box>

        <form.Subscribe
          selector={state => [state.isSubmitting, state.isValid]}
          children={([isSubmitting, isValid]) => (
            <Box
              sx={{
                display: 'flex',
                flexDirection: 'row',
                gap: 3,
                justifyContent: 'center',
                alignItems: 'center',
                mt: 3,
              }}
            >
              {isSubmitting ? (
                <Typography color="text.secondary">Submitting...</Typography>
              ) : (
                <>
                  <Button
                    variant="pill"
                    color="secondary"
                    disabled={!isValid}
                    onClick={() => {
                      form.setFieldValue('vote', 'rejected');
                      void form.handleSubmit();
                    }}
                    sx={{ backgroundColor: 'transparent' }}
                    data-testid="your-vote-reject"
                  >
                    Reject
                  </Button>
                  <Button
                    variant="pill"
                    disabled={!isValid}
                    onClick={() => {
                      form.setFieldValue('vote', 'accepted');
                      void form.handleSubmit();
                    }}
                    data-testid="your-vote-accept"
                  >
                    Accept
                  </Button>
                </>
              )}
            </Box>
          )}
        />
      </form>
    </Box>
  );
};

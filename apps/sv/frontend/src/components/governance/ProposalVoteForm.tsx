// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useForm } from '@tanstack/react-form';
import { z } from 'zod';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import { useMutation, UseMutationResult } from '@tanstack/react-query';
import { isValidUrl } from '../../utils/validations';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ProposalVote } from '../../utils/types';
import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';
import { useEffect } from 'react';
interface CastVoteArgs {
  accepted: boolean;
  url: string;
  reason: string;
}

interface ProposalVoteFormProps {
  voteRequestContractId: ContractId<VoteRequest>;
  currentSvPartyId: string;
  votes: ProposalVote[];
  onSubmissionComplete?: () => void;
}

export const ProposalVoteForm: React.FC<ProposalVoteFormProps> = props => {
  const { voteRequestContractId, currentSvPartyId, votes, onSubmissionComplete } = props;
  const { castVote } = useSvAdminClient();
  const yourVote = votes.find(vote => vote.sv === currentSvPartyId);

  const castVoteMutation: UseMutationResult<void, string, CastVoteArgs> = useMutation({
    mutationKey: ['castVote', voteRequestContractId],
    mutationFn: async ({ accepted, url, reason }) => {
      return castVote(voteRequestContractId, accepted, url, reason);
    },
  });

  useEffect(() => {
    if (castVoteMutation.isSuccess || castVoteMutation.isError) {
      onSubmissionComplete?.();
    }
  }, [castVoteMutation.isSuccess, castVoteMutation.isError, onSubmissionComplete]);

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
          form.handleSubmit();
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
                <Stack gap={3}>
                  <Typography
                    variant="subtitle2"
                    color="white"
                    fontWeight="bold"
                    fontSize={18}
                    lineHeight={1}
                  >
                    Reason
                  </Typography>
                  <TextField
                    variant="filled"
                    multiline
                    rows={5}
                    name={field.name}
                    value={field.state.value}
                    onBlur={field.handleBlur}
                    onChange={e => field.handleChange(e.target.value)}
                    error={!field.state.meta.isValid}
                    helperText={field.state.meta.errors?.[0]}
                    inputProps={{ 'data-testid': 'your-vote-reason-input' }}
                    sx={{
                      '& .MuiFilledInput-root': {
                        borderRadius: 1,
                        paddingTop: 1,
                        fontFamily: 'Lato',
                        '&:before, &:after': {
                          display: 'none',
                        },
                      },
                    }}
                  />
                </Stack>
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
                <Stack gap={3}>
                  <Typography
                    variant="subtitle2"
                    color="white"
                    fontWeight="bold"
                    fontSize={18}
                    lineHeight={1}
                  >
                    Vote Reason URL
                  </Typography>
                  <TextField
                    variant="filled"
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
                    inputProps={{ 'data-testid': 'your-vote-url-input' }}
                    sx={{
                      '& .MuiFilledInput-root': {
                        borderRadius: 1,
                        fontFamily: 'Lato',
                        '&:before, &:after': {
                          display: 'none',
                        },
                      },
                      '& .MuiFilledInput-input': {
                        paddingTop: 1.5,
                        paddingBottom: 1.5,
                      },
                    }}
                  />
                </Stack>
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
                    disabled={!isValid}
                    onClick={() => {
                      form.setFieldValue('vote', 'accepted');
                      form.handleSubmit();
                    }}
                    data-testid="your-vote-accept"
                  >
                    Accept
                  </Button>
                  <Button
                    variant="pill"
                    color="secondary"
                    disabled={!isValid}
                    onClick={() => {
                      form.setFieldValue('vote', 'rejected');
                      form.handleSubmit();
                    }}
                    sx={{ backgroundColor: 'transparent' }}
                    data-testid="your-vote-reject"
                  >
                    Reject
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

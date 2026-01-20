// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';

import {
  Box,
  Button,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import {
  ConfirmationDialog,
  DateDisplay,
  DisableConditionally,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useMutation } from '@tanstack/react-query';

import { MintingDelegation } from '@daml.js/splice-wallet/lib/Splice/Wallet/MintingDelegation/module';
import { useMintingDelegations } from '../hooks/useMintingDelegations';
import { useMintingDelegationProposals } from '../hooks/useMintingDelegationProposals';
import {
  MintingDelegationWithStatus,
  MintingDelegationProposalWithStatus,
  useWalletClient,
} from '../contexts/WalletServiceContext';

export const shortenPartyId = (partyId: string): string => {
  const elements = partyId.split('::');
  if (elements.length == 2) {
    return `${elements[0]}::${elements[1].slice(0, 10)}…`;
  }
  return partyId;
};

export const Delegations: React.FC = () => {
  const delegationsQuery = useMintingDelegations();
  const proposalsQuery = useMintingDelegationProposals();

  const isLoading = delegationsQuery.isLoading || proposalsQuery.isLoading;

  if (isLoading) {
    return <Loading />;
  }

  if (delegationsQuery.isError) {
    return (
      <Typography color="error">
        Error loading delegations: {JSON.stringify(delegationsQuery.error)}
      </Typography>
    );
  }

  if (proposalsQuery.isError) {
    return (
      <Typography color="error">
        Error loading proposals: {JSON.stringify(proposalsQuery.error)}
      </Typography>
    );
  }

  // Sort by expiration date, earliest first
  const delegations = [...(delegationsQuery.data || [])].sort((a, b) =>
    a.contract.payload.expiresAt.localeCompare(b.contract.payload.expiresAt)
  );
  const proposals = [...(proposalsQuery.data || [])].sort((a, b) =>
    a.contract.payload.delegation.expiresAt.localeCompare(b.contract.payload.delegation.expiresAt)
  );

  const hasNoDelegations = delegations.length === 0;
  const hasNoProposals = proposals.length === 0;

  return (
    <Stack
      spacing={4}
      direction="column"
      justifyContent="center"
      id="delegations-page"
      marginTop={4}
    >
      <Typography variant="h4" id="proposals-label">
        Proposed
      </Typography>
      {hasNoProposals ? (
        <Typography variant="h6" id="no-proposals-message">
          No proposals
        </Typography>
      ) : (
        <Table aria-label="proposals table">
          <TableHead>
            <TableRow>
              <TableCell>Beneficiary</TableCell>
              <TableCell>Onboarded</TableCell>
              <TableCell>Max Amulets</TableCell>
              <TableCell>Expiration</TableCell>
              <TableCell>Accept</TableCell>
              <TableCell>Reject</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {proposals.map(proposal => {
              const existingDelegation = delegations.find(
                d =>
                  d.contract.payload.beneficiary ===
                  proposal.contract.payload.delegation.beneficiary
              )?.contract;
              return (
                <ProposalRow
                  key={proposal.contract.contractId}
                  proposal={proposal}
                  existingDelegation={existingDelegation}
                />
              );
            })}
          </TableBody>
        </Table>
      )}

      <Typography variant="h4" id="delegations-label">
        Active
      </Typography>
      {hasNoDelegations ? (
        <Typography variant="h6" id="no-delegations-message">
          None active
        </Typography>
      ) : (
        <Table aria-label="delegations table">
          <TableHead>
            <TableRow>
              <TableCell>Beneficiary</TableCell>
              <TableCell>Onboarded</TableCell>
              <TableCell>Max Amulets</TableCell>
              <TableCell>Expiration</TableCell>
              <TableCell>Withdraw</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {delegations.map(delegation => (
              <DelegationRow key={delegation.contract.contractId} delegation={delegation} />
            ))}
          </TableBody>
        </Table>
      )}
    </Stack>
  );
};

interface DelegationRowProps {
  delegation: MintingDelegationWithStatus;
}

const DelegationRow: React.FC<DelegationRowProps> = ({ delegation }) => {
  const { withdrawMintingDelegation } = useWalletClient();
  const { contract, beneficiaryOnboarded } = delegation;
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);

  const withdrawMutation = useMutation({
    mutationFn: async () => {
      return await withdrawMintingDelegation(contract.contractId);
    },
    onError: error => {
      console.error('Failed to withdraw minting delegation', error);
    },
  });

  const handleWithdrawClick = () => {
    setConfirmDialogOpen(true);
  };

  const handleConfirmAccept = () => {
    withdrawMutation.mutate();
    setConfirmDialogOpen(false);
  };

  const handleConfirmClose = () => {
    setConfirmDialogOpen(false);
  };

  return (
    <TableRow
      key={contract.contractId}
      id={`delegation-row-${contract.contractId}`}
      className="delegation-row"
    >
      <TableCell>
        <Typography className="delegation-beneficiary">
          {shortenPartyId(contract.payload.beneficiary)}
        </Typography>
      </TableCell>
      <TableCell>
        <Typography className="delegation-onboarded">{beneficiaryOnboarded ? '✓' : '✗'}</Typography>
      </TableCell>
      <TableCell>
        <Typography className="delegation-max-amulets">
          {contract.payload.amuletMergeLimit}
        </Typography>
      </TableCell>
      <TableCell>
        <Typography className="delegation-expiration">
          <DateDisplay datetime={contract.payload.expiresAt} />
        </Typography>
      </TableCell>
      <TableCell>
        <DisableConditionally
          conditions={[
            {
              disabled: withdrawMutation.isPending,
              reason: 'Withdrawing delegation...',
            },
          ]}
        >
          <Button
            variant="outlined"
            size="small"
            className="delegation-withdraw"
            onClick={handleWithdrawClick}
          >
            Withdraw
          </Button>
        </DisableConditionally>
        <ConfirmationDialog
          showDialog={confirmDialogOpen}
          onAccept={handleConfirmAccept}
          onClose={handleConfirmClose}
          title="Withdraw Minting Delegation"
          attributePrefix="withdraw-delegation"
        >
          <Typography>
            Are you sure you want to withdraw this minting delegation for{' '}
            {shortenPartyId(contract.payload.beneficiary)}?
          </Typography>
        </ConfirmationDialog>
      </TableCell>
    </TableRow>
  );
};

interface ProposalRowProps {
  proposal: MintingDelegationProposalWithStatus;
  existingDelegation?: Contract<MintingDelegation>;
}

const ProposalRow: React.FC<ProposalRowProps> = ({ proposal, existingDelegation }) => {
  const { acceptMintingDelegationProposal, rejectMintingDelegationProposal } = useWalletClient();
  const { contract, beneficiaryOnboarded } = proposal;
  const [acceptDialogOpen, setAcceptDialogOpen] = useState(false);
  const [rejectDialogOpen, setRejectDialogOpen] = useState(false);

  const acceptMutation = useMutation({
    mutationFn: async () => {
      return await acceptMintingDelegationProposal(contract.contractId);
    },
    onError: error => {
      console.error('Failed to accept minting delegation proposal', error);
    },
  });

  const rejectMutation = useMutation({
    mutationFn: async () => {
      return await rejectMintingDelegationProposal(contract.contractId);
    },
    onError: error => {
      console.error('Failed to reject minting delegation proposal', error);
    },
  });

  const handleAcceptClick = () => {
    setAcceptDialogOpen(true);
  };

  const handleAcceptConfirm = () => {
    acceptMutation.mutate();
    setAcceptDialogOpen(false);
  };

  const handleAcceptClose = () => {
    setAcceptDialogOpen(false);
  };

  const handleRejectClick = () => {
    setRejectDialogOpen(true);
  };

  const handleRejectConfirm = () => {
    rejectMutation.mutate();
    setRejectDialogOpen(false);
  };

  const handleRejectClose = () => {
    setRejectDialogOpen(false);
  };

  const delegation = contract.payload.delegation;

  return (
    <TableRow
      key={contract.contractId}
      id={`proposal-row-${contract.contractId}`}
      className="proposal-row"
    >
      <TableCell>
        <Typography className="proposal-beneficiary">
          {shortenPartyId(delegation.beneficiary)}
        </Typography>
      </TableCell>
      <TableCell>
        <Typography className="proposal-onboarded">{beneficiaryOnboarded ? '✓' : '✗'}</Typography>
      </TableCell>
      <TableCell>
        <Typography className="proposal-max-amulets">{delegation.amuletMergeLimit}</Typography>
      </TableCell>
      <TableCell>
        <Typography className="proposal-expiration">
          <DateDisplay datetime={delegation.expiresAt} />
        </Typography>
      </TableCell>
      <TableCell>
        <DisableConditionally
          conditions={[
            {
              disabled: !beneficiaryOnboarded,
              reason: 'Beneficiary is not onboarded',
            },
            {
              disabled: acceptMutation.isPending,
              reason: 'Accepting proposal...',
            },
          ]}
        >
          <Button
            variant="outlined"
            size="small"
            className="proposal-accept"
            onClick={handleAcceptClick}
          >
            Accept
          </Button>
        </DisableConditionally>
        <ConfirmationDialog
          showDialog={acceptDialogOpen}
          onAccept={handleAcceptConfirm}
          onClose={handleAcceptClose}
          title={
            existingDelegation ? 'Replace Minting Delegation' : 'Accept Minting Delegation Proposal'
          }
          attributePrefix="accept-proposal"
        >
          {existingDelegation ? (
            <Stack spacing={2}>
              <Typography variant="body1">
                A delegation already exists for {shortenPartyId(delegation.beneficiary)}. Accepting
                this proposal will replace the existing delegation.
              </Typography>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Max Amulets:
                </Typography>
                <Paper
                  variant="outlined"
                  sx={{ p: 1.5, display: 'flex', alignItems: 'center', gap: 2 }}
                >
                  <Box sx={{ flex: 1 }}>
                    <Typography variant="body2" color="text.secondary">
                      Current
                    </Typography>
                    <Typography className="existing-max-amulets">
                      {existingDelegation.payload.amuletMergeLimit}
                    </Typography>
                  </Box>
                  <Typography variant="h6">→</Typography>
                  <Box sx={{ flex: 1 }}>
                    <Typography variant="body2" color="text.secondary">
                      New
                    </Typography>
                    <Typography className="new-max-amulets">
                      {delegation.amuletMergeLimit}
                    </Typography>
                  </Box>
                </Paper>
              </Box>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Expiration:
                </Typography>
                <Paper
                  variant="outlined"
                  sx={{ p: 1.5, display: 'flex', alignItems: 'center', gap: 2 }}
                >
                  <Box sx={{ flex: 1 }}>
                    <Typography variant="body2" color="text.secondary">
                      Current
                    </Typography>
                    <Typography className="existing-expiration">
                      <DateDisplay datetime={existingDelegation.payload.expiresAt} />
                    </Typography>
                  </Box>
                  <Typography variant="h6">→</Typography>
                  <Box sx={{ flex: 1 }}>
                    <Typography variant="body2" color="text.secondary">
                      New
                    </Typography>
                    <Typography className="new-expiration">
                      <DateDisplay datetime={delegation.expiresAt} />
                    </Typography>
                  </Box>
                </Paper>
              </Box>
            </Stack>
          ) : (
            <Typography>
              Are you sure you want to accept this minting delegation proposal from{' '}
              {shortenPartyId(delegation.beneficiary)}?
            </Typography>
          )}
        </ConfirmationDialog>
      </TableCell>
      <TableCell>
        <DisableConditionally
          conditions={[
            {
              disabled: rejectMutation.isPending,
              reason: 'Rejecting proposal...',
            },
          ]}
        >
          <Button
            variant="outlined"
            size="small"
            className="proposal-reject"
            onClick={handleRejectClick}
          >
            Reject
          </Button>
        </DisableConditionally>
        <ConfirmationDialog
          showDialog={rejectDialogOpen}
          onAccept={handleRejectConfirm}
          onClose={handleRejectClose}
          title="Reject Minting Delegation Proposal"
          attributePrefix="reject-proposal"
        >
          <Typography>
            Are you sure you want to reject this minting delegation proposal from{' '}
            {shortenPartyId(delegation.beneficiary)}?
          </Typography>
        </ConfirmationDialog>
      </TableCell>
    </TableRow>
  );
};

export default Delegations;

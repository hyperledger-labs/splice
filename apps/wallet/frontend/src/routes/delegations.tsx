// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import {
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { DateDisplay, DisableConditionally, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useMutation } from '@tanstack/react-query';

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

  const delegations = delegationsQuery.data || [];
  const proposals = proposalsQuery.data || [];

  const hasNoDelegations = delegations.length === 0;
  const hasNoProposals = proposals.length === 0;

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="delegations-page" marginTop={4}>
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
            {proposals.map(proposal => (
              <ProposalRow key={proposal.contract.contractId} proposal={proposal} />
            ))}
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

  const withdrawMutation = useMutation({
    mutationFn: async () => {
      return await withdrawMintingDelegation(contract.contractId);
    },
    onError: error => {
      console.error('Failed to withdraw minting delegation', error);
    },
  });

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
        <Typography className="delegation-onboarded">
          {beneficiaryOnboarded ? '✓' : '✗'}
        </Typography>
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
            onClick={() => withdrawMutation.mutate()}
          >
            Withdraw
          </Button>
        </DisableConditionally>
      </TableCell>
    </TableRow>
  );
};

interface ProposalRowProps {
  proposal: MintingDelegationProposalWithStatus;
}

const ProposalRow: React.FC<ProposalRowProps> = ({ proposal }) => {
  const { acceptMintingDelegationProposal, rejectMintingDelegationProposal } = useWalletClient();
  const { contract, beneficiaryOnboarded } = proposal;

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

  const delegation = contract.payload.delegation;

  return (
    <TableRow
      key={contract.contractId}
      id={`proposal-row-${contract.contractId}`}
      className="proposal-row"
    >
      <TableCell>
        <Typography className="proposal-beneficiary">{shortenPartyId(delegation.beneficiary)}</Typography>
      </TableCell>
      <TableCell>
        <Typography className="proposal-onboarded">
          {beneficiaryOnboarded ? '✓' : '✗'}
        </Typography>
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
            onClick={() => acceptMutation.mutate()}
          >
            Accept
          </Button>
        </DisableConditionally>
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
            onClick={() => rejectMutation.mutate()}
          >
            Reject
          </Button>
        </DisableConditionally>
      </TableCell>
    </TableRow>
  );
};

export default Delegations;

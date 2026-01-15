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
import { DisableConditionally, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useMutation } from '@tanstack/react-query';

import { useMintingDelegations } from '../hooks/useMintingDelegations';
import { useMintingDelegationProposals } from '../hooks/useMintingDelegationProposals';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { MintingDelegationProposal } from '@daml.js/splice-wallet/lib/Splice/Wallet/MintingDelegation/module';

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
              <TableCell>Max Amulets</TableCell>
              <TableCell>Expiration</TableCell>
              <TableCell>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {proposals.map(proposal => (
              <ProposalRow key={proposal.contractId} proposal={proposal} />
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
              <TableCell>Max Amulets</TableCell>
              <TableCell>Expiration</TableCell>
              <TableCell>Withdraw</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {delegations.map(delegation => (
              <TableRow
                key={delegation.contractId}
                id={`delegation-row-${delegation.contractId}`}
                className="delegation-row"
              >
                <TableCell>
                  <Typography className="delegation-beneficiary">
                    {delegation.payload.beneficiary}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Typography className="delegation-max-amulets">
                    {delegation.payload.amuletMergeLimit}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Typography className="delegation-expiration">
                    {delegation.payload.expiresAt}
                  </Typography>
                </TableCell>
                <TableCell>
                  <Button variant="outlined" size="small" className="delegation-withdraw">
                    Withdraw
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Stack>
  );
};

interface ProposalRowProps {
  proposal: Contract<MintingDelegationProposal>;
}

const ProposalRow: React.FC<ProposalRowProps> = ({ proposal }) => {
  const { acceptMintingDelegationProposal } = useWalletClient();

  const acceptMutation = useMutation({
    mutationFn: async () => {
      return await acceptMintingDelegationProposal(proposal.contractId);
    },
    onError: error => {
      console.error('Failed to accept minting delegation proposal', error);
    },
  });

  const delegation = proposal.payload.delegation;

  return (
    <TableRow
      key={proposal.contractId}
      id={`proposal-row-${proposal.contractId}`}
      className="proposal-row"
    >
      <TableCell>
        <Typography className="proposal-beneficiary">{delegation.beneficiary}</Typography>
      </TableCell>
      <TableCell>
        <Typography className="proposal-max-amulets">{delegation.amuletMergeLimit}</Typography>
      </TableCell>
      <TableCell>
        <Typography className="proposal-expiration">{delegation.expiresAt}</Typography>
      </TableCell>
      <TableCell>
        <DisableConditionally
          conditions={[
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
    </TableRow>
  );
};

export default Delegations;

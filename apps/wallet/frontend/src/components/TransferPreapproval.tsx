// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useMutation } from '@tanstack/react-query';
import { DisableConditionally } from 'common-frontend';
import React from 'react';
import { useNavigate } from 'react-router-dom';

import ApprovalIcon from '@mui/icons-material/Approval';
import { Button, Tooltip } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { usePrimaryParty } from '../hooks';
import useLookupTransferPreapproval from '../hooks/scan-proxy/useLookupTransferPreapproval';

const TransferPreapproval: React.FC = () => {
  const primaryPartyId = usePrimaryParty();
  const transferPreapprovalQuery = useLookupTransferPreapproval(primaryPartyId);
  const { createTransferPreapproval } = useWalletClient();
  const navigate = useNavigate();

  const createTransferPreapprovalMutation = useMutation({
    mutationFn: async () => {
      return await createTransferPreapproval().then(async () => transferPreapprovalQuery.refetch());
    },
    onSuccess: () => {
      navigate('/transactions');
    },
    onError: error => {
      // TODO (#5491): show an error to the user.
      console.error(`Failed to create transfer preapproval`, error);
    },
  });

  if (transferPreapprovalQuery.isLoading) {
    return <></>;
  }

  if (transferPreapprovalQuery.data) {
    return (
      <Tooltip
        title={
          <div style={{ textAlign: 'center' }}>
            INCOMING TRANSFERS PRE-APPROVED.
            <br />
            Contact validator admin to disable.
          </div>
        }
      >
        <ApprovalIcon id="transfer-preapproval-status" />
      </Tooltip>
    );
  } else {
    return (
      <DisableConditionally
        conditions={[
          {
            disabled: createTransferPreapprovalMutation.isLoading,
            reason: 'Loading...',
          },
        ]}
      >
        <Tooltip
          title={
            <div style={{ textAlign: 'center' }}>
              You will need to contact your validator admin to undo this.
            </div>
          }
        >
          <span>
            <Button
              variant="contained"
              color="info"
              onClick={() => createTransferPreapprovalMutation.mutate()}
              id="create-transfer-preapproval"
              sx={{ textWrap: 'balance' }}
            >
              Pre-approve all incoming transfers
            </Button>
          </span>
        </Tooltip>
      </DisableConditionally>
    );
  }
};

export default TransferPreapproval;

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ConfirmationDialog,
  DisableConditionally,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { useMutation } from '@tanstack/react-query';
import React, { useState } from 'react';
import { useNavigate } from 'react-router';

import { Button, Tooltip, Typography } from '@mui/material';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { usePrimaryParty } from '../hooks';
import useLookupTransferPreapproval from '../hooks/scan-proxy/useLookupTransferPreapproval';
import { useWalletConfig } from '../utils/config';

const TransferPreapproval: React.FC = () => {
  const primaryPartyId = usePrimaryParty();
  const transferPreapprovalQuery = useLookupTransferPreapproval(primaryPartyId);
  const config = useWalletConfig();
  const { createTransferPreapproval } = useWalletClient();
  const navigate = useNavigate();
  const [confirmationDialogOpen, setConfirmationDialogOpen] = useState<boolean>(false);

  const createTransferPreapprovalMutation = useMutation({
    mutationFn: async () => {
      return await createTransferPreapproval().then(async () => transferPreapprovalQuery.refetch());
    },
    onSuccess: () => {
      navigate('/transactions');
    },
    onError: error => {
      // TODO (DACH-NY/canton-network-node#5491): show an error to the user.
      console.error(`Failed to create transfer preapproval`, error);
    },
  });

  const handleConfirmationAccept = () => {
    createTransferPreapprovalMutation.mutate();
    setConfirmationDialogOpen(false);
  };

  const handleConfirmationClose = () => {
    setConfirmationDialogOpen(false);
  };

  if (transferPreapprovalQuery.isLoading) {
    return <></>;
  }

  const button = (
    <span>
      <Button
        variant="contained"
        color="info"
        onClick={() => setConfirmationDialogOpen(true)}
        id="create-transfer-preapproval"
        sx={{ textWrap: 'balance' }}
        disabled={!!transferPreapprovalQuery.data}
        size="small"
      >
        Pre-approve incoming direct transfers of {config.spliceInstanceNames.amuletName}
      </Button>
    </span>
  );

  const buttonWithTooltip = createTransferPreapprovalMutation.data ? (
    <Tooltip title="Pre-approval of incoming direct transfers to this party are enabled, contact your validator operator to disable.">
      {button}
    </Tooltip>
  ) : (
    button
  );

  return (
    <DisableConditionally
      conditions={[
        {
          disabled: createTransferPreapprovalMutation.isPending,
          reason: 'Loading...',
        },
      ]}
    >
      <div>
        {buttonWithTooltip}
        <ConfirmationDialog
          showDialog={confirmationDialogOpen}
          onAccept={handleConfirmationAccept}
          onClose={handleConfirmationClose}
          title="Pre-approve incoming direct transfers"
          attributePrefix="preapproval"
        >
          <Typography variant="h5">
            Are you sure you want to pre-approve direct transfers of{' '}
            {config.spliceInstanceNames.amuletName} to this party? To disable it later, you need to
            contact your validator operator.
          </Typography>
        </ConfirmationDialog>
      </div>
    </DisableConditionally>
  );
};

export default TransferPreapproval;

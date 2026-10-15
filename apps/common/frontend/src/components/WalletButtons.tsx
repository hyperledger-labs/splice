// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useState } from 'react';

import { Alert, Box, Button, ButtonProps, Typography } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment/module';
import { SubscriptionRequest } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Subscriptions/module';
import { ContractId } from '@daml/types';

interface Props<T> extends ButtonProps {
  text: string;
  createPaymentRequest: () => Promise<ContractId<T>>;
  redirectPath?: string;
  walletPath: string;
}

const WalletButton = <T,>(props: Props<T>, walletPage: string) => {
  const { text, createPaymentRequest, walletPath, redirectPath, ...buttonProps } = props;
  const [clicked, setClicked] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const onClick = () => {
    setClicked(true);
    setError(undefined);
    createPaymentRequest()
      .then(cid => {
        const here = window.location.origin.toString();
        const redirectTo = encodeURIComponent(here + (redirectPath || ''));
        window.location.assign(`${walletPath}/${walletPage}/${cid}/?redirect=${redirectTo}`);
      })
      .catch(err => {
        console.error('Failed to create payment request', err);
        setError(err instanceof Error ? err.message : String(err));
        setClicked(false);
      });
  };

  return (
    <Box>
      <Button {...buttonProps} onClick={onClick} disabled={clicked}>
        <Typography variant="body1" textTransform="none">
          {text}
        </Typography>
      </Button>
      {error && (
        <Alert
          severity="error"
          className="wallet-button-error"
          onClose={() => setError(undefined)}
          sx={{ mt: 1 }}
        >
          {error}
        </Alert>
      )}
    </Box>
  );
};

export const TransferButton: (props: Props<AppPaymentRequest>) => JSX.Element = (
  props: Props<AppPaymentRequest>
) => WalletButton(props, 'confirm-payment');

export const SubscriptionButton: (props: Props<SubscriptionRequest>) => JSX.Element = (
  props: Props<SubscriptionRequest>
) => WalletButton(props, 'confirm-subscription');

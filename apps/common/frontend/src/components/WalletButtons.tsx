import { Button, ButtonProps, Typography } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment/module';
import { SubscriptionRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions/module';
import { ContractId } from '@daml/types';

interface Props<T> extends ButtonProps {
  text: string;
  createPaymentRequest: () => Promise<ContractId<T>>;
  redirectPath?: string;
  walletPath: string;
}

const WalletButton = <T,>(props: Props<T>, walletPage: string) => {
  const { text, createPaymentRequest, walletPath, redirectPath, ...buttonProps } = props;

  const onClick = async () => {
    const cid = await createPaymentRequest();
    const here = window.location.origin.toString();
    const redirectTo = encodeURIComponent(here + (redirectPath || ''));
    window.location.assign(`${walletPath}/${walletPage}/${cid}/?redirect=${redirectTo}`);
  };

  return (
    <Button {...buttonProps} onClick={onClick}>
      <Typography variant="body1" textTransform="none">
        {text}
      </Typography>
    </Button>
  );
};

export const TransferButton: (props: Props<AppPaymentRequest>) => JSX.Element = (
  props: Props<AppPaymentRequest>
) => WalletButton(props, 'confirm-payment');
export const SubscriptionButton: (props: Props<SubscriptionRequest>) => JSX.Element = (
  props: Props<SubscriptionRequest>
) => WalletButton(props, 'confirm-subscription');

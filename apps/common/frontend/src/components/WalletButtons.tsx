import { Button, ButtonProps } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment/module';
import { SubscriptionRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions/module';
import { ContractId } from '@daml/types';

interface Props<T> extends ButtonProps {
  text: string;
  createPaymentRequest: () => Promise<ContractId<T>>;
  walletPath: string;
}

const WalletButton = <T,>(props: Props<T>, walletPage: string) => {
  const onClick = async () => {
    const cid = await props.createPaymentRequest();
    const here = window.location.origin.toString();
    window.location.assign(
      `${props.walletPath}/${walletPage}/${cid}/?redirect=${encodeURIComponent(here)}`
    );
  };

  return (
    <Button className={props.className} id={props.id} onClick={onClick}>
      {props.text}
    </Button>
  );
};

export const TransferButton: (props: Props<AppPaymentRequest>) => JSX.Element = (
  props: Props<AppPaymentRequest>
) => WalletButton(props, 'app-payment-requests');
export const SubscriptionButton: (props: Props<SubscriptionRequest>) => JSX.Element = (
  props: Props<SubscriptionRequest>
) => WalletButton(props, 'subscriptions');

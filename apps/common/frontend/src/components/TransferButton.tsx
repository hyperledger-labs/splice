import { Button } from '@mui/material';

import { AppPaymentRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
import { ContractId } from '@daml/types';

interface Props {
  className: string;
  text: string;
  createPaymentRequest: () => Promise<ContractId<AppPaymentRequest>>;
  walletPath: string;
}

const TransferButton: React.FC<Props> = props => {
  const onClick = async () => {
    const cid = await props.createPaymentRequest();
    const here = window.location.origin.toString();
    window.location.assign(
      `${props.walletPath}/app-payment-requests/${cid}/?redirect=${encodeURIComponent(here)}`
    );
  };

  return (
    <Button className={props.className} onClick={onClick}>
      {props.text}
    </Button>
  );
};

export default TransferButton;

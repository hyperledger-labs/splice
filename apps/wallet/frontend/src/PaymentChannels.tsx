import { PaymentChannelProposal } from "@daml.js/wallet/lib/CN/Wallet";
import { Button, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from "@mui/material";
import React, { useCallback, useState } from "react";
import { AcceptPaymentChannelProposalRequest, ExecuteDirectTransferRequest, ListPaymentChannelProposalsRequest, ProposePaymentChannelRequest, WalletContext } from "./com/daml/network/wallet/v0/wallet_service_pb";
import { Contract } from "./Contract";
import { useInterval } from "./Util";
import { useWalletClient } from "./WalletServiceContext";

const PaymentChannels: React.FC<{userId: string}> = ({ userId }) => {
    const walletClient = useWalletClient();
    const walletRequestCtx = new WalletContext().setUserId(userId);

    const [proposals, setProposals] = useState<Contract<PaymentChannelProposal>[]>([]);
    const fetchChannelProposals = useCallback(async () => {
        const proposalList = (await walletClient.listPaymentChannelProposals(new ListPaymentChannelProposalsRequest().setWalletCtx(walletRequestCtx), null)).getProposalsList();
        setProposals(proposalList.map(c => Contract.decode(c, PaymentChannelProposal)));
    }, [walletClient, walletRequestCtx, setProposals]);
    useInterval(fetchChannelProposals, 500);

    const [receiver, setReceiver] = useState<string>("");
    const proposeChannel = async (ev: React.FormEvent<HTMLFormElement>) => {
        ev.preventDefault();
        await walletClient.proposePaymentChannel(
            new ProposePaymentChannelRequest()
                .setReceiverPartyId(receiver)
                .setAllowDirectTransfers(true)
                .setAllowOffers(true)
                .setAllowRequests(true)
                .setSenderTransferFeeRatio("0.5")
                .setWalletCtx(walletRequestCtx),
            null);
        setReceiver("");
    };
    const approveChannel = async (cid: string) => {
        await walletClient.acceptPaymentChannelProposal(new AcceptPaymentChannelProposalRequest().setProposalContractId(cid).setWalletCtx(walletRequestCtx), null);
    };

    const [transferRequest, setTransferRequest] = useState(new ExecuteDirectTransferRequest());
    const directTransfer = async (ev: React.FormEvent<HTMLFormElement>) => {
        ev.preventDefault();
        await walletClient.executeDirectTransfer(transferRequest.setWalletCtx(walletRequestCtx), null);
    };
    return <Stack spacing={2}>
        <form onSubmit={directTransfer}>
            <Stack direction="row">
                <TextField label="Receiver" value={transferRequest.getReceiverPartyId()} onChange={(event) => setTransferRequest(prev => prev.setReceiverPartyId(event.target.value))}></TextField>
                <TextField label="Input coin" value={transferRequest.getCoinContractId()} onChange={(event) => setTransferRequest(prev => prev.setCoinContractId(event.target.value))}></TextField>
                <TextField label="Amount" value={transferRequest.getQuantity()} onChange={(event) => setTransferRequest(prev => prev.setQuantity(event.target.value))}></TextField>
                <Button variant="contained" type="submit">
                    Transfer over channel
                </Button>
            </Stack>
        </form>
        <form onSubmit={proposeChannel}>
            <Stack direction="row">
                <TextField label="Receiver" value={receiver} onChange={(event) => setReceiver(event.target.value)}></TextField>
                <Button variant="contained" type="submit">
                    Propose channel
                </Button>
            </Stack>
        </form>
        <Typography variant="h4">Channel proposals</Typography>
        <Table>
            <TableHead>
                <TableRow>
                    <TableCell>
                        Proposal from
                    </TableCell>
                </TableRow>
            </TableHead>
            <TableBody>
                {proposals.map(c =>
                    <TableRow key={c.contractId}>
                        <TableCell>
                            {c.payload.proposer}
                        </TableCell>
                        <TableCell>
                            <Button onClick={() => approveChannel(c.contractId)}>
                                Approve
                            </Button>
                        </TableCell>
                    </TableRow>
                )
                }
            </TableBody>
        </Table>
    </Stack>
};

export default PaymentChannels;

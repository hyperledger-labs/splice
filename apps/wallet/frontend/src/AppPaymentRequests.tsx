import { AppPaymentRequest } from "@daml.js/wallet/lib/CN/Wallet";
import { Button, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField } from "@mui/material";
import { Empty } from "google-protobuf/google/protobuf/empty_pb";
import React, { useCallback, useState } from "react";
import { AcceptAppPaymentRequestRequest } from "./com/daml/network/wallet/v0/wallet_service_pb";
import { Contract } from "./Contract";
import { sameContracts, useInterval } from "./Util";
import { useWalletClient } from "./WalletServiceContext";

const AppPaymentRequests: React.FC<{}> = () => {
    const walletClient = useWalletClient();
    const [appPaymentRequests, setAppPaymentRequests] = useState<Contract<AppPaymentRequest>[]>([]);
    const fetchAppPaymentRequests = useCallback(async () => {
        const newAppPaymentRequests = (await walletClient.listAppPaymentRequests(new Empty(), null)).getPaymentRequestsList();
        const decoded = newAppPaymentRequests.map(c => Contract.decode(c, AppPaymentRequest));
        setAppPaymentRequests((prev) => sameContracts(decoded, prev) ? prev : decoded);
    }, [walletClient, setAppPaymentRequests]);
    useInterval(fetchAppPaymentRequests, 500);

    const Request: React.FC<{ request: Contract<AppPaymentRequest> }> = ({ request }) => {
        const [coinId, setCoinId] = useState<string>("");
        const onAccept = async () => {
            await walletClient.acceptAppPaymentRequest(new AcceptAppPaymentRequestRequest().setCoinContractId(coinId).setRequestContractId(request.contractId), null);
        };
        return <TableRow>
            <TableCell>{request.payload.receiver}</TableCell>
            <TableCell>{request.payload.quantity}</TableCell>
            <TableCell>
                <TextField value={coinId} onChange={(ev) => setCoinId(ev.target.value)} label="Coin Contract ID" />
            </TableCell>
            <TableCell>
                <Button type="submit" onClick={onAccept}>Accept</Button>
            </TableCell>
        </TableRow>
    }

    return <Stack spacing={2}>
        <Table>
            <TableHead>
                <TableRow>
                    <TableCell>
                        Receiver
                    </TableCell>
                    <TableCell>
                        Quantity
                    </TableCell>
                </TableRow>
            </TableHead>
            <TableBody>
                {appPaymentRequests.map(c => <Request request={c} key={c.contractId} />)}
            </TableBody>
        </Table>
    </Stack>
};

export default AppPaymentRequests;

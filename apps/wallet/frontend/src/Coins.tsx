import { Coin } from "@daml.js/canton-coin/lib/CC/Coin";
import { Button, FormGroup, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField } from "@mui/material";
import { useCallback, useState } from "react";
import { ListRequest, TapRequest } from "./com/daml/network/wallet/v0/wallet_service_pb";
import { Contract } from "./Contract";
import { sameContracts, useInterval } from "./Util";
import { useWalletClient } from "./WalletServiceContext";

const Coins: React.FC<{}> = () => {
    const [coins, setCoins] = useState<Contract<Coin>[]>([]);
    const [tapValue, setTapValue] = useState<string>("");

    const walletClient = useWalletClient();

    const fetchCoins = useCallback(async () => {
        // TODO(i680)
        const newCoins = (await walletClient.list(new ListRequest(), null)).getCoinsList();
        const decoded = newCoins.map(c => Contract.decode(c, Coin));
        setCoins((prev) => sameContracts(prev, decoded) ? prev : decoded);
    }, [walletClient, setCoins]);

    useInterval(fetchCoins, 500);

    const onTap = async () => {
        await walletClient.tap(new TapRequest().setQuantity(tapValue), null);
    };

    const copyToClipboard = async (text: string) => {
        await navigator.clipboard.writeText(text);
    }

    return <Stack spacing={2}>
        <FormGroup row={true}>
            <TextField label="Amount" value={tapValue} onChange={(event) => setTapValue(event.target.value)}></TextField>
            <Button variant="contained" onClick={() => onTap()}>
                Tap
            </Button>
        </FormGroup>
        <Table>
            <TableHead>
                <TableRow>
                    <TableCell>Contract ID</TableCell>
                    <TableCell>Initial quantity</TableCell>
                    <TableCell>Created at</TableCell>
                </TableRow>
            </TableHead>
            <TableBody>
                {coins.map(c =>
                    <TableRow key={c.contractId}>
                        <TableCell>
                            <Button variant="text" sx={{ color: 'text.primary', fontWeight: 'regular' }} onClick={() => copyToClipboard(c.contractId)}>{c.contractId.slice(0, 10)}…</Button>
                        </TableCell>
                        <TableCell>
                            {c.payload.quantity.initialQuantity}
                        </TableCell>
                        <TableCell>
                            {c.payload.quantity.createdAt.number}
                        </TableCell>
                    </TableRow>
                )}
            </TableBody>
        </Table>
    </Stack>;
};

export default Coins;

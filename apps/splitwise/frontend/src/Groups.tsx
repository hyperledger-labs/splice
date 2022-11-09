import { Contract, sameContracts, useInterval } from 'common-frontend';
import { DirectoryEntry as DirectoryEntryComponent } from 'common-frontend';
import {
  GroupKey,
  ListAcceptedGroupInvitesRequest,
  ListBalancesRequest,
  ListBalanceUpdatesRequest,
  ListGroupsRequest,
  SplitwiseContext,
} from 'common-protobuf/com/daml/network/splitwise/v0/splitwise_service_pb';
import { Decimal } from 'decimal.js';
import { useCallback, useState } from 'react';

import {
  Autocomplete,
  Box,
  Button,
  Divider,
  List,
  ListItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  tableCellClasses,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';

import {
  AcceptedGroupInvite,
  BalanceUpdate,
  Group as CodegenGroup,
} from '@daml.js/splitwise/lib/CN/Splitwise';
import { ReceiverQuantity } from '@daml.js/wallet/lib/CN/Wallet';

import DirectoryEntries, { Entry as DirectoryEntry } from './DirectoryEntries';
import { useSplitwiseLedgerApiClient } from './contexts/SplitwiseLedgerApiContext';
import { useSplitwiseClient } from './contexts/SplitwiseServiceContext';
import { config } from './utils';

const key = (group: Contract<CodegenGroup>) =>
  new GroupKey()
    .setId(group.payload.id.unpack)
    .setOwnerPartyId(group.payload.owner)
    .setProviderPartyId(group.payload.provider);

interface BalancesProps {
  group: Contract<CodegenGroup>;
  party: string;
  provider: string;
}

const balanceEqual = (a: Map<string, string>, b: Map<string, string>): boolean => {
  if (a.size !== b.size) return false;
  for (let [k, va] of a) {
    const vb = b.get(k);
    if (va !== vb) {
      return false;
    }
  }
  return true;
};

const Balances: React.FC<BalancesProps> = ({ group, party, provider }) => {
  const splitwiseClient = useSplitwiseClient();
  const ledgerApiClient = useSplitwiseLedgerApiClient();
  const [balances, setBalances] = useState<Map<string, string>>(new Map());
  const fetchBalances = useCallback(async () => {
    const balanceMap = (
      await splitwiseClient.listBalances(
        new ListBalancesRequest()
          .setGroupKey(key(group))
          .setContext(new SplitwiseContext().setUserPartyId(party)),
        undefined
      )
    ).getBalancesMap();
    let balances = new Map<string, string>();
    [group.payload.owner].concat(group.payload.members).forEach(p => {
      if (p !== party) {
        const balance = balanceMap.get(p);
        if (balance) {
          balances.set(p, balance);
        } else {
          balances.set(p, '0.0');
        }
      }
    });
    setBalances(prev => (balanceEqual(prev, balances) ? prev : balances));
  }, [splitwiseClient, setBalances, group, party]);
  useInterval(fetchBalances, 500);
  const onSettleMyDebts = async () => {
    const quantities: ReceiverQuantity[] = Array.from(balances)
      .filter(([_, v]) => new Decimal(v).isNegative())
      .map(([k, v]) => {
        return { receiver: k, quantity: Decimal.abs(new Decimal(v)).toString() };
      });

    const cid = await ledgerApiClient.initiateTransfer(
      party,
      provider,
      group.contractId,
      quantities
    );
    const here = window.location.origin.toString();
    const walletPath = config.wallet.uiUrl;
    window.location.assign(
      `${walletPath}/app-payment-requests/${cid}/?redirect=${encodeURIComponent(here)}`
    );
  };
  return (
    <Stack>
      <Stack sx={{ px: 2, py: 1 }}>
        <Typography variant="button">Balances</Typography>
        <Table
          sx={{
            [`& .${tableCellClasses.root}`]: {
              borderBottom: 'none',
            },
          }}
        >
          <TableBody>
            {Array.from(balances).map(([party, balance]) => (
              <TableRow key={party} className="balances-table-row">
                <TableCell className="balances-table-receiver">
                  <DirectoryEntryComponent partyId={party} />
                </TableCell>
                <TableCell className="balances-table-quantity">{balance}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Stack>
      <Stack justifyContent="stretch">
        <Button className="settle-my-debts-link" onClick={onSettleMyDebts}>
          Settle My Debts
        </Button>
      </Stack>
    </Stack>
  );
};

interface MembershipRequestsProps {
  group: Contract<CodegenGroup>;
  provider: string;
  party: string;
}

const MembershipRequests: React.FC<MembershipRequestsProps> = ({ group, party, provider }) => {
  const splitwiseClient = useSplitwiseClient();
  const ledgerApiClient = useSplitwiseLedgerApiClient();
  const [acceptedInvites, setAcceptedInvites] = useState<Contract<AcceptedGroupInvite>[]>([]);
  const fetchAcceptedInvites = useCallback(async () => {
    const invites = (
      await splitwiseClient.listAcceptedGroupInvites(
        new ListAcceptedGroupInvitesRequest()
          .setGroupId(group.payload.id.unpack)
          .setContext(new SplitwiseContext().setUserPartyId(party)),
        undefined
      )
    ).getAcceptedGroupInvitesList();
    const decoded = invites.map(c => Contract.decode(c, AcceptedGroupInvite));
    setAcceptedInvites(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [group.payload.id.unpack, party, splitwiseClient]);
  useInterval(fetchAcceptedInvites, 500);
  const onAddMember = async (invite: Contract<AcceptedGroupInvite>) => {
    await ledgerApiClient.joinGroup(party, provider, group.contractId, invite.contractId);
  };
  return (
    <Box>
      <Stack sx={{ px: 2, py: 1 }}>
        <Typography variant="button">Membership Requests</Typography>
        <List>
          {acceptedInvites.map(invite => (
            <ListItem key={invite.contractId}>
              <Button className="add-user-link" onClick={() => onAddMember(invite)}>
                Add <DirectoryEntryComponent partyId={invite.payload.invitee} />
              </Button>
            </ListItem>
          ))}
        </List>
      </Stack>
      <Divider />
    </Box>
  );
};

interface EntryProps {
  directoryEntries: DirectoryEntries;
  group: Contract<CodegenGroup>;
  provider: string;
  party: string;
}

const Entry: React.FC<EntryProps> = ({ directoryEntries, group, party, provider }) => {
  const ledgerApiClient = useSplitwiseLedgerApiClient();
  const [paymentQuantity, setPaymentQuantity] = useState<string>('');
  const [paymentDescription, setPaymentDescription] = useState<string>('');
  const onEnterPayment = async () => {
    await ledgerApiClient.enterPayment(
      party,
      provider,
      group.contractId,
      paymentQuantity,
      paymentDescription
    );
  };
  const [transferQuantity, setTransferQuantity] = useState<string>('');
  const [transferReceiverEntry, setTransferReceiverEntry] = useState<DirectoryEntry | null>(null);
  const onInitiateTransfer = async () => {
    await ledgerApiClient.initiateTransfer(party, provider, group.contractId, [
      { receiver: transferReceiverEntry!.user, quantity: transferQuantity },
    ]);
  };
  return (
    <Stack>
      <Stack direction="row">
        <TextField
          label="Quantity"
          className="enter-payment-quantity-field"
          value={paymentQuantity}
          onChange={event => setPaymentQuantity(event.target.value)}
        ></TextField>
        <TextField
          label="Description"
          className="enter-payment-description-field"
          value={paymentDescription}
          onChange={event => setPaymentDescription(event.target.value)}
        ></TextField>
        <Button className="enter-payment-link" onClick={onEnterPayment}>
          Enter payment
        </Button>
      </Stack>
      <Stack direction="row" justifyContent="stretch">
        <TextField
          label="Quantity"
          className="transfer-quantity-field"
          value={transferQuantity}
          onChange={event => setTransferQuantity(event.target.value)}
        ></TextField>
        <Autocomplete<DirectoryEntry, false, false, true>
          sx={{ width: '38%' }}
          freeSolo
          options={directoryEntries.getAllEntries()}
          getOptionLabel={option => (typeof option === 'string' ? option : option.name)}
          value={transferReceiverEntry}
          onChange={(_, newValue) => {
            if (typeof newValue === 'string') {
              setTransferReceiverEntry({ user: newValue, name: newValue });
            } else {
              setTransferReceiverEntry(newValue);
            }
          }}
          renderInput={params => <TextField {...params} label="Receiver" />}
          className="transfer-receiver-field"
        />
        <Button className="transfer-link" onClick={onInitiateTransfer}>
          Transfer
        </Button>
      </Stack>
    </Stack>
  );
};

interface BalanceUpdatesProps {
  group: Contract<CodegenGroup>;
  party: string;
}

const BalanceUpdates: React.FC<BalanceUpdatesProps> = ({ group, party }) => {
  const splitwiseClient = useSplitwiseClient();
  const [balanceUpdates, setBalanceUpdates] = useState<Contract<BalanceUpdate>[]>([]);
  const fetchBalanceUpdates = useCallback(async () => {
    const balanceUpdates = (
      await splitwiseClient.listBalanceUpdates(
        new ListBalanceUpdatesRequest()
          .setGroupKey(key(group))
          .setContext(new SplitwiseContext().setUserPartyId(party)),
        undefined
      )
    ).getBalanceUpdatesList();
    const decoded = balanceUpdates.reverse().map(c => Contract.decode(c, BalanceUpdate));
    setBalanceUpdates(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [splitwiseClient, group, party]);
  useInterval(fetchBalanceUpdates, 500);

  const Update: React.FC<{ update: Contract<BalanceUpdate> }> = ({ update }) => {
    if (update.payload.update.tag === 'ExternalPayment') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <DirectoryEntryComponent partyId={value.payer} /> paid {value.quantity} {'CC for '}
          {value.description}
        </ListItem>
      );
    } else if (update.payload.update.tag === 'Transfer') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <DirectoryEntryComponent partyId={value.sender} /> sent {value.quantity} {'CC to '}
          <DirectoryEntryComponent partyId={value.receiver} />
        </ListItem>
      );
    } else {
      return <ListItem>Netting: Not yet implemented</ListItem>;
    }
  };
  return (
    <Stack sx={{ px: 2, py: 1 }}>
      <Typography variant="button">Balance Updates</Typography>
      <List>
        {balanceUpdates.map(c => (
          <Update key={c.contractId} update={c} />
        ))}
      </List>
    </Stack>
  );
};

interface GroupProps {
  directoryEntries: DirectoryEntries;
  group: Contract<CodegenGroup>;
  party: string;
  provider: string;
}

const Group: React.FC<GroupProps> = ({ directoryEntries, group, party, provider }) => {
  const ledgerApiClient = useSplitwiseLedgerApiClient();
  const isOwner = party === group.payload.owner;
  const onCreateInvite = async () => {
    await ledgerApiClient.createGroupInvite(
      party,
      provider,
      group.contractId,
      directoryEntries.getAllParties()
    );
  };

  return (
    <Paper elevation={3} sx={{ width: 600 }}>
      <Stack
        sx={{ px: 2, py: 1 }}
        direction="row"
        justifyContent="space-between"
        alignItems="center"
      >
        <Typography variant="button">{group.payload.id.unpack}</Typography>
        {isOwner && (
          <Button id="create-invite-link" onClick={onCreateInvite}>
            Create Invite
          </Button>
        )}
        <Typography variant="button">
          owned by <DirectoryEntryComponent partyId={group.payload.owner} />
        </Typography>
      </Stack>
      <Divider />
      {isOwner && <MembershipRequests group={group} party={party} provider={provider} />}
      <Entry group={group} directoryEntries={directoryEntries} party={party} provider={provider} />
      <Divider />
      <Balances group={group} party={party} provider={provider} />
      <Divider />
      <BalanceUpdates group={group} party={party} />
      <Divider />
    </Paper>
  );
};

interface GroupsProps {
  directoryEntries: DirectoryEntries;
  party: string;
  provider: string;
}

const Groups: React.FC<GroupsProps> = ({ directoryEntries, party, provider }) => {
  const splitwiseClient = useSplitwiseClient();

  const [groups, setGroups] = useState<Contract<CodegenGroup>[]>([]);

  const fetchGroups = useCallback(async () => {
    const newGroups = (
      await splitwiseClient.listGroups(
        new ListGroupsRequest().setContext(new SplitwiseContext().setUserPartyId(party)),
        undefined
      )
    ).getGroupsList();
    const decoded = newGroups.map(c => Contract.decode(c, CodegenGroup));
    setGroups(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [splitwiseClient, party]);

  useInterval(fetchGroups, 500);

  return (
    <Stack spacing={2}>
      {groups.map(group => (
        <Group
          key={`${group.payload.owner}:${group.payload.id}`}
          directoryEntries={directoryEntries}
          group={group}
          party={party}
          provider={provider}
        />
      ))}
    </Stack>
  );
};

export default Groups;

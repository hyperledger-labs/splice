import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
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
import { AcceptedAppPayment } from '@daml.js/wallet/lib/CN/Wallet';

import { Contract } from './Contract';
import DirectoryEntries, { Entry as DirectoryEntry } from './DirectoryEntries';
import { useSplitwiseClient } from './SplitwiseServiceContext';
import { sameContracts, useInterval } from './Util';
import {
  CompleteTransferRequest,
  CreateGroupInviteRequest,
  EnterPaymentRequest,
  GroupKey,
  InitiateTransferRequest,
  JoinGroupRequest,
  ListAcceptedAppPaymentsRequest,
  ListAcceptedGroupInvitesRequest,
  ListBalancesRequest,
  ListBalanceUpdatesRequest,
} from './com/daml/network/splitwise/v0/splitwise_service_pb';

const key = (group: Contract<CodegenGroup>) =>
  new GroupKey()
    .setId(group.payload.id.unpack)
    .setOwnerPartyId(group.payload.owner)
    .setProviderPartyId(group.payload.provider);

interface BalancesProps {
  directoryEntries: DirectoryEntries;
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

const Balances: React.FC<BalancesProps> = ({ directoryEntries, group, party }) => {
  const splitwiseClient = useSplitwiseClient();
  const [balances, setBalances] = useState<Map<string, string>>(new Map());
  const fetchBalances = useCallback(async () => {
    const balanceMap = (
      await splitwiseClient.listBalances(new ListBalancesRequest().setGroupKey(key(group)), null)
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
  return (
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
            <TableRow key={party}>
              <TableCell>{directoryEntries.resolveParty(party)}</TableCell>
              <TableCell>{balance}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Stack>
  );
};

interface MembershipRequestsProps {
  directoryEntries: DirectoryEntries;
  group: Contract<CodegenGroup>;
  provider: string;
}

const MembershipRequests: React.FC<MembershipRequestsProps> = ({
  directoryEntries,
  group,
  provider,
}) => {
  const splitwiseClient = useSplitwiseClient();
  const [acceptedInvites, setAcceptedInvites] = useState<Contract<AcceptedGroupInvite>[]>([]);
  const fetchAcceptedInvites = useCallback(async () => {
    const invites = (
      await splitwiseClient.listAcceptedGroupInvites(
        new ListAcceptedGroupInvitesRequest()
          .setGroupId(group.payload.id.unpack)
          .setProviderPartyId(provider),
        null
      )
    ).getAcceptedGroupInvitesList();
    const decoded = invites.map(c => Contract.decode(c, AcceptedGroupInvite));
    setAcceptedInvites(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [group, setAcceptedInvites, splitwiseClient, provider]);
  useInterval(fetchAcceptedInvites, 500);
  const onAddMember = async (invite: Contract<AcceptedGroupInvite>) => {
    await splitwiseClient.joinGroup(
      new JoinGroupRequest()
        .setAcceptedGroupInviteContractId(invite.contractId)
        .setProviderPartyId(provider),
      null
    );
  };
  return (
    <Box>
      <Stack sx={{ px: 2, py: 1 }}>
        <Typography variant="button">Membership Requests</Typography>
        <List>
          {acceptedInvites.map(invite => (
            <ListItem key={invite.contractId}>
              <Button onClick={() => onAddMember(invite)}>
                Add {directoryEntries.resolveParty(invite.payload.invitee)}
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
}

const Entry: React.FC<EntryProps> = ({ directoryEntries, group, provider }) => {
  const splitwiseClient = useSplitwiseClient();
  const [paymentQuantity, setPaymentQuantity] = useState<string>('');
  const [paymentDescription, setPaymentDescription] = useState<string>('');
  const onEnterPayment = async () => {
    await splitwiseClient.enterPayment(
      new EnterPaymentRequest()
        .setDescription(paymentDescription)
        .setQuantity(paymentQuantity)
        .setGroupKey(key(group))
        .setProviderPartyId(provider),
      null
    );
  };
  const [transferQuantity, setTransferQuantity] = useState<string>('');
  const [transferReceiverEntry, setTransferReceiverEntry] = useState<DirectoryEntry | null>(null);
  const onInitiateTransfer = async () => {
    await splitwiseClient.initiateTransfer(
      new InitiateTransferRequest()
        .setReceiverPartyId(transferReceiverEntry!.user)
        .setQuantity(transferQuantity)
        .setGroupKey(key(group))
        .setProviderPartyId(provider),
      null
    );
  };
  return (
    <Stack>
      <Stack direction="row">
        <TextField
          label="Quantity"
          value={paymentQuantity}
          onChange={event => setPaymentQuantity(event.target.value)}
        ></TextField>
        <TextField
          label="Description"
          value={paymentDescription}
          onChange={event => setPaymentDescription(event.target.value)}
        ></TextField>
        <Button onClick={onEnterPayment}>Enter payment</Button>
      </Stack>
      <Stack direction="row" justifyContent="stretch">
        <TextField
          label="Quantity"
          value={transferQuantity}
          onChange={event => setTransferQuantity(event.target.value)}
        ></TextField>
        <Autocomplete<DirectoryEntry, false, false, true>
          sx={{ width: '38%' }}
          freeSolo
          options={directoryEntries.getAllEntries()}
          getOptionLabel={option => (typeof option === 'string' ? option : option.name)}
          value={transferReceiverEntry}
          onChange={(e, newValue) => {
            if (typeof newValue === 'string') {
              setTransferReceiverEntry({ user: newValue, name: newValue });
            } else {
              setTransferReceiverEntry(newValue);
            }
          }}
          renderInput={params => <TextField {...params} label="Receiver" />}
        />
        <Button onClick={onInitiateTransfer}>Transfer</Button>
      </Stack>
    </Stack>
  );
};

interface BalanceUpdatesProps {
  directoryEntries: DirectoryEntries;
  group: Contract<CodegenGroup>;
}

const BalanceUpdates: React.FC<BalanceUpdatesProps> = ({ directoryEntries, group }) => {
  const splitwiseClient = useSplitwiseClient();
  const [balanceUpdates, setBalanceUpdates] = useState<Contract<BalanceUpdate>[]>([]);
  const fetchBalanceUpdates = useCallback(async () => {
    const balanceUpdates = (
      await splitwiseClient.listBalanceUpdates(
        new ListBalanceUpdatesRequest().setGroupKey(key(group)),
        null
      )
    ).getBalanceUpdatesList();
    const decoded = balanceUpdates.reverse().map(c => Contract.decode(c, BalanceUpdate));
    setBalanceUpdates(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [splitwiseClient, setBalanceUpdates, group]);
  useInterval(fetchBalanceUpdates, 500);

  const Update: React.FC<{ update: Contract<BalanceUpdate> }> = ({ update }) => {
    if (update.payload.update.tag === 'ExternalPayment') {
      const value = update.payload.update.value;
      return (
        <ListItem>
          {directoryEntries.resolveParty(value.payer)} payed {value.quantity} CC for{' '}
          {value.description}
        </ListItem>
      );
    } else if (update.payload.update.tag === 'Transfer') {
      const value = update.payload.update.value;
      return (
        <ListItem>
          {directoryEntries.resolveParty(value.sender)} sent {value.quantity} CC to{' '}
          {directoryEntries.resolveParty(value.receiver)}
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

interface AcceptedAppPaymentsProps {
  directoryEntries: DirectoryEntries;
  group: Contract<CodegenGroup>;
  provider: string;
}

const AcceptedAppPayments: React.FC<AcceptedAppPaymentsProps> = ({
  directoryEntries,
  group,
  provider,
}) => {
  const splitwiseClient = useSplitwiseClient();
  const [acceptedAppPayments, setAcceptedAppPayments] = useState<Contract<AcceptedAppPayment>[]>(
    []
  );
  const fetchAcceptedAppPayments = useCallback(async () => {
    const acceptedAppPayments = (
      await splitwiseClient.listAcceptedAppPayments(
        new ListAcceptedAppPaymentsRequest().setGroupKey(key(group)),
        null
      )
    ).getAcceptedAppPaymentsList();
    const decoded = acceptedAppPayments.map(c => Contract.decode(c, AcceptedAppPayment));
    setAcceptedAppPayments(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [splitwiseClient, setAcceptedAppPayments, group]);
  useInterval(fetchAcceptedAppPayments, 500);

  const onRedeem = async (acceptedAppPayment: Contract<AcceptedAppPayment>) => {
    await splitwiseClient.completeTransfer(
      new CompleteTransferRequest()
        .setGroupKey(key(group))
        .setAcceptedAppPaymentContractId(acceptedAppPayment.contractId)
        .setProviderPartyId(provider),
      null
    );
  };

  const AcceptedPayment: React.FC<{ acceptedAppPayment: Contract<AcceptedAppPayment> }> = ({
    acceptedAppPayment,
  }) => {
    return (
      <ListItem>
        Accepted transfer to {directoryEntries.resolveParty(acceptedAppPayment.payload.receiver)}
        <Button onClick={() => onRedeem(acceptedAppPayment)}>Redeem</Button>
      </ListItem>
    );
  };
  return (
    <Stack sx={{ px: 2, py: 1 }}>
      <Typography variant="button">Open transfers</Typography>
      <List>
        {acceptedAppPayments.map(acceptedAppPayment => (
          <AcceptedPayment
            acceptedAppPayment={acceptedAppPayment}
            key={acceptedAppPayment.contractId}
          />
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
  const splitwiseClient = useSplitwiseClient();
  const isOwner = party === group.payload.owner;
  const onCreateInvite = async () => {
    await splitwiseClient.createGroupInvite(
      new CreateGroupInviteRequest()
        .setObserverPartyIdsList(directoryEntries.getAllParties())
        .setGroupId(group.payload.id.unpack)
        .setProviderPartyId(provider),
      null
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
        {isOwner && <Button onClick={onCreateInvite}>Create Invite</Button>}
        <Typography variant="button">
          owned by {directoryEntries.resolveParty(group.payload.owner)}
        </Typography>
      </Stack>
      <Divider />
      {isOwner && (
        <MembershipRequests group={group} directoryEntries={directoryEntries} provider={provider} />
      )}
      <Entry group={group} directoryEntries={directoryEntries} provider={provider} />
      <Divider />
      <AcceptedAppPayments group={group} directoryEntries={directoryEntries} provider={provider} />
      <Divider />
      <Balances
        group={group}
        directoryEntries={directoryEntries}
        party={party}
        provider={provider}
      />
      <Divider />
      <BalanceUpdates directoryEntries={directoryEntries} group={group} />
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
    const newGroups = (await splitwiseClient.listGroups(new Empty(), null)).getGroupsList();
    const decoded = newGroups.map(c => Contract.decode(c, CodegenGroup));
    setGroups(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [splitwiseClient, setGroups]);

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

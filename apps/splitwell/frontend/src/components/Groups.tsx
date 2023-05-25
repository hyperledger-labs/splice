import {
  Contract,
  sameContracts,
  useInterval,
  DirectoryEntry as DirectoryEntryComponent,
  DirectoryField,
} from 'common-frontend';
import { TransferButton } from 'common-frontend/lib/components/WalletButtons';
import {
  GroupKey,
  ListAcceptedGroupInvitesRequest,
  ListBalancesRequest,
  ListBalanceUpdatesRequest,
  ListGroupsRequest,
  SplitwellContext,
} from 'common-protobuf/com/daml/network/splitwell/v0/splitwell_service_pb';
import { Decimal } from 'decimal.js';
import { useCallback, useState } from 'react';

import {
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
} from '@daml.js/splitwell/lib/CN/Splitwell';
import { ReceiverCCAmount } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';

import { useSplitwellLedgerApiClient } from '../contexts/SplitwellLedgerApiContext';
import { useSplitwellClient } from '../contexts/SplitwellServiceContext';
import { config } from '../utils/config';

const key = (group: Contract<CodegenGroup>) =>
  new GroupKey()
    .setId(group.payload.id.unpack)
    .setOwnerPartyId(group.payload.owner)
    .setProviderPartyId(group.payload.provider);

interface BalancesProps {
  group: Contract<CodegenGroup>;
  party: string;
  provider: string;
  domainId: string;
}

const balanceEqual = (a: Map<string, string>, b: Map<string, string>): boolean => {
  if (a.size !== b.size) {
    return false;
  }
  for (let [k, va] of a) {
    const vb = b.get(k);
    if (va !== vb) {
      return false;
    }
  }
  return true;
};

const Balances: React.FC<BalancesProps> = ({ group, party, provider, domainId }) => {
  const splitwellClient = useSplitwellClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const [balances, setBalances] = useState<Map<string, string>>(new Map());
  const fetchBalances = useCallback(async () => {
    const balanceMap = (
      await splitwellClient.listBalances(
        new ListBalancesRequest()
          .setGroupKey(key(group))
          .setContext(new SplitwellContext().setUserPartyId(party)),
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
  }, [splitwellClient, setBalances, group, party]);
  useInterval(fetchBalances);

  const initiateSettleDebts = async () => {
    const amounts: ReceiverCCAmount[] = Array.from(balances)
      .filter(([_, v]) => new Decimal(v).isNegative())
      .map(([k, v]) => {
        return { receiver: k, ccAmount: Decimal.abs(new Decimal(v)).toString() };
      });

    return await ledgerApiClient.initiateTransfer(
      party,
      provider,
      group.contractId,
      amounts,
      domainId
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
                <TableCell className="balances-table-amount">{balance}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Stack>
      <Stack justifyContent="stretch">
        <TransferButton
          className="settle-my-debts-link"
          text="Settle My Debts"
          createPaymentRequest={initiateSettleDebts}
          walletPath={config.services.wallet.uiUrl}
        ></TransferButton>
      </Stack>
    </Stack>
  );
};

interface MembershipRequestsProps {
  group: Contract<CodegenGroup>;
  provider: string;
  party: string;
  domainId: string;
}

const MembershipRequests: React.FC<MembershipRequestsProps> = ({
  group,
  party,
  provider,
  domainId,
}) => {
  const splitwellClient = useSplitwellClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const [acceptedInvites, setAcceptedInvites] = useState<Contract<AcceptedGroupInvite>[]>([]);
  const fetchAcceptedInvites = useCallback(async () => {
    const invites = (
      await splitwellClient.listAcceptedGroupInvites(
        new ListAcceptedGroupInvitesRequest()
          .setGroupId(group.payload.id.unpack)
          .setContext(new SplitwellContext().setUserPartyId(party)),
        undefined
      )
    ).getAcceptedGroupInvitesList();
    const decoded = invites.map(c => Contract.decode(c, AcceptedGroupInvite));
    setAcceptedInvites(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [group.payload.id.unpack, party, splitwellClient]);
  useInterval(fetchAcceptedInvites);

  const onAddMember = async (invite: Contract<AcceptedGroupInvite>) => {
    await ledgerApiClient.joinGroup(party, provider, group.contractId, invite.contractId, domainId);
  };
  return (
    <Box>
      <Stack sx={{ px: 2, py: 1 }}>
        <Typography variant="button">Membership Requests</Typography>
        <List>
          {acceptedInvites.map(invite => (
            <ListItem key={invite.contractId}>
              <Button
                data-group={group.payload.id.unpack}
                data-invitee={invite.payload.invitee}
                className="add-user-link"
                onClick={() => onAddMember(invite)}
              >
                Add
              </Button>
              <DirectoryEntryComponent partyId={invite.payload.invitee} noCopy />
            </ListItem>
          ))}
        </List>
      </Stack>
      <Divider />
    </Box>
  );
};

interface EntryProps {
  group: Contract<CodegenGroup>;
  provider: string;
  party: string;
  domainId: string;
}

const Entry: React.FC<EntryProps> = ({ group, party, provider, domainId }) => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const [paymentAmount, setPaymentAmount] = useState<string>('');
  const [paymentDescription, setPaymentDescription] = useState<string>('');
  const onEnterPayment = async () => {
    await ledgerApiClient.enterPayment(
      party,
      provider,
      group.contractId,
      paymentAmount,
      paymentDescription,
      domainId
    );
  };
  const [transferAmount, setTransferAmount] = useState<string>('');
  const [transferReceiver, setTransferReceiver] = useState<string | undefined>();
  const initiateTransfer = async () => {
    const members = [group.payload.owner, ...group.payload.members];
    if (!transferReceiver) {
      throw new Error('Transfer receiver not set');
    } else if (members.includes(transferReceiver)) {
      return await ledgerApiClient.initiateTransfer(
        party,
        provider,
        group.contractId,
        [{ receiver: transferReceiver, ccAmount: transferAmount }],
        domainId
      );
    } else {
      throw new Error(
        `Transfer receiver ${transferReceiver} is not part of the group’s members: ${members}`
      );
    }
  };
  return (
    <div
      className="group-entry"
      data-group-owner={group.payload.owner}
      data-group-id={group.payload.id.unpack}
    >
      <Stack>
        <Stack direction="row">
          <TextField
            label="Amount"
            className="enter-payment-amount-field"
            value={paymentAmount}
            onChange={event => setPaymentAmount(event.target.value)}
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
            label="Amount"
            className="transfer-amount-field"
            value={transferAmount}
            onChange={event => setTransferAmount(event.target.value)}
          ></TextField>
          <DirectoryField
            className="transfer-receiver-field"
            onPartyChanged={party => setTransferReceiver(party)}
          />
          <TransferButton
            className="transfer-link"
            text="Transfer"
            createPaymentRequest={initiateTransfer}
            walletPath={config.services.wallet.uiUrl}
          ></TransferButton>
        </Stack>
      </Stack>
    </div>
  );
};

interface BalanceUpdatesProps {
  group: Contract<CodegenGroup>;
  party: string;
}

const BalanceUpdates: React.FC<BalanceUpdatesProps> = ({ group, party }) => {
  const splitwellClient = useSplitwellClient();
  const [balanceUpdates, setBalanceUpdates] = useState<Contract<BalanceUpdate>[]>([]);
  const fetchBalanceUpdates = useCallback(async () => {
    const balanceUpdates = (
      await splitwellClient.listBalanceUpdates(
        new ListBalanceUpdatesRequest()
          .setGroupKey(key(group))
          .setContext(new SplitwellContext().setUserPartyId(party)),
        undefined
      )
    ).getBalanceUpdatesList();
    const decoded = balanceUpdates.reverse().map(c => Contract.decode(c, BalanceUpdate));
    setBalanceUpdates(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [splitwellClient, group, party]);
  useInterval(fetchBalanceUpdates);

  const Update: React.FC<{ update: Contract<BalanceUpdate> }> = ({ update }) => {
    if (update.payload.update.tag === 'ExternalPayment') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <DirectoryEntryComponent partyId={value.payer} /> paid {value.amount} {'CC for '}
          {value.description}
        </ListItem>
      );
    } else if (update.payload.update.tag === 'Transfer') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <DirectoryEntryComponent partyId={value.sender} /> sent {value.amount} {'CC to '}
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
  group: Contract<CodegenGroup>;
  party: string;
  provider: string;
  domainId: string;
}

const Group: React.FC<GroupProps> = ({ group, party, provider, domainId }) => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const isOwner = party === group.payload.owner;
  const onCreateInvite = async () => {
    await ledgerApiClient.createGroupInvite(party, provider, group.contractId, domainId);
  };

  return (
    <Paper elevation={3} sx={{ width: 600 }}>
      <div className="data-group-contract-id" style={{ display: 'none' }}>
        {group.contractId}
      </div>
      <Stack
        sx={{ px: 2, py: 1 }}
        direction="row"
        justifyContent="space-between"
        alignItems="center"
      >
        <Typography variant="button" className="group-name">
          {group.payload.id.unpack}
        </Typography>
        {isOwner && (
          <Button
            data-group={group.payload.id.unpack}
            className="create-invite-link"
            onClick={onCreateInvite}
          >
            Create Invite
          </Button>
        )}
        <Typography variant="button">
          owned by <DirectoryEntryComponent partyId={group.payload.owner} />
        </Typography>
      </Stack>
      <Divider />
      {isOwner && (
        <MembershipRequests group={group} party={party} provider={provider} domainId={domainId} />
      )}
      <Entry group={group} party={party} provider={provider} domainId={domainId} />
      <Divider />
      <Balances group={group} party={party} provider={provider} domainId={domainId} />
      <Divider />
      <BalanceUpdates group={group} party={party} />
      <Divider />
    </Paper>
  );
};

interface GroupsProps {
  party: string;
  provider: string;
  domainId: string;
}

const Groups: React.FC<GroupsProps> = ({ party, provider, domainId }) => {
  const splitwellClient = useSplitwellClient();

  const [groups, setGroups] = useState<Contract<CodegenGroup>[]>([]);

  const fetchGroups = useCallback(async () => {
    const newGroups = (
      await splitwellClient.listGroups(
        new ListGroupsRequest().setContext(new SplitwellContext().setUserPartyId(party)),
        undefined
      )
    ).getGroupsList();
    const decoded = newGroups.flatMap(g => {
      const c = g.getContract();
      return c === undefined
        ? []
        : // TODO (#3991) use g.getDomainId(), ""=>in-flight
          [Contract.decode(c, CodegenGroup)];
    });
    setGroups(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [splitwellClient, party]);

  useInterval(fetchGroups);

  return (
    <Stack spacing={2}>
      {groups.map(group => (
        <Group
          key={`${group.payload.owner}:${group.payload.id.unpack}`}
          group={group}
          party={party}
          provider={provider}
          domainId={domainId}
        />
      ))}
    </Stack>
  );
};

export default Groups;

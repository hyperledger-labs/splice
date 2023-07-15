import {
  Contract,
  sameContracts,
  useInterval,
  DirectoryEntry as DirectoryEntryComponent,
  DirectoryField,
  TransferButton,
  Loading,
  ErrorDisplay,
} from 'common-frontend';
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

import { SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import {
  AcceptedGroupInvite,
  BalanceUpdate,
  Group as CodegenGroup,
} from '@daml.js/splitwell/lib/CN/Splitwell';
import { ReceiverCCAmount } from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
import { ContractId } from '@daml/types';

import { useSplitwellClient } from '../contexts/SplitwellServiceContext';
import {
  useAddMember,
  useCreateInvite,
  useEnterPayment,
  useGroups,
  useInitiateTransfer,
} from '../hooks';
import { config } from '../utils/config';
import { SplitwellInstalls } from '../utils/installs';

interface BalancesProps {
  group: Contract<CodegenGroup>;
  party: string;
  provider: string;
  domainId: string;
  install: ContractId<SplitwellInstall>;
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

const Balances: React.FC<BalancesProps> = ({ group, party, provider, domainId, install }) => {
  const splitwellClient = useSplitwellClient();
  const [balances, setBalances] = useState<Map<string, string>>(new Map());
  const fetchBalances = useCallback(async () => {
    const balanceMap = (
      await splitwellClient.listBalances(party, group.payload.id.unpack, group.payload.owner)
    ).balances;
    let balances = new Map<string, string>();
    [group.payload.owner].concat(group.payload.members).forEach(p => {
      if (p !== party) {
        const balance: string | undefined = balanceMap[p];
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

  const initiateTransfer = useInitiateTransfer(party, provider, domainId, install);

  const groupId = group.payload.id;

  const initiateSettleDebts = useCallback(() => {
    const amounts: ReceiverCCAmount[] = Array.from(balances)
      .filter(([_, v]) => new Decimal(v).isNegative())
      .map(([k, v]) => {
        return { receiver: k, ccAmount: Decimal.abs(new Decimal(v)).toString() };
      });
    return initiateTransfer.mutateAsync({ groupId, amounts });
  }, [balances, groupId, initiateTransfer]);

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
                <TableCell>
                  <DirectoryEntryComponent className="balances-table-receiver" partyId={party} />
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
  install: ContractId<SplitwellInstall>;
}

const MembershipRequests: React.FC<MembershipRequestsProps> = ({
  group,
  party,
  provider,
  domainId,
  install,
}) => {
  const splitwellClient = useSplitwellClient();
  const [acceptedInvites, setAcceptedInvites] = useState<Contract<AcceptedGroupInvite>[]>([]);
  const fetchAcceptedInvites = useCallback(async () => {
    const invites = (await splitwellClient.listAcceptedGroupInvites(party, group.payload.id.unpack))
      .acceptedGroupInvites;
    const decoded = invites.map(c => Contract.decodeOpenAPI(c, AcceptedGroupInvite));
    setAcceptedInvites(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [group.payload.id.unpack, party, splitwellClient]);
  useInterval(fetchAcceptedInvites);

  const addMember = useAddMember(party, provider, domainId, install);

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
                onClick={() => addMember.mutate(invite)}
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
  install: ContractId<SplitwellInstall>;
}

const Entry: React.FC<EntryProps> = ({ group, party, provider, domainId, install }) => {
  const [paymentAmount, setPaymentAmount] = useState<string>('');
  const [paymentDescription, setPaymentDescription] = useState<string>('');
  const enterPayment = useEnterPayment(party, provider, domainId, install);
  const [transferAmount, setTransferAmount] = useState<string>('');
  const [transferReceiver, setTransferReceiver] = useState<string | undefined>();
  const initiateTransfer = useInitiateTransfer(party, provider, domainId, install);
  const groupId = group.payload.id;

  const transfer = async () => {
    const members = [group.payload.owner, ...group.payload.members];
    if (!transferReceiver) {
      throw new Error('Transfer receiver not set');
    } else if (!members.includes(transferReceiver)) {
      throw new Error(
        `Transfer receiver ${transferReceiver} is not part of the group’s members: ${members}`
      );
    } else {
      const amounts: ReceiverCCAmount[] = [
        { receiver: transferReceiver, ccAmount: transferAmount },
      ];
      return await initiateTransfer.mutateAsync({ groupId, amounts });
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
          <Button
            className="enter-payment-link"
            onClick={() =>
              enterPayment.mutate({ groupId: group.payload.id, paymentAmount, paymentDescription })
            }
          >
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
            createPaymentRequest={transfer}
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
      await splitwellClient.listBalanceUpdates(party, group.payload.id.unpack, group.payload.owner)
    ).balanceUpdates;
    const decoded = balanceUpdates.reverse().map(c => Contract.decodeOpenAPI(c, BalanceUpdate));
    setBalanceUpdates(prev => (sameContracts(decoded, prev) ? prev : decoded));
  }, [splitwellClient, group, party]);
  useInterval(fetchBalanceUpdates);

  const Update: React.FC<{ update: Contract<BalanceUpdate> }> = ({ update }) => {
    if (update.payload.update.tag === 'ExternalPayment') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <DirectoryEntryComponent className="sender" partyId={value.payer} />
          <span className="description">
            paid {value.amount} {'CC for '} {value.description}
          </span>
        </ListItem>
      );
    } else if (update.payload.update.tag === 'Transfer') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <DirectoryEntryComponent className="sender" partyId={value.sender} />
          <span className="description">
            sent {value.amount} {'CC to '}
          </span>
          <DirectoryEntryComponent className="receiver" partyId={value.receiver} />
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
  install: ContractId<SplitwellInstall>;
}

const Group: React.FC<GroupProps> = ({ group, party, provider, domainId, install }) => {
  const isOwner = party === group.payload.owner;
  const createInvite = useCreateInvite(party, provider, domainId, install);

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
            onClick={() => createInvite.mutate(group.payload.id)}
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
        <MembershipRequests
          group={group}
          party={party}
          provider={provider}
          domainId={domainId}
          install={install}
        />
      )}
      <Entry
        group={group}
        party={party}
        provider={provider}
        domainId={domainId}
        install={install}
      />
      <Divider />
      <Balances
        group={group}
        party={party}
        provider={provider}
        domainId={domainId}
        install={install}
      />
      <Divider />
      <BalanceUpdates group={group} party={party} />
      <Divider />
    </Paper>
  );
};

interface GroupsProps {
  party: string;
  provider: string;
  installs: SplitwellInstalls;
}

const Groups: React.FC<GroupsProps> = ({ party, provider, installs }) => {
  const groups = useGroups(party);

  if (groups.isLoading) {
    return <Loading />;
  }

  if (groups.isError) {
    return <ErrorDisplay message={'Error while retrieving groups'} />;
  }

  return (
    <Stack spacing={2}>
      {groups.data.flatMap(({ contract: group, domainId }) => {
        const install = installs.get(domainId);
        return install
          ? [
              <Group
                key={`${group.payload.owner}:${group.payload.id.unpack}`}
                group={group}
                party={party}
                provider={provider}
                domainId={domainId}
                install={install}
              />,
            ]
          : [];
      })}
    </Stack>
  );
};

export default Groups;

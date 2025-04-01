// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AnsEntry as AnsEntryComponent,
  AnsField,
  ErrorDisplay,
  Loading,
  TransferButton,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
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

import { ReceiverAmuletAmount } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import {
  BalanceUpdate,
  Group as CodegenGroup,
  SplitwellRules,
} from '@daml.js/splitwell/lib/Splice/Splitwell';

import {
  useAcceptedInvites,
  useAddMember,
  useBalances,
  useBalanceUpdates,
  useCreateInvite,
  useEnterPayment,
  useGroups,
  useInitiateTransfer,
} from '../hooks';
import { useConfig } from '../utils/config';
import { SplitwellRulesMap } from '../utils/installs';

interface BalancesProps {
  group: Contract<CodegenGroup>;
  party: string;
  provider: string;
  domainId: string;
  rules: Contract<SplitwellRules>;
}

const Balances: React.FC<BalancesProps> = ({ group, party, provider, domainId, rules }) => {
  const config = useConfig();
  const balances = useBalances(group, party);

  const initiateTransfer = useInitiateTransfer(party, provider, domainId, rules);

  const groupId = group.payload.id;

  const initiateSettleDebts = useCallback(() => {
    if (balances.data) {
      const amounts: ReceiverAmuletAmount[] = Object.entries(balances.data)
        .filter(([_, v]) => new Decimal(v).isNegative())
        .map(([k, v]) => {
          return { receiver: k, amuletAmount: Decimal.abs(new Decimal(v)).toString() };
        });
      return initiateTransfer.mutateAsync({ groupId, amounts });
    } else {
      return Promise.reject('Balances not yet fetched.');
    }
  }, [balances, groupId, initiateTransfer]);

  if (balances.isLoading) {
    return <Loading />;
  }
  if (balances.isError) {
    return <ErrorDisplay message="Error while retrieving group balances." />;
  }

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
            {Object.entries(balances.data).map(([party, balance]) => (
              <TableRow key={party} className="balances-table-row">
                <TableCell>
                  <AnsEntryComponent className="balances-table-receiver" partyId={party} />
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
  rules: Contract<SplitwellRules>;
}

const MembershipRequests: React.FC<MembershipRequestsProps> = ({
  group,
  party,
  provider,
  domainId,
  rules,
}) => {
  const acceptedInvites = useAcceptedInvites(group, party);

  const addMember = useAddMember(party, provider, domainId, rules);

  if (acceptedInvites.isLoading) {
    return <Loading />;
  }
  if (acceptedInvites.isError) {
    return <ErrorDisplay message="Error while retrieving accepted invites." />;
  }

  return (
    <Box>
      <Stack sx={{ px: 2, py: 1 }}>
        <Typography variant="button">Membership Requests</Typography>
        <List>
          {acceptedInvites.data.map(invite => (
            <ListItem key={invite.contractId}>
              <Button
                data-group={group.payload.id.unpack}
                data-invitee={invite.payload.invitee}
                className="add-user-link"
                onClick={() => addMember.mutate(invite)}
              >
                Add
              </Button>
              <AnsEntryComponent partyId={invite.payload.invitee} noCopy />
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
  rules: Contract<SplitwellRules>;
}

const Entry: React.FC<EntryProps> = ({ group, party, provider, domainId, rules }) => {
  const config = useConfig();
  const [paymentAmount, setPaymentAmount] = useState<string>('');
  const [paymentDescription, setPaymentDescription] = useState<string>('');
  const enterPayment = useEnterPayment(party, provider, domainId, rules);
  const [transferAmount, setTransferAmount] = useState<string>('');
  const [transferReceiver, setTransferReceiver] = useState<string | undefined>();
  const initiateTransfer = useInitiateTransfer(party, provider, domainId, rules);
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
      const amounts: ReceiverAmuletAmount[] = [
        { receiver: transferReceiver, amuletAmount: transferAmount },
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
          <AnsField
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
  const balanceUpdates = useBalanceUpdates(group, party);
  const amuletAcronym = useConfig().spliceInstanceNames.amuletNameAcronym;

  const Update: React.FC<{ update: Contract<BalanceUpdate> }> = ({ update }) => {
    if (update.payload.update.tag === 'ExternalPayment') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <AnsEntryComponent className="sender" partyId={value.payer} />
          <span className="description">
            paid {value.amount} {amuletAcronym} {'for'} {value.description}
          </span>
        </ListItem>
      );
    } else if (update.payload.update.tag === 'Transfer') {
      const value = update.payload.update.value;
      return (
        <ListItem className="balance-updates-list-item">
          <AnsEntryComponent className="sender" partyId={value.sender} />
          <span className="description">
            sent {value.amount} {amuletAcronym} {'to '}
          </span>
          <AnsEntryComponent className="receiver" partyId={value.receiver} />
        </ListItem>
      );
    } else {
      return <ListItem>Netting: Not yet implemented</ListItem>;
    }
  };

  if (balanceUpdates.isLoading) {
    return <Loading />;
  }
  if (balanceUpdates.isError) {
    return <ErrorDisplay message="Error while retrieving balance updates." />;
  }

  return (
    <Stack sx={{ px: 2, py: 1 }}>
      <Typography variant="button">Balance Updates</Typography>
      <List>
        {balanceUpdates.data.map(c => (
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
  rules: Contract<SplitwellRules>;
}

const Group: React.FC<GroupProps> = ({ group, party, provider, domainId, rules }) => {
  const isOwner = party === group.payload.owner;
  const createInvite = useCreateInvite(party, provider, domainId, rules);

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
          owned by <AnsEntryComponent partyId={group.payload.owner} />
        </Typography>
      </Stack>
      <Divider />
      {isOwner && (
        <MembershipRequests
          group={group}
          party={party}
          provider={provider}
          domainId={domainId}
          rules={rules}
        />
      )}
      <Entry group={group} party={party} provider={provider} domainId={domainId} rules={rules} />
      <Divider />
      <Balances group={group} party={party} provider={provider} domainId={domainId} rules={rules} />
      <Divider />
      <BalanceUpdates group={group} party={party} />
      <Divider />
    </Paper>
  );
};

interface GroupsProps {
  party: string;
  provider: string;
  rulesMap: SplitwellRulesMap;
}

const Groups: React.FC<GroupsProps> = ({ party, provider, rulesMap }) => {
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
        const rules = rulesMap.get(domainId);
        return rules
          ? [
              <Group
                key={`${group.payload.owner}:${group.payload.id.unpack}`}
                group={group}
                party={party}
                provider={provider}
                domainId={domainId}
                rules={rules}
              />,
            ]
          : [];
      })}
    </Stack>
  );
};

export default Groups;

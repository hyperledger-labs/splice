import { Contract, sameContracts, useInterval } from 'common-frontend';
import {
  ListGroupInvitesRequest,
  SplitwellContext,
} from 'common-protobuf/com/daml/network/splitwell/v0/splitwell_service_pb';
import { useCallback, useState } from 'react';

import { Button, FormGroup, List, ListItem, Stack, TextField, Typography } from '@mui/material';

import { GroupInvite } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellLedgerApiClient } from './contexts/SplitwellLedgerApiContext';
import { useSplitwellClient } from './contexts/SplitwellServiceContext';

interface GroupSetupProps {
  svc: string;
  provider: string;
  party: string;
  domainId: string;
}

const GroupSetup: React.FC<GroupSetupProps> = ({ party, provider, svc, domainId }) => {
  const splitwellClient = useSplitwellClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const [groupId, setGroupId] = useState<string>('');
  const [groupInvite, setGroupInvite] = useState<string>('');
  const onCreateGroup = async () => {
    await ledgerApiClient.requestGroup(party, provider, svc, groupId, domainId);
  };

  const [groupInvites, setGroupInvites] = useState<Contract<GroupInvite>[]>([]);

  const fetchInvites = useCallback(async () => {
    const groupInvites = (
      await splitwellClient.listGroupInvites(
        new ListGroupInvitesRequest().setContext(new SplitwellContext().setUserPartyId(party)),
        undefined
      )
    ).getGroupInvitesList();
    const decoded = groupInvites.map(c => Contract.decode(c, GroupInvite));
    setGroupInvites(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [splitwellClient, party]);

  useInterval(fetchInvites, 500);

  const onJoinGroup = async () => {
    const inviteContract = Contract.decodeOpenAPI(JSON.parse(groupInvite), GroupInvite);
    await ledgerApiClient.acceptInvite(
      party,
      provider,
      inviteContract.contractId,
      domainId,
      inviteContract
    );
  };

  const copyToClipboard = async (text: string) => {
    await navigator.clipboard.writeText(text);
  };

  return (
    <Stack spacing={2}>
      <FormGroup row>
        <TextField
          label="Group ID"
          id="group-id-field"
          value={groupId}
          onChange={event => setGroupId(event.target.value)}
        ></TextField>
        <Button variant="contained" id="create-group-button" onClick={onCreateGroup}>
          Create Group
        </Button>
      </FormGroup>
      <TextField
        label="Group Invite"
        id="group-invite-field"
        value={groupInvite}
        onChange={event => setGroupInvite(event.target.value)}
      ></TextField>
      <Button variant="contained" id="request-membership-link" onClick={onJoinGroup}>
        Request to join group
      </Button>
      <List>
        <Typography>Created group invites</Typography>
        {groupInvites.map(invite => (
          <ListItem className="invites-list-item" key={invite.contractId}>
            <Stack direction="row" alignItems="baseline">
              <Typography variant="button">{invite.payload.group.id.unpack}</Typography>
              <Button
                variant="contained"
                className="invite-copy-button"
                sx={{ color: 'text.primary', fontWeight: 'regular' }}
                data-invite-contract={JSON.stringify(invite)}
                data-group-id={invite.payload.group.id.unpack}
                onClick={() => copyToClipboard(JSON.stringify(invite))}
              >
                Copy invite
              </Button>
            </Stack>
          </ListItem>
        ))}
      </List>
    </Stack>
  );
};

export default GroupSetup;

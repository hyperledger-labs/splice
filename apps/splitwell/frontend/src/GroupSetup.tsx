import { DirectoryEntry, Contract, sameContracts, useInterval } from 'common-frontend';
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

  const onAcceptInvite = async (invite: Contract<GroupInvite>) => {
    await ledgerApiClient.acceptInvite(party, provider, invite.contractId, domainId);
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
      <List>
        {groupInvites.map(invite => (
          <ListItem className="invites-list-item" key={invite.contractId}>
            <Stack direction="row" alignItems="baseline">
              <div>
                <Typography variant="button">
                  <DirectoryEntry partyId={invite.payload.group.owner} />
                </Typography>{' '}
                is inviting you to join{' '}
                <Typography variant="button">{invite.payload.group.id.unpack}</Typography>
              </div>
              <Button
                data-owner={invite.payload.group.owner}
                data-group={invite.payload.group.id.unpack}
                className="request-membership-link"
                onClick={() => onAcceptInvite(invite)}
              >
                Request membership
              </Button>
            </Stack>
          </ListItem>
        ))}
      </List>
    </Stack>
  );
};

export default GroupSetup;

import { sameContracts, useInterval } from 'common-frontend';
import {
  ListGroupInvitesRequest,
  SplitwiseContext,
} from 'common-protobuf/com/daml/network/splitwise/v0/splitwise_service_pb';
import { useCallback, useState } from 'react';

import { Button, FormGroup, List, ListItem, Stack, TextField, Typography } from '@mui/material';

import { GroupInvite } from '@daml.js/splitwise/lib/CN/Splitwise';

import { Contract } from './Contract';
import DirectoryEntries from './DirectoryEntries';
import { useLedgerApiClient } from './LedgerApiContext';
import { useSplitwiseClient } from './contexts/SplitwiseServiceContext';

interface GroupSetupProps {
  directoryEntries: DirectoryEntries;
  svc: string;
  provider: string;
  party: string;
}

const GroupSetup: React.FC<GroupSetupProps> = ({ directoryEntries, party, provider, svc }) => {
  const splitwiseClient = useSplitwiseClient();
  const ledgerApiClient = useLedgerApiClient();
  const [groupId, setGroupId] = useState<string>('');
  const onCreateGroup = async () => {
    await ledgerApiClient.createGroup(party, provider, svc, groupId);
  };

  const [groupInvites, setGroupInvites] = useState<Contract<GroupInvite>[]>([]);

  const fetchInvites = useCallback(async () => {
    const groupInvites = (
      await splitwiseClient.listGroupInvites(
        new ListGroupInvitesRequest().setContext(new SplitwiseContext().setUserPartyId(party)),
        undefined
      )
    ).getGroupInvitesList();
    const decoded = groupInvites.map(c => Contract.decode(c, GroupInvite));
    setGroupInvites(prev => (sameContracts(prev, decoded) ? prev : decoded));
  }, [splitwiseClient, party]);

  useInterval(fetchInvites, 500);

  const onAcceptInvite = async (invite: Contract<GroupInvite>) => {
    await ledgerApiClient.acceptInvite(party, provider, invite.contractId);
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
                  {directoryEntries.resolveParty(invite.payload.group.owner)}
                </Typography>{' '}
                is inviting you to join{' '}
                <Typography variant="button">{invite.payload.group.id.unpack}</Typography>
              </div>
              <Button id="request-membership-link" onClick={() => onAcceptInvite(invite)}>
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

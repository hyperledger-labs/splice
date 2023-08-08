import { AssignedContract, Contract, ErrorDisplay, Loading } from 'common-frontend';
import { useCallback, useState } from 'react';

import { Button, FormGroup, List, ListItem, Stack, TextField, Typography } from '@mui/material';

import { GroupInvite, SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

import { useGroupInvites, useJoinGroup, useRequestGroup } from '../hooks';
import { SplitwellInstalls } from '../utils/installs';

interface GroupSetupProps {
  svc: string;
  provider: string;
  party: string;
  domainId: string;
  newGroupInstall: ContractId<SplitwellInstall>;
  installs: SplitwellInstalls;
}

type GroupInviteInput = { originalText: string } & (
  | {
      type: 'good';
      inviteContract: AssignedContract<GroupInvite>;
      install: ContractId<SplitwellInstall>;
    }
  | { type: 'noparse'; failure: string }
  | { type: 'noinstall'; domainId: string }
);

const GroupSetup: React.FC<GroupSetupProps> = ({
  party,
  provider,
  svc,
  domainId,
  newGroupInstall,
  installs,
}) => {
  const [groupId, setGroupId] = useState<string>('');
  const [groupInvite, setGroupInvite] = useState<GroupInviteInput>({
    type: 'noparse',
    failure: 'empty invite text field',
    originalText: '',
  });
  const requestGroup = useRequestGroup(party, provider, svc, domainId, newGroupInstall);
  const onCreateGroup = async () => {
    await requestGroup.mutate(groupId);
  };

  const groupInvites = useGroupInvites(party);

  const setGroupInviteInput = useCallback(
    (rawValue: string) => {
      try {
        const decodedInvite = JSON.parse(rawValue);
        const inviteContract = Contract.decodeOpenAPI(decodedInvite, GroupInvite);
        const inviteDomainId = (decodedInvite as { domainId: string }).domainId;
        const inviteInstall = installs.get(inviteDomainId);
        setGroupInvite({
          originalText: rawValue,
          ...(inviteInstall
            ? {
                type: 'good',
                inviteContract: { contract: inviteContract, domainId: inviteDomainId },
                install: inviteInstall,
              }
            : { type: 'noinstall', domainId: inviteDomainId }),
        });
      } catch (e) {
        if (e instanceof SyntaxError) {
          setGroupInvite({ originalText: rawValue, type: 'noparse', failure: e.message });
        } else {
          throw e;
        }
      }
    },
    [installs, setGroupInvite]
  );

  const joinGroup = useJoinGroup();

  const onJoinGroup = async () => {
    // otherwise this callback shouldn't have been called
    if (groupInvite.type === 'good') {
      const {
        inviteContract: { contract: inviteContract, domainId: inviteDomainId },
        install: inviteInstall,
      } = groupInvite;
      joinGroup.mutate({
        party,
        provider,
        inviteContract,
        installContractId: inviteInstall,
        inviteDomainId,
      });
    }
  };

  const copyToClipboard = async (text: string) => {
    await navigator.clipboard.writeText(text);
  };

  if (groupInvites.isLoading) {
    return <Loading />;
  }
  if (groupInvites.isError) {
    return <ErrorDisplay message="Error while retrieving group invites." />;
  }

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
        value={groupInvite.originalText}
        onChange={event => setGroupInviteInput(event.target.value)}
      ></TextField>
      <Button
        variant="contained"
        id="request-membership-link"
        disabled={groupInvite.type !== 'good'}
        onClick={onJoinGroup}
      >
        Request to join group
      </Button>
      <List>
        <Typography>Created group invites</Typography>
        {groupInvites.data.map(({ contract: invite, domainId }) => {
          const encodedInvite = JSON.stringify({ ...invite, domainId });
          return (
            <ListItem className="invites-list-item" key={invite.contractId}>
              <Stack direction="row" alignItems="baseline">
                <Typography variant="button">{invite.payload.group.id.unpack}</Typography>
                <Button
                  variant="contained"
                  className="invite-copy-button"
                  sx={{ color: 'text.primary', fontWeight: 'regular' }}
                  data-invite-contract={encodedInvite}
                  data-group-id={invite.payload.group.id.unpack}
                  onClick={() => copyToClipboard(encodedInvite)}
                >
                  Copy invite
                </Button>
              </Stack>
            </ListItem>
          );
        })}
      </List>
    </Stack>
  );
};

export default GroupSetup;

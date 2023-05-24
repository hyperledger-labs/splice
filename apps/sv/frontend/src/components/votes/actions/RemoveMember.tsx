import { Loading } from 'common-frontend';
import React, { useState } from 'react';

import { FormControl, NativeSelect, Stack, Typography } from '@mui/material';

import { useSvcInfos } from '../../../contexts/SvContext';

function createRow(key: string, value: string, isParty: boolean = false) {
  return { key, value, isParty };
}

const RemoveMember: React.FC<{ chooseAction: (action: object) => void }> = ({ chooseAction }) => {
  const svcInfosQuery = useSvcInfos();
  const [member, setMember] = useState('1');

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  var memberOptions: { key: string; value: string }[] = [];
  svcInfosQuery.data!.svcRules.payload.members.forEach((value, key) =>
    memberOptions.push(createRow(key, value.name))
  );
  function setMemberAction(member: string) {
    setMember(member);
    console.log(member);
    chooseAction({ action: 'SRARC_RemoveMember', member: member });
  }

  return (
    <Stack direction="column" mb={4} spacing={1}>
      <Typography variant="h6">Member</Typography>
      <FormControl fullWidth>
        <NativeSelect
          inputProps={{ id: 'display-members' }}
          value={member}
          onChange={e => setMemberAction(e.target.value)}
        >
          {memberOptions &&
            memberOptions.map((member, index) => (
              <option key={'member-option-' + index} value={member.key}>
                {member.value}
              </option>
            ))}
        </NativeSelect>
      </FormControl>
    </Stack>
  );
};

export default RemoveMember;

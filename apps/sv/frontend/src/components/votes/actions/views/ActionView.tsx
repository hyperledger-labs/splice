import { PartyId } from 'common-frontend';
import React from 'react';

import {
  Chip,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
  Typography,
} from '@mui/material';

import { ActionRequiringConfirmation } from '../../../../../../../common/frontend/daml.js/svc-governance-0.1.0/lib/CN/SvcRules';

const ActionView: React.FC<{ action: ActionRequiringConfirmation }> = ({ action }) => {
  const actionType = action.tag;

  if (action.tag === 'ARC_SvcRules') {
    const svcAction = action.value.svcAction;
    switch (svcAction.tag) {
      case 'SRARC_RemoveMember': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={svcAction.tag}
            valuesMap={{
              Member: <PartyId partyId={svcAction.value.member} />,
            }}
          />
        );
      }
      case 'SRARC_GrantFeaturedAppRight': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={svcAction.tag}
            valuesMap={{
              Provider: <PartyId partyId={svcAction.value.provider} />,
            }}
          />
        );
      }
      case 'SRARC_RevokeFeaturedAppRight': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={svcAction.tag}
            valuesMap={{
              FeatureAppRightCid: <PartyId partyId={svcAction.value.rightCid} />,
            }}
          />
        );
      }
    }
  }
  return <p>Not yet implemented for this action</p>;
};

const ActionValueTable: React.FC<{
  actionType: string;
  actionName: string;
  valuesMap: { [key: string]: React.ReactElement };
}> = ({ actionType, actionName, valuesMap }) => {
  return (
    <TableContainer>
      <Table style={{ tableLayout: 'auto' }} className="sv-voting-table">
        <TableBody>
          <TableRow>
            <TableCell>
              <Typography variant="h6">Action Type</Typography>
            </TableCell>
            <TableCell>
              <Chip id="vote-request-modal-action-type" label={actionType} color="primary" />
            </TableCell>
          </TableRow>
          <TableRow>
            <TableCell>
              <Typography variant="h6">Action Name</Typography>
            </TableCell>
            <TableCell>
              <Chip id="vote-request-modal-action-name" label={actionName} color="primary" />
            </TableCell>
          </TableRow>
          {Object.keys(valuesMap).map(key => (
            <TableRow key={key}>
              <TableCell>
                <Typography variant="h6">{key}</Typography>
              </TableCell>
              <TableCell>{valuesMap[key]}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
};

export default ActionView;

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';

import { Button, Dialog, DialogActions, DialogContent, DialogTitle } from '@mui/material';

import {
  ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

import { hasConflictingFields } from '../utils/configDiffs';

interface ConfirmationDialogProps {
  showDialog: boolean;
  onAccept: () => void;
  onClose: () => void;
  title: string;
  attributePrefix: string;
  children: React.ReactNode;
  supportsVoteEffectivityAndSetConfig?: boolean;
  action?: ActionRequiringConfirmation;
  voteRequestQuery?: UseQueryResult<Contract<VoteRequest>[]>;
}

export const ConfirmationDialogWithRequestConflictsCheck: React.FC<ConfirmationDialogProps> = ({
  showDialog,
  onAccept,
  onClose,
  title,
  attributePrefix,
  children,
  action,
  voteRequestQuery,
}) => {
  const conflicts =
    action && voteRequestQuery
      ? hasConflictingFields(action, voteRequestQuery.data).hasConflict
      : false;
  return (
    <Dialog
      open={showDialog}
      onClose={onClose}
      aria-labelledby={`${attributePrefix}-confirmation-dialog-title`}
    >
      <DialogTitle id={`${attributePrefix}-confirmation-dialog-title`}>
        {title}
        <hr />
      </DialogTitle>
      <DialogContent>{children}</DialogContent>
      <DialogActions>
        <Button autoFocus onClick={onClose}>
          Cancel
        </Button>
        <Button
          id={`${attributePrefix}-confirmation-dialog-accept-button`}
          onClick={onAccept}
          disabled={conflicts}
        >
          Proceed
        </Button>
      </DialogActions>
    </Dialog>
  );
};

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Button, Dialog, DialogActions, DialogContent, DialogTitle } from '@mui/material';

export interface ConfirmationDialogProps {
  showDialog: boolean;
  onAccept: () => void;
  onClose: () => void;
  title: string;
  attributePrefix: string;
  children: React.ReactNode;
  disableProceed?: boolean;
}

export const ConfirmationDialog: React.FC<ConfirmationDialogProps> = ({
  showDialog,
  onAccept,
  onClose,
  title,
  attributePrefix,
  children,
  disableProceed,
}) => {
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
          disabled={disableProceed}
        >
          Proceed
        </Button>
      </DialogActions>
    </Dialog>
  );
};

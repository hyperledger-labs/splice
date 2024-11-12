// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Button, Dialog, DialogActions, DialogContent, DialogTitle } from '@mui/material';

interface VoteConfirmationDialogProps {
  showDialog: boolean;
  onAccept: () => void;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
}

export const VoteConfirmationDialog: React.FC<VoteConfirmationDialogProps> = ({
  showDialog,
  onAccept,
  onClose,
  title,
  children,
}) => {
  return (
    <Dialog open={showDialog} onClose={onClose} aria-labelledby="vote-confirmation-dialog-title">
      <DialogTitle id="vote-confirmation-dialog-title">
        {title}
        <hr />
      </DialogTitle>
      <DialogContent>{children}</DialogContent>
      <DialogActions>
        <Button autoFocus onClick={onClose}>
          Cancel
        </Button>
        <Button id="vote-confirmation-dialog-accept-button" onClick={onAccept}>
          Proceed
        </Button>
      </DialogActions>
    </Dialog>
  );
};

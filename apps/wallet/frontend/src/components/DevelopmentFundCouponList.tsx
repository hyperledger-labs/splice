// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useDevelopmentFundCouponsHistory } from '../hooks/useDevelopmentFundCouponsHistory';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import BftAnsEntry from './BftAnsEntry';
import { DisableConditionally } from '@lfdecentralizedtrust/splice-common-frontend';

const DevelopmentFundCouponList: React.FC = () => {
  const { withdrawDevelopmentFundCoupon } = useWalletClient();
  const {
    coupons,
    isLoading,
    isError,
    error,
    hasNextPage,
    hasPreviousPage,
    currentPage,
    goToNextPage,
    goToPreviousPage,
    invalidate,
  } = useDevelopmentFundCouponsHistory();

  const [withdrawDialogOpen, setWithdrawDialogOpen] = useState(false);
  const [selectedCoupon, setSelectedCoupon] = useState<string | null>(null);
  const [withdrawalReason, setWithdrawalReason] = useState('');

  const withdrawMutation = useMutation({
    mutationFn: async (reason: string) => {
      if (!selectedCoupon) {
        throw new Error('No coupon selected');
      }
      return await withdrawDevelopmentFundCoupon(selectedCoupon, reason);
    },
    onSuccess: () => {
      setWithdrawDialogOpen(false);
      setSelectedCoupon(null);
      setWithdrawalReason('');
      invalidate();
    },
    onError: error => {
      console.error('Failed to withdraw development fund coupon', error);
    },
  });

  const handleWithdrawClick = (couponId: string) => {
    setSelectedCoupon(couponId);
    setWithdrawDialogOpen(true);
  };

  const handleWithdrawConfirm = () => {
    if (withdrawalReason.trim()) {
      withdrawMutation.mutate(withdrawalReason);
    }
  };

  if (isLoading) {
    return <Loading />;
  }

  if (isError) {
    return (
      <Alert severity="error">
        Error loading development fund coupons: {JSON.stringify(error)}
      </Alert>
    );
  }

  const formatDate = (date: Date) => {
    return date.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
  };

  return (
    <Stack spacing={2}>
      <Typography variant="h4">Development Coupon List</Typography>
      <Card variant="outlined">
        <CardContent>
          <TableContainer>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell>Created At</TableCell>
                  <TableCell>Beneficiary</TableCell>
                  <TableCell>Amount</TableCell>
                  <TableCell>Expires At</TableCell>
                  <TableCell>Reason</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell>Withdrawal Reason</TableCell>
                  <TableCell>Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {coupons.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={8} align="center">
                      <Typography variant="body2" color="text.secondary">
                        No coupons found
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  coupons.map(coupon => (
                    <TableRow key={coupon.id}>
                      <TableCell>{formatDate(coupon.createdAt)}</TableCell>
                      <TableCell>
                        <BftAnsEntry partyId={coupon.beneficiary} />
                      </TableCell>
                      <TableCell>{coupon.amount.toFixed(4)} CC</TableCell>
                      <TableCell>{formatDate(coupon.expiresAt)}</TableCell>
                      <TableCell>{coupon.reason}</TableCell>
                      <TableCell>
                        <Chip label={coupon.status} size="small" sx={{ color: 'black' }} />
                      </TableCell>
                      <TableCell>
                        {coupon.withdrawalReason ? (
                          <Typography variant="body2" color="text.secondary">
                            {coupon.withdrawalReason}
                          </Typography>
                        ) : (
                          <Typography variant="body2" color="text.secondary">
                            -
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        {coupon.status === 'active' ? (
                          <Button
                            variant="outlined"
                            size="small"
                            onClick={() => handleWithdrawClick(coupon.id)}
                          >
                            Withdraw
                          </Button>
                        ) : (
                          <Typography variant="body2" color="text.secondary">
                            -
                          </Typography>
                        )}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>

          {/* Pagination controls */}
          <Box display="flex" justifyContent="space-between" mt={2}>
            <Button disabled={!hasPreviousPage} onClick={goToPreviousPage}>
              Previous
            </Button>
            <Typography variant="body2" alignSelf="center">
              Page {currentPage}
            </Typography>
            <Button disabled={!hasNextPage} onClick={goToNextPage}>
              Next
            </Button>
          </Box>
        </CardContent>
      </Card>

      {/* Withdraw Dialog */}
      <Dialog open={withdrawDialogOpen} onClose={() => setWithdrawDialogOpen(false)}>
        <DialogTitle>Withdraw Development Fund Coupon</DialogTitle>
        <DialogContent>
          <Stack spacing={2} mt={1}>
            <Typography variant="body2">
              Please provide a reason for withdrawing this coupon.
            </Typography>
            <TextField
              label="Withdrawal Reason"
              multiline
              rows={3}
              fullWidth
              value={withdrawalReason}
              onChange={event => setWithdrawalReason(event.target.value)}
              placeholder="Enter the reason for withdrawal"
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setWithdrawDialogOpen(false)}>Cancel</Button>
          <DisableConditionally
            conditions={[
              {
                disabled: withdrawMutation.isPending,
                reason: 'Withdrawing...',
              },
              {
                disabled: !withdrawalReason.trim(),
                reason: 'Please provide a withdrawal reason',
              },
            ]}
          >
            <Button variant="contained" onClick={handleWithdrawConfirm}>
              Confirm Withdrawal
            </Button>
          </DisableConditionally>
        </DialogActions>
      </Dialog>
    </Stack>
  );
};

export default DevelopmentFundCouponList;

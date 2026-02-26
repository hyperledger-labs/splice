// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React, { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import {
  useActiveDevelopmentFundCoupons,
  useCouponHistoryEvents,
} from '../hooks/useDevelopmentFundCouponsHistory';
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
import { Loading, DisableConditionally } from '@lfdecentralizedtrust/splice-common-frontend';
import { CouponHistoryEventType } from '../models/models';
import BftAnsEntry from './BftAnsEntry';

const formatDate = (date: Date) => {
  return date.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
};

const formatDateTime = (date: Date) => {
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};

const getEventTypeLabel = (eventType: CouponHistoryEventType): string => {
  switch (eventType) {
    case 'activation':
      return 'Activated';
    case 'claim':
      return 'Claimed';
    case 'withdrawal':
      return 'Withdrawn';
    case 'expiration':
      return 'Expired';
    default:
      return eventType;
  }
};

// Active Coupons Table Component
const ActiveCouponsTable: React.FC = () => {
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
  } = useActiveDevelopmentFundCoupons();

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
        Error loading active coupons: {JSON.stringify(error)}
      </Alert>
    );
  }

  return (
    <>
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
                  <TableCell>Allocation Reason</TableCell>
                  <TableCell>Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {coupons.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={6} align="center">
                      <Typography variant="body2" color="text.secondary">
                        No development fund allocations found
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
                        <Button
                          variant="outlined"
                          size="small"
                          onClick={() => handleWithdrawClick(coupon.id)}
                        >
                          Withdraw
                        </Button>
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
      <Dialog open={!!selectedCoupon} onClose={() => setSelectedCoupon(null)}>
        <DialogTitle>Withdraw Development Fund Coupon</DialogTitle>
        <DialogContent>
          <Stack spacing={2} mt={1}>
            <Typography variant="body2">
              Please provide a reason for withdrawing this development fund allocation.
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
          <Button onClick={() => setSelectedCoupon(null)}>Cancel</Button>
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
    </>
  );
};

// Coupon History Table Component
const CouponHistoryTable: React.FC = () => {
  const {
    events,
    isLoading,
    isError,
    error,
    hasNextPage,
    hasPreviousPage,
    currentPage,
    goToNextPage,
    goToPreviousPage,
  } = useCouponHistoryEvents();

  if (isLoading) {
    return <Loading />;
  }

  if (isError) {
    return (
      <Alert severity="error">
        Error loading coupon history: {JSON.stringify(error)}
      </Alert>
    );
  }

  return (
    <Card variant="outlined">
      <CardContent>
        <TableContainer>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell>Timestamp</TableCell>
                <TableCell>Event Type</TableCell>
                <TableCell>Beneficiary</TableCell>
                <TableCell>Amount</TableCell>
                <TableCell>Allocation Reason</TableCell>
                <TableCell>Withdrawal Reason</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {events.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} align="center">
                    <Typography variant="body2" color="text.secondary">
                      No history events found
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                events.map(event => (
                  <TableRow key={event.id}>
                    <TableCell>{formatDateTime(event.timestamp)}</TableCell>
                    <TableCell>
                      <Chip
                        label={getEventTypeLabel(event.eventType)}
                        size="small"
                        sx={{ color: 'black' }}
                      />
                    </TableCell>
                    <TableCell>
                      <BftAnsEntry partyId={event.beneficiary} />
                    </TableCell>
                    <TableCell>{event.amount.toFixed(4)} CC</TableCell>
                    <TableCell>
                      <Typography variant="body2">{event.allocationReason}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">
                        {event.withdrawalReason ? event.withdrawalReason : '-'}
                      </Typography>
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
  );
};

// Main Component
const DevelopmentFundCouponList: React.FC = () => {
  return (
    <Stack spacing={4}>
      <Stack spacing={2}>
        <Typography variant="h4">Unclaimed Development Fund Allocations</Typography>
        <ActiveCouponsTable />
      </Stack>

      <Stack spacing={2}>
        <Typography variant="h4">Coupon History</Typography>
        <CouponHistoryTable />
      </Stack>
    </Stack>
  );
};

export default DevelopmentFundCouponList;

// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useNavigate } from 'react-router';
import { useMutation, UseMutationResult } from '@tanstack/react-query';
import { ProposalMutationArgs } from '../utils/types';
import dayjs from 'dayjs';
import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useDsoInfos } from '../contexts/SvContext';
import { toast } from 'sonner';

export const useProposalMutation: () => UseMutationResult<
  void,
  Error,
  ProposalMutationArgs
> = () => {
  const { createVoteRequest } = useSvAdminClient();
  const dsoInfosQuery = useDsoInfos();
  const requester = dsoInfosQuery.data?.svPartyId!;
  const navigate = useNavigate();

  return useMutation({
    mutationFn: async (arg: ProposalMutationArgs) => {
      const { formData, action } = arg;

      const isConfigProposal = 'config' in formData;
      const url = isConfigProposal ? formData.common.url : formData.url;
      const summary = isConfigProposal ? formData.common.summary : formData.summary;

      const formExpiryDate = isConfigProposal ? formData.common.expiryDate : formData.expiryDate;
      const expiryDate = {
        microseconds: BigInt(dayjs(formExpiryDate).diff(dayjs(), 'milliseconds') * 1000).toString(),
      };

      const formEffectiveDate = isConfigProposal
        ? formData.common.effectiveDate
        : formData.effectiveDate;

      const effectiveDate =
        formEffectiveDate.type === 'threshold'
          ? undefined
          : dayjs(formEffectiveDate.effectiveDate).toDate();

      return createVoteRequest(requester, action, url, summary, expiryDate, effectiveDate);
    },

    onSuccess: () => {
      toast.success('Successfully submitted the proposal');
      navigate('/governance-beta/proposals');
    },

    onError: error => {
      console.error(`Failed to send proposal to dso`, error);
    },
  });
};

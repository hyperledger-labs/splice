// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import dayjs from 'dayjs';
import { RestHandler, rest } from 'msw';
import {
  Contract,
  ErrorResponse,
  GetDsoInfoResponse,
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
  ListValidatorLicensesResponse,
} from 'sv-openapi';

import { ValidatorLicense } from '@daml.js/splice-amulet-0.1.5/lib/Splice/ValidatorLicense';

import { dsoInfo, voteResults } from '../constants';

export const buildSvMock = (svUrl: string): RestHandler[] => [
  rest.get(`${svUrl}/v0/admin/authorization`, (_, res, ctx) => {
    return res(ctx.status(200));
  }),
  rest.get(`${svUrl}/v0/dso`, (_, res, ctx) => {
    return res(ctx.json<GetDsoInfoResponse>(dsoInfo));
  }),
  rest.get(`${svUrl}/v0/admin/sv/voterequests`, (_, res, ctx) => {
    return res(
      ctx.json<ListDsoRulesVoteRequestsResponse>({
        dso_rules_vote_requests: [],
      })
    );
  }),
  rest.post(`${svUrl}/v0/admin/sv/voteresults`, (_, res, ctx) => {
    console.log(voteResults);
    return res(ctx.json<ListDsoRulesVoteResultsResponse>(voteResults));
  }),
  rest.get(`${svUrl}/v0/admin/domain/cometbft/debug`, (_, res, ctx) => {
    return res(
      ctx.status(404),
      ctx.json<ErrorResponse>({
        error: `No domain nodes in this test.`,
      })
    );
  }),
  rest.get(`${svUrl}/v0/admin/domain/sequencer/status`, (_, res, ctx) => {
    return res(
      ctx.status(404),
      ctx.json<ErrorResponse>({
        error: `No domain nodes in this test.`,
      })
    );
  }),
  rest.get(`${svUrl}/v0/admin/domain/mediator/status`, (_, res, ctx) => {
    return res(
      ctx.status(404),
      ctx.json<ErrorResponse>({
        error: `No domain nodes in this test.`,
      })
    );
  }),
  rest.get(`${svUrl}/v0/admin/validator/licenses`, (req, res, ctx) => {
    const n = parseInt(req.url.searchParams.get('limit')!);
    const after = req.url.searchParams.get('after');
    const from = after ? parseInt(after) + 1 : 0;
    const now = dayjs();
    const validatorLicenses = Array.from({ length: n }, (_, i) => {
      const id = (i + from).toString();
      const validatorLicense: ValidatorLicense = {
        dso: 'dso',
        validator: `validator::${id}`,
        sponsor: 'sponsor',
        faucetState: {
          firstReceivedFor: { number: '1' },
          lastReceivedFor: { number: '10' },
          numCouponsMissed: '1',
        },
        metadata: { version: '1', lastUpdatedAt: now.toISOString(), contactPoint: 'nowhere' },
        lastActiveAt: now.toISOString(),
      };
      const contract: Contract = {
        contract_id: id,
        created_at: now.toISOString(),
        created_event_blob: '',
        payload: validatorLicense,
        template_id: ValidatorLicense.templateId,
      };
      return contract;
    });
    return res(
      ctx.json<ListValidatorLicensesResponse>({
        validator_licenses: validatorLicenses,
        next_page_token: from + n,
      })
    );
  }),
];

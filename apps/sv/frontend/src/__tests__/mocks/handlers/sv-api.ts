// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  validatorLicensesHandler,
  dsoInfoHandler,
} from '@lfdecentralizedtrust/splice-common-test-handlers';
import dayjs from 'dayjs';
import { rest, RestHandler } from 'msw';
import {
  ErrorResponse,
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
  ListVoteRequestByTrackingCidResponse,
  LookupDsoRulesVoteRequestResponse,
} from 'sv-openapi';

import {
  voteRequest,
  voteRequests,
  voteResultsAmuletRules,
  voteResultsDsoRules,
} from '../constants';

export const buildSvMock = (svUrl: string): RestHandler[] => [
  rest.get(`${svUrl}/v0/admin/authorization`, (_, res, ctx) => {
    return res(ctx.status(200));
  }),
  dsoInfoHandler(svUrl),
  rest.get(`${svUrl}/v0/admin/sv/voterequests`, (_, res, ctx) => {
    return res(ctx.json<ListDsoRulesVoteRequestsResponse>(voteRequests));
  }),
  rest.get(`${svUrl}/v0/admin/sv/voterequests/:id`, (req, res, ctx) => {
    const { id } = req.params;
    return res(
      ctx.json<LookupDsoRulesVoteRequestResponse>({
        dso_rules_vote_request: voteRequests.dso_rules_vote_requests.filter(
          vr => vr.contract_id === id
        )[0],
      })
    );
  }),
  rest.post(`${svUrl}/v0/admin/sv/voterequest`, (_, res, ctx) => {
    return res(ctx.json<ListVoteRequestByTrackingCidResponse>(voteRequest));
  }),
  rest.post(`${svUrl}/v0/admin/sv/voteresults`, (req, res, ctx) => {
    return req.json().then(data => {
      if (data.actionName === 'SRARC_SetConfig') {
        return res(
          ctx.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: voteResultsDsoRules.dso_rules_vote_results
              .filter(
                r =>
                  (data.accepted
                    ? r.outcome.tag === 'VRO_Accepted'
                    : r.outcome.tag === 'VRO_Rejected') &&
                  (data.effectiveTo
                    ? dayjs(r.completedAt).isBefore(dayjs(data.effectiveTo))
                    : true) &&
                  (data.effectiveFrom
                    ? dayjs(r.completedAt).isAfter(dayjs(data.effectiveFrom))
                    : true)
              )
              .slice(0, data.limit || 10),
          })
        );
      } else if (data.actionName === 'CRARC_AddFutureAmuletConfigSchedule') {
        return res(
          ctx.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: voteResultsAmuletRules.dso_rules_vote_results
              .filter(
                r =>
                  (data.accepted
                    ? r.outcome.tag === 'VRO_Accepted'
                    : r.outcome.tag === 'VRO_Rejected') &&
                  (data.effectiveTo
                    ? r.outcome.value
                      ? dayjs(r.outcome.value.effectiveAt).isBefore(dayjs(data.effectiveTo))
                      : dayjs(r.completedAt).isBefore(dayjs(data.effectiveTo))
                    : true) &&
                  (data.effectiveFrom
                    ? r.outcome.value
                      ? dayjs(r.outcome.value.effectiveAt).isAfter(dayjs(data.effectiveFrom))
                      : dayjs(r.completedAt).isAfter(dayjs(data.effectiveFrom))
                    : true)
              )
              .slice(0, data.limit || 10),
          })
        );
      } else if (data.actionName === 'CRARC_UpdateFutureAmuletConfigSchedule') {
        return res(
          ctx.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: [],
          })
        );
      } else {
        return res(
          ctx.json<ListDsoRulesVoteResultsResponse>({
            dso_rules_vote_results: voteResultsAmuletRules.dso_rules_vote_results
              .concat(voteResultsDsoRules.dso_rules_vote_results)
              .filter(r =>
                data.accepted
                  ? r.outcome.tag === 'VRO_Accepted'
                  : r.outcome.tag === 'VRO_Rejected' &&
                    (data.effectiveTo
                      ? r.outcome.value
                        ? dayjs(r.outcome.value.effectiveAt).isBefore(dayjs(data.effectiveTo))
                        : dayjs(r.completedAt).isBefore(dayjs(data.effectiveTo))
                      : true) &&
                    (data.effectiveFrom
                      ? r.outcome.value
                        ? dayjs(r.outcome.value.effectiveAt).isAfter(dayjs(data.effectiveFrom))
                        : dayjs(r.completedAt).isAfter(dayjs(data.effectiveFrom))
                      : true)
              ),
          })
        );
      }
    });
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
  validatorLicensesHandler(svUrl),
];

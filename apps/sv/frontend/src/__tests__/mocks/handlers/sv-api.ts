// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  validatorLicensesHandler,
  dsoInfoHandler,
} from '@lfdecentralizedtrust/splice-common-test-handlers';
import dayjs from 'dayjs';
import { rest, RestHandler } from 'msw';
import { FeatureSupportResponse, SuccessStatusResponse } from '@lfdecentralizedtrust/scan-openapi';
import {
  ErrorResponse,
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
  ListOngoingValidatorOnboardingsResponse,
  ListVoteRequestByTrackingCidResponse,
  LookupDsoRulesVoteRequestResponse,
} from '@lfdecentralizedtrust/sv-openapi';

import {
  voteRequest,
  voteRequests,
  voteResultsAmuletRules,
  voteResultsDsoRules,
  svPartyId,
} from '../constants';
import { ValidatorOnboarding } from '@daml.js/splice-validator-lifecycle/lib/Splice/ValidatorOnboarding/module';
import { ContractId } from '@daml/types';

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

  rest.post(`${svUrl}/v0/admin/sv/voterequest/create`, (_, res, ctx) => {
    return res(ctx.json({}));

    // Use this to test a failed response
    // return res(
    //   ctx.status(503),
    //   ctx.json({
    //     error: 'Service Unavailable',
    //   })
    // );
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

  rest.post(`${svUrl}/v0/admin/sv/votes`, (_, res, ctx) => {
    return res(ctx.status(201));
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
      ctx.json<SuccessStatusResponse>({
        success: {
          id: 'global-domain::1990be58c99e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb999a',
          uptime: 'PT26H38.219973S',
          ports: {
            public: 5008,
            admin: 5009,
          },
          active: true,
        },
      })
    );
  }),

  rest.get(`${svUrl}/v0/admin/domain/mediator/status`, (_, res, ctx) => {
    return res(
      ctx.json<SuccessStatusResponse>({
        success: {
          id: 'global-domain::1990be58c99e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb999a',
          uptime: 'PT26H38.219973S',
          ports: {
            public: 5008,
            admin: 5009,
          },
          active: true,
        },
      })
    );
  }),

  rest.get(`${svUrl}/v0/admin/feature-support`, (_, res, ctx) => {
    return res(ctx.json<FeatureSupportResponse>({ no_holding_fees_on_transfers: false }));
  }),

  validatorLicensesHandler(svUrl),
  rest.get(`${svUrl}/v0/admin/validator/onboarding/ongoing`, (_, res, ctx) => {
    return res(
      ctx.json<ListOngoingValidatorOnboardingsResponse>({
        ongoing_validator_onboardings: [
          {
            encoded_secret: 'encoded_secret',
            contract: {
              template_id:
                '455dd4533c2dd0131fb349c93d9d35f3670901d13efadb0aa9b975d35b41dbb2:Splice.ValidatorOnboarding:ValidatorOnboarding',
              contract_id: 'validatorOnboardingCid' as ContractId<ValidatorOnboarding>,
              payload: {
                sv: 'svParty',
                candidateSecret: 'candidate_secret',
                expiresAt: '2024-08-05T13:44:35.878681Z',
              },
              created_event_blob: '',
              created_at: '2024-08-05T13:44:35.878681Z',
            },
          },
        ],
      })
    );
  }),

  rest.get(`${svUrl}/v0/admin/sv/party-to-participant/:partyId`, (req, res, ctx) => {
    const { partyId } = req.params;
    if (partyId === 'a-party-id::1014912492' || partyId === svPartyId) {
      return res(
        ctx.json({
          participant_id: svPartyId,
        })
      );
    } else {
      return res(ctx.status(404));
    }
  }),
];

import { RestHandler, rest } from 'msw';
import { ErrorResponse, GetSvcInfoResponse, ListSvcRulesVoteRequestsResponse } from 'sv-openapi';

import { svcInfo } from '../constants';

export const buildSvMock = (svUrl: string): RestHandler[] => [
  rest.get(`${svUrl}/v0/admin/authorization`, (_, res, ctx) => {
    return res(ctx.status(200));
  }),
  rest.get(`${svUrl}/v0/svc`, (_, res, ctx) => {
    return res(ctx.json<GetSvcInfoResponse>(svcInfo));
  }),
  rest.get(`${svUrl}/v0/admin/sv/voterequests`, (_, res, ctx) => {
    return res(
      ctx.json<ListSvcRulesVoteRequestsResponse>({
        svc_rules_vote_requests: [],
      })
    );
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
];

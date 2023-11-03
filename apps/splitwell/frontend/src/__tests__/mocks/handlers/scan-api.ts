import { RestHandler, rest } from 'msw';
import { GetSvcPartyIdResponse } from 'scan-openapi';

export const buildScanMock = (scanUrl: string): RestHandler[] => [
  rest.get(`${scanUrl}/v0/svc-party-id`, (_, res, ctx) => {
    return res(
      ctx.json<GetSvcPartyIdResponse>({
        svc_party_id: 'svc::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
      })
    );
  }),
];

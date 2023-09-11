import { RestHandler, rest } from 'msw';
import { GetProviderPartyIdResponse } from 'splitwell-openapi';

export const buildSplitwellMock = (splitwellUrl: string): RestHandler[] => [
  rest.get<null, never, GetProviderPartyIdResponse>(
    `${splitwellUrl}/provider-party-id`,
    (_, res, ctx) => {
      return res(
        ctx.json({
          providerPartyId:
            'splitwell__provider::122087c9aa69ea8ab1b674bbb7c39e47d03c15b6ffaf7343b58b4a6b71c9d9fc6ce1',
        })
      );
    }
  ),
  rest.get(`${splitwellUrl}/splitwell-installs`, (_, res, ctx) => {
    return res(
      ctx.json({
        installs: [
          {
            contract_id:
              '0012fc9ea13690eab655f8d3caaf4bb7f736e25b6dba1e8a35cd8fa214f3996570ca0212209dbc04b3bef1127458cf97dc501df9b6457bea7a3c4a116169198220c4507d41',
            domain_id:
              'splitwell::122037ea629383b22a47d2355f0e08fb1637964744e65959271eaea665e4d13015b1',
          },
        ],
      })
    );
  }),
  rest.get(`${splitwellUrl}/connected-domains`, (_, res, ctx) => {
    return res(
      ctx.json({
        domain_ids: [
          'global-domain::1220809612f787469c92b924ad1d32f1cbc0bdbd4eeda55a50469250bcf64b8becf2',
          'splitwell::122037ea629383b22a47d2355f0e08fb1637964744e65959271eaea665e4d13015b1',
        ],
      })
    );
  }),
  rest.get(`${splitwellUrl}/splitwell-domains`, (_, res, ctx) => {
    return res(
      ctx.json({
        preferred:
          'splitwell::122037ea629383b22a47d2355f0e08fb1637964744e65959271eaea665e4d13015b1',
        other_domain_ids: [],
      })
    );
  }),

  rest.get(`${splitwellUrl}/group-invites`, (_, res, ctx) => {
    return res(
      ctx.json({
        group_invites: [],
      })
    );
  }),
  rest.get(`${splitwellUrl}/groups`, (_, res, ctx) => {
    return res(
      ctx.json({
        groups: [],
      })
    );
  }),
];

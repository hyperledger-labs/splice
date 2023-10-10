import { DirectoryEntry } from 'common-frontend/daml.js/directory-service-0.1.0/lib/CN/Directory/module';
import { ErrorResponse, LookupEntryByPartyResponse } from 'directory-openapi';
import { RestHandler, rest } from 'msw';

export const buildDirectoryMock = (directoryUrl: string): RestHandler[] => [
  rest.get<null, { partyId: string }, LookupEntryByPartyResponse | ErrorResponse>(
    `${directoryUrl}/entries/by-party/:partyId`,
    (req, res, ctx) => {
      if (
        req.params['partyId'] ===
        'charlie__wallet__user::12200d3c885d2cb51226911f828da25f7f0fc0d06b8c6bf00c714266729033f138f7'
      ) {
        return res(
          ctx.json<LookupEntryByPartyResponse>({
            entry: {
              template_id:
                'ab605eca7c09dd64625e95e4a1d632058276f05fbdfe2f67009e66f35d3b3106:CN.Directory:DirectoryEntry',
              contract_id:
                '00a0e1386b02ea75f0ddcfc7c4fbfb8eba09cd3c3748b160de1f17450bb99faaa7ca0212209680fb7e9526ddccf5931db169bf8ba16e4e60d7e23a74c28e9492e8b62d1194',
              payload: DirectoryEntry.encode({
                name: 'charlie.unverified.cns',
                provider:
                  'svc::1220164c57d911c28c81575446053818b21dc83b24cf10b22c622663e0e282d85a57',
                url: '',
                description: '',
                expiresAt: '2024-01-04T07:37:05.004139Z',
                user: 'google-oauth2_007c106265882859845879513::122033667ff9ec083bf5a6b512655bd7986dfc4d6644978c944129a0f46489bc41d4',
              }),
              payload_value: {},
              metadata: {
                createdAt: '2023-10-06T07:37:05.004139Z',
                contractKeyHash: '',
                driverMetadata: 'CiYKJAgBEiCXgBoBsVAjxBQQBHjwClCXH2Q36rFqPdR9wr9OKphDqQ==',
              },
            },
          })
        );
      } else {
        return res(
          ctx.status(404),
          ctx.json({
            error:
              'No directory entry found for party: alice::122015ba7aa9054dbad217110e8fbf5dd550a59fb56df5986913f7b9a8e63bad8570',
          })
        );
      }
    }
  ),
];

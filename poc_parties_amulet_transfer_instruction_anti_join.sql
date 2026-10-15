-- POC for the anti-join between owners to ignore and dso_acs_store

-- Scenario:
-- dso_acs_stores has roughly 11M rows containing 1M TransferInstructionAmulets hold by 1M unique owners against a table of 200k owners to ignore
-- steps:
-- 1. insert 1M amulets transfer instruction hold by 500k unique owners
-- 2. Add more unrelated data about 1OM rows
-- 3. create 200k parties to ignore in table dso_unavailable_parties
-- 4. define indices on dso_unavailable_parties
-- 5. Run a sample Anti-join query

-- 1. Command to insert amulet in batches
-- 1. Insert 1M AmuletTransferInstruction rows held by 500k unique senders
DO $$
    DECLARE
        batch_size INT := 1000000;
        start_row INT;
        end_row INT;
    BEGIN
        FOR i IN 0..1 LOOP -- 800 batches of 100k = 80 million
        start_row := (i * batch_size) + 1;
        end_row := (i + 1) * batch_size;

        RAISE NOTICE 'Processing batch %: rows % to %', i+1, start_row, end_row;

        INSERT INTO dso_acs_store (
            store_id, migration_id, contract_id, template_id_package_id,
            template_id_qualified_name, create_arguments, created_event_blob,
            created_at, assigned_domain, reassignment_counter, reward_amount, package_name, amulet_round_of_expiry
        )
        SELECT
            5, 0,
            'transfer-instr-2' || lpad(row_num::text, 20, '0'),
            '56ed13e658da77aad568b59c8fb8cca890f8bb26d473a43342340bc1306d547t',
            'Splice.Amulet:AmuletTransferInstruction',
            jsonb_build_object(
                    'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
                    'transfer', jsonb_build_object(
                            'sender', 'user_' || lpad((row_num % 500000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 1000000)::text),
                            'receiver', 'user_' || lpad(((row_num + 500000) % 1000000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || ((row_num + 500000) % 1000000)::text),
                            'amount', (1 + random() * 500)::numeric(28,10)::text
                                ),
                    'expiresAt', row_num % 789 + 100
            ),
            decode('0A03322E3112C1050A45002A10285920CE60630D645A6A57F2552811BB3E', 'hex'),
            row_num,
            'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            0,
            1 + random() * 500,
            'splice-amulet',
            row_num % 789
        FROM generate_series(start_row, end_row) AS row_num;

        COMMIT;
            END LOOP;
    END $$;

-- 2. SvRewardCoupon : 2.5M rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, reward_amount
)
SELECT
    5, 0,
    'sv-reward-' || md5(row_num::text || 'x1'),
    'a1b2c3d4e5f6' || md5(row_num::text),
    'Splice.Amulet:SvRewardCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
            'round', (row_num * 11 + 3) % 3000,
            'weight', 1 + (row_num % 20),
            'beneficiary', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text)
    ),
    decode(lpad(md5(row_num::text || 'b1'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 120)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 3)::int, 'splice-amulet',
    (row_num * 11 + 3) % 3000,
    'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
    1 + (row_num % 20)
FROM generate_series(1, 2500000) AS row_num;

-- AppRewardCoupon: 2.5M rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, reward_amount, app_reward_is_featured
)
SELECT
    5, 0,
    'app-rew-' || md5(row_num::text || 'x2'),
    'f6e5d4c3b2a1' || md5((row_num + 999)::text),
    'Splice.Amulet:AppRewardCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'provider', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'amount', (0.001 + random() * 75)::numeric(28,10)::text,
            'round', (row_num * 17 + 5) % 4000,
            'featured', (row_num % 7 = 0),
            'appId', 'app-' || md5((row_num % 200)::text),
            'activityRef', md5(row_num::text || 'activity')
    ),
    decode(lpad(md5(row_num::text || 'b2'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 90)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 4)::int, 'splice-amulet',
    (row_num * 17 + 5) % 4000,
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
    0.001 + random() * 75,
    (row_num % 7 = 0)
FROM generate_series(1, 2500000) AS row_num;

-- ValidatorRewardCoupon: 2M rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, reward_amount
)
SELECT
    5, 0,
    'val-rew-' || md5(row_num::text || 'x3'),
    '1122334455667788' || md5((row_num + 5000)::text),
    'Splice.Amulet:ValidatorRewardCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'user', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'amount', (0.05 + random() * 25)::numeric(28,10)::text,
            'round', (row_num * 13 + 9) % 3500
    ),
    decode(lpad(md5(row_num::text || 'b3'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 75)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 2)::int, 'splice-amulet',
    (row_num * 13 + 9) % 3500,
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
    0.05 + random() * 25
FROM generate_series(1, 2000000) AS row_num;

-- LockedAmulet: 2M rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    amulet_round_of_expiry
)
SELECT
    5, 0,
    'locked-amulet-' || lpad(row_num::text, 20, '0'),
    '56ed13e658da77aad568b59c8fb8cca890f8bb26d473a43342340bc1306d547f',
    'Splice.Amulet:LockedAmulet',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'amulet', jsonb_build_object(
                    'owner', 'user_' || lpad((row_num % 200000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
                    'amount', jsonb_build_object(
                            'createdAt', jsonb_build_object('number', row_num % 5000),
                            'ratePerRound', jsonb_build_object('rate', '123.0000000000'),
                            'initialAmount', (10 + random() * 500)::numeric(28,10)::text
                              )
                      ),
            'lock', jsonb_build_object(
                    'holders', jsonb_build_array(
                    'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text)
                               ),
                    'expiresAt', (1748800000000000 + (row_num::bigint * 600000000))::text
                    ),
            'timelockExpiresAt', row_num % 789 + 100
    ),
    decode('0A03322E3112C1050A45002A10285920CE60630D645A6A57F2552811BB3E', 'hex'),
    row_num,
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-amulet',
    row_num % 789
FROM generate_series(1, 2000000) AS row_num;

-- ValidatorFaucetCoupon: 1M rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    reward_round, reward_party, validator
)
SELECT
    5, 0,
    'val-faucet-' || md5(row_num::text || 'x4'),
    'aabbccdd11223344' || md5((row_num + 8000)::text),
    'Splice.Amulet:ValidatorFaucetCoupon',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'validator', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'round', (row_num * 19 + 2) % 2500
    ),
    decode(lpad(md5(row_num::text || 'b4'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 60)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 3)::int, 'splice-amulet',
    (row_num * 19 + 2) % 2500,
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text)
FROM generate_series(1, 1000000) AS row_num;

-- OpenMiningRound: 50k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    mining_round
)
SELECT
    5, 0,
    'open-round-' || md5(row_num::text || 'x5'),
    '99887766554433' || md5((row_num + 3000)::text),
    'Splice.Round:OpenMiningRound',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'round', row_num,
            'opensAt', row_num * (400 + (random() * 200)::int),
            'targetClosesAt', (row_num + 1) * (400 + (random() * 200)::int),
            'amuletPrice', (0.001 + random() * 0.5)::numeric(28,10)::text,
            'transferConfigUsd', jsonb_build_object(
                    'createFee', (0.001 + random() * 0.01)::numeric(28,10)::text,
                    'holdingFee', (0.0001 + random() * 0.001)::numeric(28,10)::text,
                    'transferFee', (0.001 + random() * 0.05)::numeric(28,10)::text
                                 )
    ),
    decode(lpad(md5(row_num::text || 'b5'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 180)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 2)::int, 'splice-amulet',
    row_num
FROM generate_series(1, 50000) AS row_num;

-- ClosedMiningRound: 50k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    mining_round
)
SELECT
    5, 0,
    'closed-round-' || md5(row_num::text || 'x6'),
    '11223344aabbccdd' || md5((row_num + 7000)::text),
    'Splice.Round:ClosedMiningRound',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'round', row_num,
            'closedAt', row_num * 500 + (random() * 100)::int,
            'numMintedAmulets', (100 + random() * 1000)::numeric(28,10)::text,
            'totalFees', (1 + random() * 50)::numeric(28,10)::text
    ),
    decode(lpad(md5(row_num::text || 'b6'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 180)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 2)::int, 'splice-amulet',
    row_num
FROM generate_series(1, 50000) AS row_num;

-- ValidatorLicense: 50k rows
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name,
    validator
)
SELECT
    5, 0,
    'val-license-' || md5(row_num::text || 'x7'),
    'deadbeef12345678' || md5((row_num + 2000)::text),
    'Splice.ValidatorLicense:ValidatorLicense',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'validator', 'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text),
            'sponsor', 'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
            'faucetState', jsonb_build_object(
                    'firstCollectedInRound', (row_num * 3) % 5000,
                    'numCouponsMissed', (random() * 10)::int,
                    'lastCollectedInRound', (row_num * 3) % 5000 + (random() * 200)::int
                           )
    ),
    decode(lpad(md5(row_num::text || 'b7'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 200)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    (random() * 3)::int, 'splice-amulet',
    'user_' || lpad((row_num % 10000)::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || (row_num % 10000)::text)
FROM generate_series(1, 50000) AS row_num;

-- SvNodeState: 50 rows (one per SV node)
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name
)
SELECT
    5, 0,
    'sv-node-state-' || lpad(row_num::text, 10, '0'),
    'aabb11223344' || md5('svnodestate' || row_num::text),
    'Splice.DSO.SvState:SvNodeState',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad(row_num::text, 3, '0') || '::1220' || md5('sv_' || row_num::text),
            'svName', 'Super Validator ' || row_num,
            'state', jsonb_build_object(
                    'synchronizerNodes', jsonb_build_object(
                    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
                    jsonb_build_object(
                            'sequencer', 'https://sequencer-sv' || row_num || '.example.com',
                            'mediator', 'https://mediator-sv' || row_num || '.example.com',
                            'scan', 'https://scan-sv' || row_num || '.example.com'
                    )
                                         ),
                    'participantId', 'participant-sv' || row_num || '::1220' || md5('participant_sv_' || row_num::text)
                     )
    ),
    decode(lpad(md5(row_num::text || 'svns'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 30)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-dso-governance'
FROM generate_series(1, 50) AS row_num;

-- SvRewardState: 50 rows (one per SV)
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name
)
SELECT
    5, 0,
    'sv-reward-state-' || lpad(row_num::text, 10, '0'),
    'ccdd55667788' || md5('svrewardstate' || row_num::text),
    'Splice.DSO.SvState:SvRewardState',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad(row_num::text, 3, '0') || '::1220' || md5('sv_' || row_num::text),
            'svName', 'Super Validator ' || row_num,
            'state', jsonb_build_object(
                    'numRoundsMissed', (random() * 100)::int,
                    'numRoundsCollected', (random() * 5000)::int,
                    'lastCollectedInRound', 4000 + (random() * 1000)::int,
                    'collectedWeight', 1 + (row_num % 30)
                     )
    ),
    decode(lpad(md5(row_num::text || 'svrs'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 30)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-dso-governance'
FROM generate_series(1, 50) AS row_num;

-- SvStatusReport: 5000 rows (many reports per SV over time)
INSERT INTO dso_acs_store (
    store_id, migration_id, contract_id, template_id_package_id,
    template_id_qualified_name, create_arguments, created_event_blob,
    created_at, assigned_domain, reassignment_counter, package_name
)
SELECT
    5, 0,
    'sv-status-report-' || lpad(row_num::text, 10, '0'),
    'eeff99001122' || md5('svstatusreport' || row_num::text),
    'Splice.DSO.SvState:SvStatusReport',
    jsonb_build_object(
            'dso', 'DSO-c2301d5d-c2301d5d::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
            'sv', 'sv_' || lpad((row_num % 50)::text, 3, '0') || '::1220' || md5('sv_' || (row_num % 50)::text),
            'round', row_num % 5000,
            'status', jsonb_build_object(
                    'openMiningRoundNumber', row_num % 5000,
                    'latestOpenMiningRound', row_num % 5000,
                    'isSynchronizerNodeOnline', (row_num % 20 != 0),
                    'participantSynchronized', (row_num % 50 != 0),
                    'cometBftHeight', 100000 + row_num * 10
                      )
    ),
    decode(lpad(md5(row_num::text || 'svsr'), 60, '0'), 'hex'),
    (1748800000000000 - (random() * 86400000000 * 60)::bigint),
    'global-domain::12208a4915bf573e93d38bebb84775adf304e434e1abc957b930da8f653ad221f0c8',
    0, 'splice-dso-governance'
FROM generate_series(1, 50000) AS row_num;

-- 3. create 200k parties to ignore in table dso_unavailable_parties
-- 3. create 200k parties to ignore in table dso_unavailable_parties
CREATE TABLE IF NOT EXISTS dso_unavailable_parties (
                                                       party_id VARCHAR NOT NULL,
                                                       added_at BIGINT NOT NULL,
                                                       store_id BIGINT NOT NULL,
                                                       ignore_duration BIGINT NOT NULL,
                                                       metadata JSONB,
                                                       PRIMARY KEY (party_id)
);

INSERT INTO dso_unavailable_parties (party_id, added_at, store_id, ignore_duration, metadata)
SELECT
    'user_' || lpad(n::text, 5, '0') || '__wallet__user::1220' || md5('owner_' || n::text),
    extract(epoch from now() - (random() * interval '30 days'))::bigint * 1000000,
    5,
    3600000000,  -- 1 hour in microseconds
    jsonb_build_object(
            'display_name', 'User ' || n,
            'region', (ARRAY['US', 'EU', 'APAC', 'LATAM'])[1 + (n % 4)],
            'tier', (ARRAY['basic', 'premium', 'enterprise'])[1 + (n % 3)]
    )
FROM generate_series(0, 199999) AS n
ON CONFLICT DO NOTHING;

-- 4. create index and redo anti-join in point 4.

-- CREATE INDEX idx_dso_acs_pn_tid_croe_owner
--     ON dso_acs_store (store_id, migration_id, package_name, template_id_qualified_name, amulet_round_of_expiry, (create_arguments->>'owner'))
--     WHERE amulet_round_of_expiry IS NOT NULL AND (create_arguments->>'owner') IS NOT NULL;

CREATE INDEX idx_dso_unavailable_parties_added_at ON dso_unavailable_parties (added_at);

CREATE INDEX idx_dso_unavailable_parties_expiry ON dso_unavailable_parties (party_id, (added_at + ignore_duration));

SET max_parallel_workers_per_gather = 0; -- avoid runnign in parallel
-- 5. check indices are used in the anti-join query
SELECT count(*) FROM dso_acs_store;
SELECT count(*) FROM dso_unavailable_parties;
DISCARD ALL;

EXPLAIN ANALYSE
SELECT *
FROM dso_acs_store d
WHERE d.store_id = 5
  AND d.migration_id = 0
  AND d.package_name = 'splice-amulet'
  AND d.template_id_qualified_name = 'Splice.Amulet:AmuletTransferInstruction'
  AND d.amulet_round_of_expiry < 900
  AND NOT EXISTS (
    SELECT 1
    FROM dso_unavailable_parties t
    WHERE t.party_id IN (d.create_arguments->'transfer'->>'sender', d.create_arguments->'transfer'->>'receiver') AND (extract(epoch from now()) * 1000000)::bigint < t.added_at + t.ignore_duration
)
ORDER BY d.amulet_round_of_expiry ASC
LIMIT 1000;

-- Query plan on postgres 18:
-- Limit  (cost=0.87..9489.21 rows=1000 width=1620) (actual time=0.087..13.050 rows=1000.00 loops=1)
--   Buffers: shared hit=5929
--   ->  Nested Loop Anti Join  (cost=0.87..2102143.53 rows=221550 width=1620) (actual time=0.086..12.945 rows=1000.00 loops=1)
--         Buffers: shared hit=5929
--         ->  Index Scan using dso_acs_store_sid_pn_tid_croe on dso_acs_store d  (cost=0.43..303106.53 rows=664657 width=1620) (actual time=0.025..1.164 rows=1003.00 loops=1)
--               Index Cond: ((store_id = 5) AND (migration_id = 0) AND (package_name = 'splice-amulet'::text) AND (template_id_qualified_name = 'Splice.Amulet:AmuletTransferInstruction'::text) AND (amulet_round_of_expiry < 900))
--               Index Searches: 1
--               Buffers: shared hit=1006
--         ->  Index Scan using idx_dso_unavailable_parties_expiry on dso_unavailable_parties t  (cost=0.44..2.70 rows=1 width=63) (actual time=0.011..0.011 rows=0.00 loops=1003)
-- "              Index Cond: (((party_id)::text = ANY ((ARRAY[(((d.create_arguments -> 'transfer'::text) ->> 'sender'::text))::character varying, (((d.create_arguments -> 'transfer'::text) ->> 'receiver'::text))::character varying])::text[])) AND ((added_at + ignore_duration) > ((EXTRACT(epoch FROM now()) * '1000000'::numeric))::bigint))"
--               Index Searches: 1640
--               Buffers: shared hit=4923
-- Planning:
--   Buffers: shared hit=10
-- Planning Time: 0.395 ms
-- Execution Time: 13.149 ms
-- Query plan on postgres 14:
-- Limit  (cost=0.87..8942.64 rows=1000 width=1620) (actual time=0.066..15.115 rows=1000 loops=1)
--   ->  Nested Loop Anti Join  (cost=0.87..5849418.51 rows=654168 width=1620) (actual time=0.065..15.039 rows=1000 loops=1)
--         ->  Index Scan using dso_acs_store_sid_pn_tid_croe on dso_acs_store d  (cost=0.43..949552.69 rows=655755 width=1620) (actual time=0.031..1.226 rows=1002 loops=1)
--               Index Cond: ((store_id = 5) AND (migration_id = 0) AND (package_name = 'splice-amulet'::text) AND (template_id_qualified_name = 'Splice.Amulet:AmuletTransferInstruction'::text) AND (amulet_round_of_expiry < 900))
--         ->  Index Scan using idx_dso_unavailable_parties_expiry on dso_unavailable_parties t  (cost=0.44..7.47 rows=1 width=63) (actual time=0.011..0.011 rows=0 loops=1002)
-- "              Index Cond: (((party_id)::text = ANY ((ARRAY[(((d.create_arguments -> 'transfer'::text) ->> 'sender'::text))::character varying, (((d.create_arguments -> 'transfer'::text) ->> 'receiver'::text))::character varying])::text[])) AND ((added_at + ignore_duration) > ((EXTRACT(epoch FROM now()) * '1000000'::numeric))::bigint))"
-- Planning Time: 0.451 ms
-- Execution Time: 15.227 ms


-- 6. helpers
select template_id_qualified_name, count(*) from dso_acs_store group by template_id_qualified_name

SET random_page_cost = 1.1;

SELECT count (*)
FROM dso_acs_store d
WHERE d.package_name = 'splice-amulet'
  AND d.template_id_qualified_name = 'Splice.Amulet:Amulet'

SHOW server_version;

ANALYZE dso_acs_store;
ANALYZE dso_unavailable_parties;

set enable_seqscan=off; -- disable seq scan to force index usage

DISCARD ALL;

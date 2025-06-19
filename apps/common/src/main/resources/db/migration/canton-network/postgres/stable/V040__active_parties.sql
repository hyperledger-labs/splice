create table active_parties
(
    store_id          int not null,
    party             text not null,
    closed_round      bigint not null,
    primary key       (store_id, party)
);

create index active_parties_sid_p_cr_idx
    on active_parties (store_id, party, closed_round);

create or replace procedure initialize_active_parties()
language plpgsql
as $$
begin
    if not exists (select 1 from active_parties limit 1) then
        insert into active_parties (store_id, party, closed_round)
        select store_id, party, max(closed_round)
        from round_party_totals
        group by store_id, party;
    end if;
end
$$;

grant execute on procedure initialize_active_parties() to public;

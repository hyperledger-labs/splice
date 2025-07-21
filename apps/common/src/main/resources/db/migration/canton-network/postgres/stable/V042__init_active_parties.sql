-- updated to do nothing on conflict
create or replace procedure initialize_active_parties()
    language plpgsql
as $$
begin
    if not exists (select 1 from active_parties limit 1) then
        insert into active_parties (store_id, party, closed_round)
        select store_id, party, max(closed_round)
        from round_party_totals
        group by store_id, party
        on conflict (store_id, party) do nothing;
    end if;
end
$$;

grant execute on procedure initialize_active_parties() to public;

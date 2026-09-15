CREATE FUNCTION status_list_apply_bytes(d BYTEA, idx INTEGER[], clr INTEGER[], st INTEGER[]) RETURNS BYTEA
    LANGUAGE plpgsql IMMUTABLE STRICT AS
$$
DECLARE
    n INTEGER := coalesce(array_length(idx, 1), 0);
    k INTEGER;
BEGIN
    IF coalesce(array_length(clr, 1), 0) <> n OR coalesce(array_length(st, 1), 0) <> n THEN
        RAISE EXCEPTION 'status_list_apply_bytes: parallel arrays differ in length';
    END IF;
    FOR k IN 1..n LOOP
        d := set_byte(d, idx[k], (get_byte(d, idx[k]) & clr[k]) | st[k]);
    END LOOP;
    RETURN d;
END
$$;

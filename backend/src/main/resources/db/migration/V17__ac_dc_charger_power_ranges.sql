ALTER TABLE ports DROP CONSTRAINT ports_max_power_kw_check;

UPDATE ports
SET max_power_kw = CASE charging_mode
    WHEN 'AC' THEN greatest(10, least(max_power_kw, 22))
    WHEN 'DC' THEN greatest(30, least(max_power_kw, 150))
END;

ALTER TABLE ports
    ADD CONSTRAINT ports_mode_power_range_ck CHECK (
        (charging_mode = 'AC' AND max_power_kw BETWEEN 10 AND 22)
        OR (charging_mode = 'DC' AND max_power_kw BETWEEN 30 AND 150)
    );

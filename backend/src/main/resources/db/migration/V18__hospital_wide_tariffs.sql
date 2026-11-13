-- The hospital charging network now uses one tariff schedule across the whole site.
UPDATE tariffs SET station_id = NULL WHERE station_id IS NOT NULL;

ALTER TABLE tariffs
    ADD CONSTRAINT tariffs_hospital_wide_ck CHECK (station_id IS NULL);

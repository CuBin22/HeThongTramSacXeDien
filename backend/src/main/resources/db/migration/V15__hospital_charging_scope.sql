-- Rename the station created by the legacy campus demo seed. No charging data is removed.
UPDATE stations
SET name = 'Bãi xe Bệnh viện'
WHERE name = 'Nhà xe thư viện';

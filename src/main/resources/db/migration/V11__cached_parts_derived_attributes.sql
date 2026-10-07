-- Cached payloads hold the distributor's attributes only (DESIGN.md 3.2 "Cache model", 8): ParametricExtractor.enrich
-- derives the comparable attributes again on every read, so a cached part always shows the values of the running
-- extractor. Rows written before this release also carry the derived attributes. Remove the keys only KINA writes
-- (ParametricExtractor.DERIVED_ONLY_KEYS), so those rows are derived afresh too; keys a distributor may also send
-- (Capacitance, Mounting...) stay until the part is fetched again.
UPDATE cached_parts
SET payload = jsonb_set(payload, '{attributes}', (payload -> 'attributes') - ARRAY[
        'Family', 'Subtype', 'FormFactor', 'Elements', 'RatedCurrent', 'SaturationCurrent', 'MaxTemperature',
        'RippleCurrent', 'OperatingTemperature', 'ConnectorType', 'UsbType', 'UsbStandard', 'UsbSpeedGbps',
        'PinConfiguration', 'ShieldPinsCounted', 'MountingStyle'])
WHERE jsonb_typeof(payload -> 'attributes') = 'object'
  AND (payload -> 'attributes') <> (payload -> 'attributes') - ARRAY[
        'Family', 'Subtype', 'FormFactor', 'Elements', 'RatedCurrent', 'SaturationCurrent', 'MaxTemperature',
        'RippleCurrent', 'OperatingTemperature', 'ConnectorType', 'UsbType', 'UsbStandard', 'UsbSpeedGbps',
        'PinConfiguration', 'ShieldPinsCounted', 'MountingStyle'];

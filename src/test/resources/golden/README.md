# Old-jar golden saves

The GameTest logs `GOLDEN-SAVES-SKIPPED` while this folder has no sample
subdirectories. A sample directory is mandatory once added: missing/corrupt files
fail, rather than silently skipping it. Each old jar gets its own directory:

- `player.dat`: untouched compressed NBT from `playerdata/<uuid>.dat`.
- `data/murim_wanderers.dat`: untouched compressed overworld SavedData.
- `expected.json`: values recorded while playing that jar, independently of the
  new decoder. Required keys: `player_uuid` (UUID string), `member` (boolean),
  `rank` (integer), `stars` (object mapping every learned form id to integer stars),
  `loadout` (complete Loadout codec JSON), `pills` (complete PillState codec JSON),
  `penance` (boolean, sect flag `penance.sentenced`), and `wanderer` (object with
  `entity` UUID string or null, `visits`, `next_at`, `expires_at`, `last_dist`,
  `extra`, `map_given`). Missing old optional wanderer fields have zero/false values.

Choose a progressed member with nonzero rank/stars, nonempty loadout and pill
history, an existing wanderer entry and active penance. Record jar version and
setup steps in a README inside the sample. Do not regenerate saves with the new
jar. Include additional samples with penance false as useful.

The test loads a detached ServerPlayer through Entity.load (including NeoForge
attachments), then the level's Wanderer.Data through its production loader. It
does not import chunks or overwrite the running GameTest world's saved data.
Run from the repository or a child directory, as with the registry snapshot test.

// Incompatible fixture: exposes popularNovels, so the host's load gate
// passes, but exposes neither parse member, so the v1-era adapter's
// generation check must reject it loudly as not a v1-era plugin.
exports.default = {
  id: "fixture-v1era-no-contract",
  name: "No Contract",
  popularNovels: async function () {
    return { novels: [] };
  },
};

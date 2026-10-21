// Incompatible fixture: exposes no plugin contract members at all, so the
// host's load gate must reject it as not a valid plugin object.
exports.default = {
  id: "fixture-no-contract",
  name: "No Contract",
};

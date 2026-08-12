// Architecture §6's second privacy layer: the consumer validates that nothing private arrived,
// even though nothing private can have arrived.
//
// `publicPayload` runs inside room-gameplay, in the same transaction that writes the event, so by
// the time a body reaches this service the seed is already gone. This check is therefore expected
// to never fire — which is exactly why it is worth having and worth counting. A rejection here is
// not a nuisance to be tuned out; it means the filter three services upstream stopped working, and
// the only thing worse than finding that out here is not finding it out at all.
//
// Defence in depth across a service boundary is worth one `if`. Defence in depth inside one service
// would just be the same check twice.

const PRIVATE_FIELDS = ["hand", "cards", "deckOrder", "rngSeed", "seed"];

export function privateFields(body: Record<string, unknown>): string[] {
  return PRIVATE_FIELDS.filter((field) => field in body);
}

export function isClean(body: Record<string, unknown>): boolean {
  return privateFields(body).length === 0;
}

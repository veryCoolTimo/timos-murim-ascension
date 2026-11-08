/**
 * The body (docs/design/27-body-training.md): it grows from ordinary play — walking, sprinting, jumping, mining, felling
 * trees, fighting — read from the vanilla statistics ({@code VanillaBody}, {@code BodyActivity}, {@code BodyProbe},
 * {@code BodySource}), plus three explicit sect drills (the training stone carried uphill, the South Peak climb, the trail
 * sprint). The tempered body ({@link io.github.verycooltimo.murim.training.BodyState}) feeds the breakthrough gates.
 * Pure rules ({@code BodyRules}, {@code BodyActivity}, {@code RouteRun}) are unit-tested; the server glue is
 * {@code TrainingService}; numbers live in {@code TrainingBalance}.
 */
package io.github.verycooltimo.murim.training;

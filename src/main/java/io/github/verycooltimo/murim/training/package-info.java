/**
 * Body training (docs/design/27-body-training.md): squats, push-ups (weighted), horse stance, carrying the training
 * stone, the South Peak climb and the trail sprint; the tempered body ({@link io.github.verycooltimo.murim.training.BodyState})
 * with daily diminishing returns, fatigue and recovery. Pure rules ({@code BodyRules}, {@code SetMachine},
 * {@code RouteRun}) are unit-tested; the server glue is {@code TrainingService}; numbers live in {@code TrainingBalance}.
 */
package io.github.verycooltimo.murim.training;

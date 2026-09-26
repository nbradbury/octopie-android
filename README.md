# OctoPie

Tap the falling pies and the octopus reaches out a tentacle to grab them. Pies fall faster and in bigger bunches as the levels climb; miss three and the octopus pops.

Kotlin + Jetpack Compose, single `Canvas` game loop. Game rules live in `GameEngine` (plain Kotlin, unit tested).

## Assets
Octopus and pie art were generated with Adobe Firefly. `tools/prepare_assets.py` cuts them out, builds the blurred reef background and the surprised face, and writes the drawables.

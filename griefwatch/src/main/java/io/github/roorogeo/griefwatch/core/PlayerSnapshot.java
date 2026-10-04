package io.github.roorogeo.griefwatch.core;

import java.util.UUID;

/** Name and UUID of an online player, copied off the game objects on the server thread. */
public record PlayerSnapshot(String name, UUID id) {
}

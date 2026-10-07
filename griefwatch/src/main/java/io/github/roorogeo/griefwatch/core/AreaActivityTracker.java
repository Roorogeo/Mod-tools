package io.github.roorogeo.griefwatch.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Detects large-scale world changes that happen without a player clicking each block, such as
 * a forest fire burning down or a lava cast forming cobblestone, and attributes them to the
 * player who most likely started them.
 *
 * <p>Players register <em>sources</em> (lighting a fire, emptying a lava or water bucket).
 * Each changed block is an <em>activity</em> event. Events in the same dimension join an open
 * cluster when they fall within {@code joinRadius} of the cluster's bounding box, so a spreading
 * fire stays one cluster however far it travels. A new cluster is attributed to the most recent
 * source near its first event. A cluster is reported once its count reaches
 * {@code alertThreshold}, and summarised when it has been idle for {@code idleMillis}.
 *
 * <p>Not thread-safe: only used from the server thread.
 */
public final class AreaActivityTracker {
	private static final int MAX_SOURCES = 2048;
	private static final int MAX_CLUSTERS = 1024;

	/**
	 * @param sourceRadius    horizontal distance from a source within which a new cluster is attributed to it
	 * @param sourceHeight    vertical distance allowed between source and cluster (lava casts form far below the pour)
	 * @param joinRadius      distance from a cluster's bounding box within which an event joins it
	 * @param alertThreshold  number of events before the cluster is reported
	 * @param idleMillis      a cluster closes after this long without events
	 * @param sourceMaxAgeMillis how long a source can be used for attribution
	 * @param maxClusterMillis   hard cap on cluster lifetime
	 */
	public record Settings(boolean enabled, double sourceRadius, double sourceHeight, double joinRadius,
			int alertThreshold, long idleMillis, long sourceMaxAgeMillis, long maxClusterMillis) {
	}

	/** Who a cluster is blamed on; {@code null} fields when nobody could be identified. */
	public record Attribution(String playerName, UUID playerId, int sourceX, int sourceY, int sourceZ) {
	}

	/**
	 * A report about a cluster.
	 *
	 * @param summary false for the threshold alert, true for the final summary when the cluster closes
	 */
	public record Report(String kind, String dimension, Attribution attribution, int count,
			int centerX, int centerY, int centerZ, int sizeX, int sizeY, int sizeZ,
			long durationMillis, boolean summary) {
	}

	private record Source(String name, UUID id, String dimension, int x, int y, int z, long at) {
	}

	private final class Cluster {
		final String dimension;
		final Attribution attribution;
		final long startedAt;
		long lastAt;
		int count;
		double sumX, sumY, sumZ;
		int minX, minY, minZ, maxX, maxY, maxZ;
		boolean alerted;

		Cluster(String dimension, Attribution attribution, int x, int y, int z, long now) {
			this.dimension = dimension;
			this.attribution = attribution;
			this.startedAt = now;
			minX = maxX = x;
			minY = maxY = y;
			minZ = maxZ = z;
		}

		void add(int x, int y, int z, long now) {
			count++;
			sumX += x;
			sumY += y;
			sumZ += z;
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			minZ = Math.min(minZ, z);
			maxX = Math.max(maxX, x);
			maxY = Math.max(maxY, y);
			maxZ = Math.max(maxZ, z);
			lastAt = now;
		}

		/** Squared distance from the point to this cluster's bounding box (0 inside). */
		double distanceSq(int x, int y, int z) {
			double dx = Math.max(0, Math.max(minX - x, x - maxX));
			double dy = Math.max(0, Math.max(minY - y, y - maxY));
			double dz = Math.max(0, Math.max(minZ - z, z - maxZ));
			return dx * dx + dy * dy + dz * dz;
		}

		Report report(boolean summary) {
			return new Report(kind, dimension, attribution, count,
					(int) Math.round(sumX / count), (int) Math.round(sumY / count), (int) Math.round(sumZ / count),
					maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, lastAt - startedAt, summary);
		}
	}

	private final String kind;
	private final ArrayDeque<Source> sources = new ArrayDeque<>();
	private final List<Cluster> clusters = new ArrayList<>();

	/** @param kind label carried in reports, e.g. "fire" or "cast" */
	public AreaActivityTracker(String kind) {
		this.kind = kind;
	}

	public void recordSource(String playerName, UUID playerId, String dimension, int x, int y, int z, long now) {
		if (sources.size() >= MAX_SOURCES) {
			sources.pollFirst();
		}
		sources.addLast(new Source(playerName, playerId, dimension, x, y, z, now));
	}

	/** Records one changed block; any alert it triggers is added to {@code out}. */
	public void submit(String dimension, int x, int y, int z, long now, Settings s, List<Report> out) {
		if (!s.enabled()) {
			return;
		}
		Cluster best = null;
		double bestDist = s.joinRadius() * s.joinRadius();
		for (Cluster c : clusters) {
			if (!c.dimension.equals(dimension) || isExpired(c, now, s)) continue;
			double d = c.distanceSq(x, y, z);
			if (d <= bestDist) {
				best = c;
				bestDist = d;
			}
		}
		if (best == null) {
			if (clusters.size() >= MAX_CLUSTERS) {
				closeOldest(out);
			}
			best = new Cluster(dimension, attribute(dimension, x, y, z, now, s), x, y, z, now);
			clusters.add(best);
		}
		best.add(x, y, z, now);
		if (!best.alerted && best.count >= s.alertThreshold()) {
			best.alerted = true;
			out.add(best.report(false));
		}
	}

	/** Closes idle clusters (summarising the ones that alerted) and forgets old sources. */
	public void sweep(long now, Settings s, List<Report> out) {
		for (Iterator<Cluster> it = clusters.iterator(); it.hasNext(); ) {
			Cluster c = it.next();
			if (!s.enabled() || isExpired(c, now, s)) {
				it.remove();
				if (c.alerted) {
					out.add(c.report(true));
				}
			}
		}
		while (!sources.isEmpty() && now - sources.peekFirst().at() > s.sourceMaxAgeMillis()) {
			sources.pollFirst();
		}
	}

	public void closeAll(List<Report> out) {
		for (Cluster c : clusters) {
			if (c.alerted) {
				out.add(c.report(true));
			}
		}
		clusters.clear();
	}

	public int openClusters() {
		return clusters.size();
	}

	private Attribution attribute(String dimension, int x, int y, int z, long now, Settings s) {
		double r2 = s.sourceRadius() * s.sourceRadius();
		// Newest first: the latest player to start something here is the best guess.
		for (Iterator<Source> it = sources.descendingIterator(); it.hasNext(); ) {
			Source src = it.next();
			if (now - src.at() > s.sourceMaxAgeMillis()) break;
			if (!src.dimension().equals(dimension)) continue;
			double dx = src.x() - x, dz = src.z() - z;
			if (dx * dx + dz * dz <= r2 && Math.abs(src.y() - y) <= s.sourceHeight()) {
				return new Attribution(src.name(), src.id(), src.x(), src.y(), src.z());
			}
		}
		return new Attribution(null, null, 0, 0, 0);
	}

	private static boolean isExpired(Cluster c, long now, Settings s) {
		return now - c.lastAt > s.idleMillis() || now - c.startedAt >= s.maxClusterMillis();
	}

	private void closeOldest(List<Report> out) {
		Cluster oldest = null;
		for (Cluster c : clusters) {
			if (oldest == null || c.lastAt < oldest.lastAt) oldest = c;
		}
		if (oldest != null) {
			clusters.remove(oldest);
			if (oldest.alerted) out.add(oldest.report(true));
		}
	}
}

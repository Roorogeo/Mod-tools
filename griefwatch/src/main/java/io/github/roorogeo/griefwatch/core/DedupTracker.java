package io.github.roorogeo.griefwatch.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Groups events from the same player, of the same type, in the same dimension, that happen
 * close together in space and time. The first event of a group is reported immediately; when
 * the group closes, a summary is reported if more than one event was merged into it.
 *
 * <p>Not thread-safe: only used from the server thread.
 */
public final class DedupTracker {
	/** Upper bound on open groups so a flood of distinct events can't grow memory without limit. */
	private static final int MAX_OPEN_GROUPS = 4096;

	public record Settings(boolean enabled, double radius, long windowMillis, long maxGroupMillis) {
	}

	/** A closed group that merged {@code count} events. */
	public record Summary(GriefEvent first, int count, int centerX, int centerY, int centerZ, long durationMillis) {
	}

	private static final class Group {
		final GriefEvent first;
		final long startedAt;
		long lastAt;
		int count;
		double sumX, sumY, sumZ;

		Group(GriefEvent first, long now) {
			this.first = first;
			this.startedAt = now;
			add(first, now);
		}

		void add(GriefEvent e, long now) {
			count++;
			sumX += e.x();
			sumY += e.y();
			sumZ += e.z();
			lastAt = now;
		}

		double cx() { return sumX / count; }
		double cy() { return sumY / count; }
		double cz() { return sumZ / count; }

		Summary summary() {
			return new Summary(first, count, (int) Math.round(cx()), (int) Math.round(cy()), (int) Math.round(cz()), lastAt - startedAt);
		}
	}

	private final Map<String, List<Group>> groups = new HashMap<>();
	private int openGroups;

	/**
	 * @return true if this event started a new group and should be logged now, false if it
	 *         was merged into an open group.
	 */
	public boolean submit(GriefEvent event, long now, Settings settings, List<Summary> closedOut) {
		if (!settings.enabled()) {
			return true;
		}
		List<Group> list = groups.computeIfAbsent(event.groupKey(), k -> new ArrayList<>(2));
		double r2 = settings.radius() * settings.radius();
		for (Iterator<Group> it = list.iterator(); it.hasNext(); ) {
			Group g = it.next();
			if (isExpired(g, now, settings)) {
				it.remove();
				openGroups--;
				closeInto(g, closedOut);
				continue;
			}
			double dx = event.x() - g.cx(), dy = event.y() - g.cy(), dz = event.z() - g.cz();
			if (dx * dx + dy * dy + dz * dz <= r2) {
				g.add(event, now);
				return false;
			}
		}
		if (openGroups >= MAX_OPEN_GROUPS) {
			evictOldest(closedOut);
		}
		list.add(new Group(event, now));
		openGroups++;
		return true;
	}

	/** Closes every group whose window has passed and drops empty buckets. */
	public void sweep(long now, Settings settings, List<Summary> closedOut) {
		for (Iterator<List<Group>> buckets = groups.values().iterator(); buckets.hasNext(); ) {
			List<Group> list = buckets.next();
			for (Iterator<Group> it = list.iterator(); it.hasNext(); ) {
				Group g = it.next();
				if (!settings.enabled() || isExpired(g, now, settings)) {
					it.remove();
					openGroups--;
					closeInto(g, closedOut);
				}
			}
			if (list.isEmpty()) {
				buckets.remove();
			}
		}
	}

	/** Closes all groups regardless of age (server shutdown). */
	public void closeAll(List<Summary> closedOut) {
		for (List<Group> list : groups.values()) {
			for (Group g : list) {
				closeInto(g, closedOut);
			}
		}
		groups.clear();
		openGroups = 0;
	}

	public int openGroups() {
		return openGroups;
	}

	private static boolean isExpired(Group g, long now, Settings s) {
		return now - g.lastAt > s.windowMillis() || now - g.startedAt >= s.maxGroupMillis();
	}

	private static void closeInto(Group g, List<Summary> closedOut) {
		if (g.count > 1) {
			closedOut.add(g.summary());
		}
	}

	private void evictOldest(List<Summary> closedOut) {
		List<Group> oldestList = null;
		Group oldest = null;
		for (List<Group> list : groups.values()) {
			for (Group g : list) {
				if (oldest == null || g.lastAt < oldest.lastAt) {
					oldest = g;
					oldestList = list;
				}
			}
		}
		if (oldest != null) {
			oldestList.remove(oldest);
			openGroups--;
			closeInto(oldest, closedOut);
		}
	}
}

package io.github.roorogeo.griefwatch.output;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sends embeds to Discord webhooks from a single background thread.
 *
 * <p>The server thread only calls {@link #enqueue}, which never blocks on the network. The
 * queue is bounded; when full the oldest message is dropped. Consecutive embeds for the same
 * webhook are batched (up to Discord's 10 embeds / 6000 characters per message). HTTP 429
 * responses are retried after Discord's {@code retry_after}; the rate-limit headers are honoured
 * pre-emptively; network errors and 5xx responses are retried a few times with backoff.
 */
public final class DiscordWebhook {
	private static final int MAX_EMBEDS_PER_MESSAGE = 10;
	private static final int MAX_CHARS_PER_MESSAGE = 5500; // Discord's limit is 6000 across all embeds
	private static final int MAX_FAILURE_ATTEMPTS = 3;
	private static final int MAX_RATE_LIMIT_RETRIES = 10;
	private static final long WARN_INTERVAL_MILLIS = 60_000;

	private record Message(String url, JsonObject embed, int size) {
	}

	private final Logger log;
	private final HttpClient client;
	private final ArrayDeque<Message> queue = new ArrayDeque<>();
	private final Object lock = new Object();
	private final Thread thread;

	private volatile int maxQueueSize = 200;
	private volatile String username = "GriefWatch";
	private volatile String avatarUrl = "";
	private volatile boolean stopping;
	private volatile long stopDeadline;

	private long dropped;
	private long lastDropWarnAt;
	private long lastErrorWarnAt;

	public DiscordWebhook(Logger log) {
		this.log = log;
		this.client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		this.thread = new Thread(this::run, "GriefWatch-Discord");
		this.thread.setDaemon(true);
	}

	public void start() {
		thread.start();
	}

	public void configure(int maxQueueSize, String username, String avatarUrl) {
		this.maxQueueSize = Math.max(1, maxQueueSize);
		this.username = username == null ? "" : username;
		this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
	}

	/** Queues an embed for {@code url}. Does nothing if the URL is blank. Never blocks. */
	public void enqueue(String url, JsonObject embed) {
		if (url == null || url.isBlank() || stopping) {
			return;
		}
		Message message = new Message(url, embed, embed.toString().length());
		synchronized (lock) {
			while (queue.size() >= maxQueueSize) {
				queue.pollFirst();
				dropped++;
			}
			queue.addLast(message);
			lock.notifyAll();
		}
	}

	public int queued() {
		synchronized (lock) {
			return queue.size();
		}
	}

	/** Tries to deliver what is still queued for up to {@code timeoutMillis}, then stops. */
	public void shutdown(long timeoutMillis) {
		stopDeadline = System.currentTimeMillis() + timeoutMillis;
		stopping = true;
		synchronized (lock) {
			lock.notifyAll();
		}
		try {
			thread.join(timeoutMillis + 1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		thread.interrupt();
	}

	private void run() {
		while (true) {
			List<Message> batch;
			try {
				batch = takeBatch();
			} catch (InterruptedException e) {
				if (stopping) {
					return;
				}
				continue;
			}
			if (batch == null) {
				return;
			}
			try {
				send(batch);
			} catch (InterruptedException e) {
				if (stopping) {
					return;
				}
			} catch (RuntimeException e) {
				warnError("Unexpected error sending to Discord: " + e);
			}
		}
	}

	/** Blocks until messages are available; returns null when it is time to stop. */
	private List<Message> takeBatch() throws InterruptedException {
		synchronized (lock) {
			while (queue.isEmpty()) {
				if (stopping) {
					return null;
				}
				lock.wait(5000);
			}
			if (stopping && System.currentTimeMillis() > stopDeadline) {
				return null;
			}
			reportDrops();
			List<Message> batch = new ArrayList<>();
			Message first = queue.pollFirst();
			batch.add(first);
			int chars = first.size();
			while (batch.size() < MAX_EMBEDS_PER_MESSAGE) {
				Message next = queue.peekFirst();
				if (next == null || !next.url().equals(first.url()) || chars + next.size() > MAX_CHARS_PER_MESSAGE) {
					break;
				}
				queue.pollFirst();
				batch.add(next);
				chars += next.size();
			}
			return batch;
		}
	}

	private void send(List<Message> batch) throws InterruptedException {
		String url = batch.getFirst().url();
		URI uri;
		try {
			uri = URI.create(url);
		} catch (IllegalArgumentException e) {
			warnError("Invalid Discord webhook URL, dropping " + batch.size() + " message(s): " + e.getMessage());
			return;
		}

		JsonObject payload = new JsonObject();
		if (!username.isBlank()) {
			payload.addProperty("username", username);
		}
		if (!avatarUrl.isBlank()) {
			payload.addProperty("avatar_url", avatarUrl);
		}
		JsonArray embeds = new JsonArray();
		for (Message m : batch) {
			embeds.add(m.embed());
		}
		payload.add("embeds", embeds);
		JsonObject mentions = new JsonObject();
		mentions.add("parse", new JsonArray()); // never ping anyone, whatever a player names themselves
		payload.add("allowed_mentions", mentions);

		HttpRequest request;
		try {
			request = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(15))
					.header("Content-Type", "application/json")
					.header("User-Agent", "GriefWatch (Fabric mod)")
					.POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
					.build();
		} catch (IllegalArgumentException e) {
			warnError("Invalid Discord webhook URL, dropping " + batch.size() + " message(s): " + e.getMessage());
			return;
		}

		int failures = 0;
		int rateLimited = 0;
		while (true) {
			HttpResponse<String> response;
			try {
				response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			} catch (IOException e) {
				failures++;
				if (failures >= MAX_FAILURE_ATTEMPTS || stopping) {
					warnError("Discord unreachable (" + e + "); dropped " + batch.size() + " message(s).");
					return;
				}
				pause(2000L << (failures - 1));
				continue;
			}

			int status = response.statusCode();
			if (status >= 200 && status < 300) {
				honourRateLimitHeaders(response);
				return;
			}
			if (status == 429) {
				rateLimited++;
				if (rateLimited > MAX_RATE_LIMIT_RETRIES) {
					warnError("Discord kept rate limiting; dropped " + batch.size() + " message(s).");
					return;
				}
				pause(retryAfterMillis(response));
				continue;
			}
			if (status >= 500) {
				failures++;
				if (failures >= MAX_FAILURE_ATTEMPTS || stopping) {
					warnError("Discord returned HTTP " + status + "; dropped " + batch.size() + " message(s).");
					return;
				}
				pause(2000L << (failures - 1));
				continue;
			}
			// 400/401/403/404: retrying won't help (bad or deleted webhook, rejected payload).
			warnError("Discord rejected webhook request with HTTP " + status + " (check the webhook URL): "
					+ abbreviate(response.body(), 200));
			return;
		}
	}

	/** When the bucket is exhausted, wait for it to reset instead of provoking a 429. */
	private void honourRateLimitHeaders(HttpResponse<String> response) throws InterruptedException {
		Optional<String> remaining = response.headers().firstValue("X-RateLimit-Remaining");
		if (remaining.isPresent() && remaining.get().trim().equals("0")) {
			Optional<String> resetAfter = response.headers().firstValue("X-RateLimit-Reset-After");
			pause(resetAfter.map(DiscordWebhook::secondsToMillis).orElse(1000L));
		}
	}

	static long retryAfterMillis(HttpResponse<String> response) {
		String body = response.body();
		if (body != null && !body.isBlank()) {
			try {
				JsonElement parsed = JsonParser.parseString(body);
				if (parsed.isJsonObject() && parsed.getAsJsonObject().has("retry_after")) {
					return clampRetry((long) Math.ceil(parsed.getAsJsonObject().get("retry_after").getAsDouble() * 1000.0));
				}
			} catch (RuntimeException ignored) {
				// fall through to the header
			}
		}
		return response.headers().firstValue("Retry-After").map(DiscordWebhook::secondsToMillis).orElse(5000L);
	}

	private static long secondsToMillis(String seconds) {
		try {
			return clampRetry((long) Math.ceil(Double.parseDouble(seconds.trim()) * 1000.0));
		} catch (NumberFormatException e) {
			return 1000L;
		}
	}

	private static long clampRetry(long millis) {
		return Math.max(250L, Math.min(millis, 10L * 60_000L));
	}

	/** Sleeps, but never past the shutdown deadline. */
	private void pause(long millis) throws InterruptedException {
		long wait = millis;
		if (stopping) {
			wait = Math.min(wait, stopDeadline - System.currentTimeMillis());
			if (wait <= 0) {
				throw new InterruptedException("shutting down");
			}
		}
		Thread.sleep(wait);
	}

	private void reportDrops() {
		long now = System.currentTimeMillis();
		if (dropped > 0 && now - lastDropWarnAt > WARN_INTERVAL_MILLIS) {
			log.warn("[GriefWatch] Discord queue full; dropped {} oldest message(s).", dropped);
			dropped = 0;
			lastDropWarnAt = now;
		}
	}

	private void warnError(String message) {
		long now = System.currentTimeMillis();
		if (now - lastErrorWarnAt > WARN_INTERVAL_MILLIS) {
			lastErrorWarnAt = now;
			log.warn("[GriefWatch] {}", message);
		} else {
			log.debug("[GriefWatch] {}", message);
		}
	}

	private static String abbreviate(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}
}

package io.github.roorogeo.griefwatch.output;

import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Appends lines to logs/griefwatch/griefwatch-YYYY-MM-DD.log from a background thread.
 * The server thread only enqueues strings.
 */
public final class FileLogger {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final int MAX_PENDING = 10_000;

	private record Line(LocalDateTime time, String text) {
	}

	private final Logger log;
	private final BlockingQueue<Line> queue = new LinkedBlockingQueue<>(MAX_PENDING);
	private final Thread thread;
	private volatile Path directory;
	private volatile boolean running = true;

	private LocalDate openDate;
	private Path openDirectory;
	private BufferedWriter writer;
	private long lastErrorAt;

	public FileLogger(Path directory, Logger log) {
		this.directory = directory;
		this.log = log;
		this.thread = new Thread(this::run, "GriefWatch-FileLog");
		this.thread.setDaemon(true);
	}

	public void start() {
		thread.start();
	}

	public void setDirectory(Path directory) {
		this.directory = directory;
	}

	/** Never blocks; drops the line if the writer has fallen far behind. */
	public void log(String text) {
		if (!queue.offer(new Line(LocalDateTime.now(), text))) {
			queue.poll();
			queue.offer(new Line(LocalDateTime.now(), text));
		}
	}

	/** Stops the writer after flushing what is queued, waiting at most {@code timeoutMillis}. */
	public void shutdown(long timeoutMillis) {
		running = false;
		thread.interrupt();
		try {
			thread.join(timeoutMillis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void run() {
		while (running || !queue.isEmpty()) {
			Line line;
			try {
				line = running ? queue.poll(1, TimeUnit.SECONDS) : queue.poll();
			} catch (InterruptedException e) {
				continue; // shutdown requested; loop drains the rest
			}
			if (line == null) {
				flushQuietly();
				continue;
			}
			write(line);
			if (queue.isEmpty()) {
				flushQuietly();
			}
		}
		closeQuietly();
	}

	private void write(Line line) {
		try {
			LocalDate date = line.time().toLocalDate();
			Path dir = directory;
			if (writer == null || !date.equals(openDate) || !dir.equals(openDirectory)) {
				closeQuietly();
				Files.createDirectories(dir);
				Path file = dir.resolve("griefwatch-" + date + ".log");
				writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				openDate = date;
				openDirectory = dir;
			}
			writer.write("[" + TIME.format(line.time()) + "] " + line.text());
			writer.newLine();
		} catch (IOException e) {
			closeQuietly();
			long now = System.currentTimeMillis();
			if (now - lastErrorAt > 60_000) {
				lastErrorAt = now;
				log.warn("[GriefWatch] Could not write to log file in {}: {}", directory, e.toString());
			}
		}
	}

	private void flushQuietly() {
		if (writer != null) {
			try {
				writer.flush();
			} catch (IOException e) {
				closeQuietly();
			}
		}
	}

	private void closeQuietly() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
			}
			writer = null;
			openDate = null;
		}
	}
}

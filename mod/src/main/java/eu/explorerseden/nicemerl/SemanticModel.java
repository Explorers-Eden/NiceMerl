package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Meaning-based search with a tiny static embedding model (Model2Vec potion-base-8M), in plain Java.
 * The model is a table of word-piece vectors: a text's vector is the average of its pieces' vectors,
 * so "unlock the boss room" lands close to "boss key" without any AI runtime. It only compares texts,
 * it never writes any. Mirrors semantic.py in the NiceMerl Discord bot (which uses the model2vec package),
 * so both give the same vectors.
 */
public final class SemanticModel {
	/** The model on Hugging Face, pinned to one revision; about 30 MB, 256 numbers per text. */
	static final String MODEL_NAME = "minishlab/potion-base-8M";
	private static final String REVISION = "bf8b056651a2c21b8d2565580b8569da283cab23";
	/** The files Merl needs and their SHA-256, so a broken or changed download is never used. */
	private static final Map<String, String> FILES = new LinkedHashMap<>(Map.of(
			"tokenizer.json", "e67e803f624fb4d67dea1c730d06e1067e1b14d830e2c2202569e3ef0f70bb50",
			"model.safetensors", "f65d0f325faadc1e121c319e2faa41170d3fa07d8c89abd48ca5358d9a223de2"));
	/** Longest text in word pieces, like model2vec's encode(). */
	private static final int MAX_TOKENS = 512;
	/** Longer words count as unknown, like the tokenizer's max_input_chars_per_word. */
	private static final int MAX_WORD_CHARS = 100;
	private static final String CONTINUING = "##";

	private final Map<String, Integer> vocab;
	private final int unknown;
	private final float[] table;
	private final int dim;

	private SemanticModel(Map<String, Integer> vocab, int unknown, float[] table, int dim) {
		this.vocab = vocab;
		this.unknown = unknown;
		this.table = table;
		this.dim = dim;
	}

	/** The text as a unit-length vector; the dot product of two is their cosine similarity. */
	public float[] embed(String text) {
		float[] out = new float[dim];
		List<Integer> ids = tokenize(text);
		if (ids.isEmpty()) return out;
		for (int id : ids) {
			for (int d = 0, at = id * dim; d < dim; d++) out[d] += table[at + d];
		}
		double norm = 0;
		for (float v : out) norm += (double) v * v;
		norm = Math.sqrt(norm);
		if (norm == 0) return out;
		for (int d = 0; d < dim; d++) out[d] = (float) (out[d] / norm);
		return out;
	}

	public static float dot(float[] a, float[] b) {
		float sum = 0;
		for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
		return sum;
	}

	/** Word-piece ids of the text, without unknown pieces (model2vec drops those too). */
	List<Integer> tokenize(String text) {
		List<Integer> ids = new ArrayList<>();
		for (String word : words(text)) {
			for (int id : pieces(word)) {
				if (id != unknown) ids.add(id);
				if (ids.size() == MAX_TOKENS) return ids;
			}
		}
		return ids;
	}

	/** Greedy longest-match word pieces ("unlocking" → "un", "##lock", "##ing"); a word with no match is unknown. */
	private List<Integer> pieces(String word) {
		int[] cps = word.codePoints().toArray();
		if (cps.length > MAX_WORD_CHARS) return List.of(unknown);
		List<Integer> out = new ArrayList<>();
		int start = 0;
		while (start < cps.length) {
			Integer found = null;
			int end = cps.length;
			for (; end > start; end--) {
				String piece = new String(cps, start, end - start);
				found = vocab.get(start > 0 ? CONTINUING + piece : piece);
				if (found != null) break;
			}
			if (found == null) return List.of(unknown);
			out.add(found);
			start = end;
		}
		return out;
	}

	/**
	 * BERT's normalizing and word splitting: drops control characters, spaces out Chinese characters,
	 * strips accents, lowercases, then splits on spaces and around every punctuation mark.
	 */
	static List<String> words(String text) {
		StringBuilder clean = new StringBuilder();
		text.codePoints().forEach(c -> {
			if (c == 0 || c == 0xFFFD || isControl(c)) return;
			if (isWhitespace(c)) clean.append(' ');
			else if (isChinese(c)) clean.append(' ').appendCodePoint(c).append(' ');
			else clean.appendCodePoint(c);
		});
		StringBuilder plain = new StringBuilder();
		Normalizer.normalize(clean, Normalizer.Form.NFD).codePoints().forEach(c -> {
			if (Character.getType(c) != Character.NON_SPACING_MARK) plain.appendCodePoint(c);
		});
		String lower = plain.toString().toLowerCase(java.util.Locale.ROOT);

		List<String> words = new ArrayList<>();
		StringBuilder word = new StringBuilder();
		lower.codePoints().forEach(c -> {
			if (isWhitespace(c) || isPunctuation(c)) {
				if (!word.isEmpty()) words.add(word.toString());
				word.setLength(0);
				if (isPunctuation(c)) words.add(Character.toString(c));
			} else {
				word.appendCodePoint(c);
			}
		});
		if (!word.isEmpty()) words.add(word.toString());
		return words;
	}

	private static boolean isWhitespace(int c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r' || Character.isWhitespace(c) || Character.isSpaceChar(c);
	}

	private static boolean isControl(int c) {
		if (c == '\t' || c == '\n' || c == '\r') return false;
		int type = Character.getType(c);
		return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
				|| type == Character.PRIVATE_USE || type == Character.UNASSIGNED;
	}

	private static boolean isPunctuation(int c) {
		if (c >= 33 && c <= 47 || c >= 58 && c <= 64 || c >= 91 && c <= 96 || c >= 123 && c <= 126) return true;
		return switch (Character.getType(c)) {
			case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
					Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
					Character.OTHER_PUNCTUATION -> true;
			default -> false;
		};
	}

	private static boolean isChinese(int c) {
		return c >= 0x4E00 && c <= 0x9FFF || c >= 0x3400 && c <= 0x4DBF || c >= 0x20000 && c <= 0x2A6DF
				|| c >= 0x2A700 && c <= 0x2B73F || c >= 0x2B740 && c <= 0x2B81F || c >= 0x2B820 && c <= 0x2CEAF
				|| c >= 0xF900 && c <= 0xFAFF || c >= 0x2F800 && c <= 0x2FA1F;
	}

	/** The model from a folder holding tokenizer.json and model.safetensors. */
	public static SemanticModel load(Path dir) throws IOException {
		JsonObject tokenizer;
		try (Reader reader = Files.newBufferedReader(dir.resolve("tokenizer.json"))) {
			tokenizer = JsonParser.parseReader(reader).getAsJsonObject();
		}
		JsonObject model = tokenizer.getAsJsonObject("model");
		if (!"WordPiece".equals(model.get("type").getAsString())) throw new IOException("not a WordPiece tokenizer");
		Map<String, Integer> vocab = new HashMap<>();
		model.getAsJsonObject("vocab").entrySet().forEach(e -> vocab.put(e.getKey(), e.getValue().getAsInt()));
		Integer unknown = vocab.get(model.get("unk_token").getAsString());
		if (unknown == null) throw new IOException("no unknown token");

		try (FileChannel file = FileChannel.open(dir.resolve("model.safetensors"), StandardOpenOption.READ)) {
			ByteBuffer size = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			readFully(file, size);
			long headerSize = size.flip().getLong();
			if (headerSize <= 0 || headerSize > 1_000_000) throw new IOException("bad safetensors header");
			ByteBuffer headerBytes = ByteBuffer.allocate((int) headerSize);
			readFully(file, headerBytes);
			JsonObject header = JsonParser.parseString(new String(headerBytes.array(), StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject embeddings = header.getAsJsonObject("embeddings");
			if (embeddings == null || !"F32".equals(embeddings.get("dtype").getAsString())) throw new IOException("no F32 embeddings");
			JsonArray shape = embeddings.getAsJsonArray("shape");
			int rows = shape.get(0).getAsInt(), dim = shape.get(1).getAsInt();
			JsonArray offsets = embeddings.getAsJsonArray("data_offsets");
			long begin = offsets.get(0).getAsLong(), end = offsets.get(1).getAsLong();
			if (end - begin != 4L * rows * dim || rows < vocab.size()) throw new IOException("embeddings don't fit the tokenizer");
			ByteBuffer data = ByteBuffer.allocate((int) (end - begin)).order(ByteOrder.LITTLE_ENDIAN);
			file.position(8 + headerSize + begin);
			readFully(file, data);
			float[] table = new float[rows * dim];
			data.flip().asFloatBuffer().get(table);
			return new SemanticModel(vocab, unknown, table, dim);
		}
	}

	private static void readFully(FileChannel file, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			if (file.read(buffer) < 0) throw new IOException("file ends early");
		}
	}

	/**
	 * The model from the folder, downloading the missing files from Hugging Face first (once, about 31 MB).
	 * Every file is checked against its known SHA-256 before it's used.
	 */
	public static SemanticModel loadOrDownload(Path dir) throws IOException, InterruptedException {
		Files.createDirectories(dir);
		HttpClient http = null;
		for (Map.Entry<String, String> file : FILES.entrySet()) {
			Path target = dir.resolve(file.getKey());
			if (Files.exists(target) && file.getValue().equals(sha256(target))) continue;
			if (http == null) {
				http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
						.followRedirects(HttpClient.Redirect.NORMAL).build();
			}
			Path part = dir.resolve(file.getKey() + ".part");
			HttpRequest request = HttpRequest.newBuilder(URI.create(
					"https://huggingface.co/" + MODEL_NAME + "/resolve/" + REVISION + "/" + file.getKey()))
					.timeout(Duration.ofMinutes(5)).header("User-Agent", "NiceMerl").GET().build();
			HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(part));
			if (response.statusCode() != 200) {
				Files.deleteIfExists(part);
				throw new IOException("download of " + file.getKey() + " failed: HTTP " + response.statusCode());
			}
			if (!file.getValue().equals(sha256(part))) {
				Files.deleteIfExists(part);
				throw new IOException("download of " + file.getKey() + " doesn't match its checksum");
			}
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return load(dir);
	}

	private static String sha256(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
				in.transferTo(java.io.OutputStream.nullOutputStream());
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}
}

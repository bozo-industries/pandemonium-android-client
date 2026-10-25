package page.claras.pandemonium;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Immutable encrypted pages become visible only after the complete cursor traversal commits. */
final class MailWidgetStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "pandemonium.mail-widget.v1";
    private static final int MAX_BYTES = 1_048_576;
    private static final Map<String, Integer> READERS = new HashMap<>();
    private static final Set<String> WRITERS = new HashSet<>();
    private static long epoch;
    private final File folder;
    private final AtomicFile manifest;

    MailWidgetStore(Context context) {
        folder = new File(context.getApplicationContext().getFilesDir(), "mail-widget");
        manifest = new AtomicFile(new File(folder, "snapshot"));
    }

    JSONObject metadata() {
        synchronized (LOCK) {
            try { return readMetadata(); }
            catch (Exception ignored) { return errorMetadata("cache_error"); }
        }
    }

    Writer writer() throws Exception {
        synchronized (LOCK) { return new Writer(); }
    }

    Reader reader(Instant now) throws Exception {
        synchronized (LOCK) {
            JSONObject metadata = readMetadata();
            return new Reader(metadata, now);
        }
    }

    void markFailure(String code) throws Exception {
        synchronized (LOCK) {
            JSONObject metadata = metadata();
            metadata.put("refreshError", code).put("lastAttemptAt", System.currentTimeMillis());
            writeMetadata(metadata);
        }
    }

    void clear() throws IOException {
        synchronized (LOCK) {
            epoch++;
            manifest.delete();
            READERS.clear();
            try {
                KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
                if (keys.containsAlias(ALIAS)) keys.deleteEntry(ALIAS);
            } catch (Exception error) { throw new IOException("Mail cache key removal failed"); }
            File[] files = folder.listFiles();
            if (files != null) for (File file : files) if (file.getName().matches("[a-f0-9]{32}")) deleteSnapshot(file.getName());
        }
    }

    final class Writer implements MailWindowSource.Sink, AutoCloseable {
        private final String token = UUID.randomUUID().toString().replace("-", "");
        private final long generation = epoch;
        private final File directory = new File(folder, token);
        private final List<Integer> starts = new ArrayList<>();
        private final Map<String, List<Location>> identities = new HashMap<>();
        private JSONArray pending = new JSONArray();
        private int count, pendingBytes = 2;
        private boolean committed, closed;

        private Writer() throws Exception {
            ensureFolder();
            if (!directory.mkdir()) throw new IOException("Mail cache staging unavailable");
            WRITERS.add(token);
        }

        @Override public int count() { return count; }

        @Override public void append(JSONObject message) throws Exception {
            synchronized (LOCK) {
                ensureActive();
                String ref = message.getString("messageRef");
                String digest = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(ref.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
                List<Location> bucket = identities.get(digest);
                if (bucket != null) for (Location location : bucket) {
                    JSONObject existing = location.page == starts.size() ? pending.getJSONObject(location.row)
                        : readPage(token, location.page).getJSONObject(location.row);
                    if (ref.equals(existing.getString("messageRef"))) {
                        if (!MailWindowSource.canonical(existing).equals(MailWindowSource.canonical(message))) throw new MailWindowSource.InvalidSnapshot();
                        return;
                    }
                }
                if (count >= MailWindowSource.MAX_MESSAGES) throw new MailWindowSource.LimitExceeded();
                byte[] encoded = message.toString().getBytes(StandardCharsets.UTF_8);
                if (encoded.length > MAX_BYTES - 256) throw new IOException("Mail summary exceeds cache page bound");
                if (pending.length() >= MailWindowSource.PAGE_SIZE || pendingBytes + encoded.length + 1 > MAX_BYTES - 128) flush();
                if (bucket == null) { bucket = new ArrayList<>(); identities.put(digest, bucket); }
                bucket.add(new Location(starts.size(), pending.length()));
                pending.put(message);
                pendingBytes += encoded.length + 1;
                count++;
            }
        }

        void commit(JSONObject metadata) throws Exception {
            synchronized (LOCK) {
                ensureActive();
                if (metadata.getInt("schema") != 1 || metadata.getInt("count") != count || !metadata.getBoolean("paginationComplete")
                        || metadata.optJSONObject("coverage") == null) throw new MailWindowSource.InvalidSnapshot();
                flush();
                JSONObject selected = new JSONObject(metadata.toString());
                JSONArray pages = new JSONArray();
                for (int start : starts) pages.put(start);
                selected.put("snapshot", token).put("pageStarts", pages);
                selected.remove("refreshError");
                writeMetadata(selected);
                committed = true;
                WRITERS.remove(token);
                cleanup(token);
            }
        }

        private void flush() throws Exception {
            if (pending.length() == 0) return;
            ensureActive();
            int page = starts.size();
            File file = pageFile(token, page);
            if (file.exists()) throw new IOException("Mail cache page already exists");
            byte[] plaintext = pending.toString().getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > MAX_BYTES) throw new IOException("Mail cache page exceeds its bound");
            byte[] encrypted = encrypt(plaintext, token + ":" + page);
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(encrypted); output.getFD().sync();
            }
            starts.add(count - pending.length());
            pending = new JSONArray(); pendingBytes = 2;
        }

        private void ensureActive() throws IOException {
            if (closed || committed || generation != epoch) throw new IOException("Mail cache staging was invalidated");
        }

        @Override public void close() {
            synchronized (LOCK) {
                if (closed) return;
                closed = true; WRITERS.remove(token);
                if (!committed) try { deleteSnapshot(token); } catch (IOException ignored) { }
                identities.clear(); pending = new JSONArray();
            }
        }
    }

    final class Reader implements AutoCloseable {
        private final JSONObject metadata;
        private final String token;
        private final int total;
        private final int[] starts;
        private final long generation = epoch;
        private int first, end, cachedPage = -1;
        private JSONArray cached = new JSONArray();
        private boolean closed;

        private Reader(JSONObject metadata, Instant now) throws Exception {
            this.metadata = metadata;
            token = metadata.optString("snapshot");
            total = metadata.optInt("count");
            JSONArray pages = metadata.optJSONArray("pageStarts");
            starts = new int[pages == null ? 0 : pages.length()];
            for (int i = 0; i < starts.length; i++) starts[i] = pages.getInt(i);
            if (!token.isEmpty()) READERS.put(token, READERS.getOrDefault(token, 0) + 1);
            try {
                first = boundary(now);
                end = boundary(now.minusMillis(MailWindowSource.WINDOW_MS));
            } catch (Exception error) { close(); throw error; }
        }

        int count() { synchronized (LOCK) { return closed || generation != epoch ? 0 : Math.max(0, end - first); } }
        JSONObject metadata() { return metadata; }

        JSONObject row(int position) throws Exception {
            synchronized (LOCK) {
                if (position < 0 || position >= count()) return null;
                return raw(first + position);
            }
        }

        private int boundary(Instant timestamp) throws Exception {
            int low = 0, high = total;
            while (low < high) {
                int middle = (low + high) >>> 1;
                Instant date = Instant.parse(raw(middle).getString("date"));
                if (!date.isBefore(timestamp)) low = middle + 1; else high = middle;
            }
            return low;
        }

        private JSONObject raw(int position) throws Exception {
            if (generation != epoch || closed || position < 0 || position >= total) throw new IOException("Mail cache reader is unavailable");
            int found = Arrays.binarySearch(starts, position);
            int page = found >= 0 ? found : -found - 2;
            if (page < 0) throw new IOException("Mail cache index is invalid");
            if (cachedPage != page) { cached = readPage(token, page); cachedPage = page; }
            int expected = (page + 1 == starts.length ? total : starts[page + 1]) - starts[page];
            if (cached.length() != expected) throw new IOException("Mail cache page is incomplete");
            return cached.getJSONObject(position - starts[page]);
        }

        @Override public void close() {
            synchronized (LOCK) {
                if (closed) return;
                closed = true; cached = new JSONArray();
                if (!token.isEmpty()) {
                    int remaining = READERS.getOrDefault(token, 0) - 1;
                    if (remaining > 0) READERS.put(token, remaining); else READERS.remove(token);
                }
            }
        }
    }

    private static final class Location {
        final int page, row;
        Location(int page, int row) { this.page = page; this.row = row; }
    }

    private JSONObject readMetadata() throws Exception {
        if (!manifest.getBaseFile().exists() && !new File(folder, "snapshot.bak").exists()) return emptyMetadata();
        byte[] encrypted;
        try (FileInputStream input = manifest.openRead()) { encrypted = readBytes(input, MAX_BYTES + 64); }
        JSONObject value = new JSONObject(new String(decrypt(encrypted, "index"), StandardCharsets.UTF_8));
        if (value.optInt("schema") != 1) throw new IOException("Mail cache index is invalid");
        Object rawCount = value.opt("count");
        if (!(rawCount instanceof Number) || ((Number) rawCount).doubleValue() != ((Number) rawCount).longValue()
                || ((Number) rawCount).longValue() < 0 || ((Number) rawCount).longValue() > MailWindowSource.MAX_MESSAGES)
            throw new IOException("Mail cache count is invalid");
        int count = ((Number) rawCount).intValue();
        String token = value.optString("snapshot");
        if (token.isEmpty()) {
            if (count != 0) throw new IOException("Mail cache index is missing");
            return value;
        }
        if (!token.matches("[a-f0-9]{32}") || !value.optBoolean("paginationComplete") || value.optJSONObject("coverage") == null)
            throw new IOException("Mail cache snapshot is invalid");
        JSONArray pages = value.optJSONArray("pageStarts");
        if (pages == null || pages.length() > count || (count > 0 && (pages.length() == 0 || pages.getInt(0) != 0)))
            throw new IOException("Mail cache pages are invalid");
        int previous = -1;
        for (int i = 0; i < pages.length(); i++) {
            Object item = pages.get(i);
            if (!(item instanceof Number) || ((Number) item).doubleValue() != ((Number) item).intValue()) throw new IOException("Mail cache page offset is invalid");
            int start = ((Number) item).intValue();
            if (start <= previous || start >= count) throw new IOException("Mail cache page offsets are invalid");
            previous = start;
        }
        return value;
    }

    private void writeMetadata(JSONObject value) throws Exception {
        ensureFolder();
        byte[] plaintext = value.toString().getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_BYTES) throw new IOException("Mail cache metadata exceeds its bound");
        byte[] encrypted = encrypt(plaintext, "index");
        FileOutputStream output = manifest.startWrite();
        try { output.write(encrypted); manifest.finishWrite(output); }
        catch (Exception error) { manifest.failWrite(output); throw error; }
        try (FileInputStream input = manifest.openRead()) {
            if (!Arrays.equals(encrypted, readBytes(input, MAX_BYTES + 64))) throw new IOException("Mail cache commit was not persisted");
        }
    }

    private JSONArray readPage(String token, int page) throws Exception {
        try (FileInputStream input = new FileInputStream(pageFile(token, page))) {
            JSONArray value = new JSONArray(new String(decrypt(readBytes(input, MAX_BYTES + 64), token + ":" + page), StandardCharsets.UTF_8));
            if (value.length() == 0 || value.length() > MailWindowSource.PAGE_SIZE) throw new IOException("Mail cache page is invalid");
            return value;
        }
    }

    private File pageFile(String token, int page) throws IOException {
        if (!token.matches("[a-f0-9]{32}") || page < 0 || page >= MailWindowSource.MAX_MESSAGES) throw new IOException("Mail cache page path is invalid");
        return new File(new File(folder, token), String.format(java.util.Locale.ROOT, "%05d.page", page));
    }

    private void ensureFolder() throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Mail cache directory unavailable");
    }

    private void cleanup(String current) throws IOException {
        File[] files = folder.listFiles();
        if (files != null) for (File file : files) {
            String token = file.getName();
            if (token.matches("[a-f0-9]{32}") && !token.equals(current) && !READERS.containsKey(token) && !WRITERS.contains(token)) deleteSnapshot(token);
        }
    }

    private void deleteSnapshot(String token) throws IOException {
        File directory = new File(folder, token);
        if (!token.matches("[a-f0-9]{32}") || !directory.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())) throw new IOException("Mail cache cleanup path is invalid");
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.getName().matches("[0-9]{5}\\.page")) file.delete();
        directory.delete();
    }

    private static byte[] readBytes(FileInputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("Mail cache record exceeds its bound");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private byte[] encrypt(byte[] plaintext, String identity) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        byte[] data = cipher.doFinal(plaintext), iv = cipher.getIV();
        if (iv.length != 12) throw new IOException("Mail cache encryption unavailable");
        byte[] encrypted = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, encrypted, 0, iv.length); System.arraycopy(data, 0, encrypted, iv.length, data.length);
        return encrypted;
    }

    private byte[] decrypt(byte[] encrypted, String identity) throws Exception {
        if (encrypted.length < 28) throw new IOException("Mail cache record is incomplete");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
        cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static JSONObject emptyMetadata() {
        try { return new JSONObject().put("schema", 1).put("count", 0).put("paginationComplete", false); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONObject errorMetadata(String code) {
        JSONObject value = emptyMetadata();
        try { value.put("refreshError", code); return value; }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}

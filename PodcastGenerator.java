import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PodcastGenerator {
    private static final AudioFormat TARGET_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            16000.0f,
            16,
            1,
            2,
            16000.0f,
            false
    );

    private static final double DEFAULT_PEAK = 0.95;

    public static void main(String[] args) {
        try {
            CliOptions options = CliOptions.parse(args);
            Config config = ConfigLoader.load(options.configPath);
            run(config, options.outputPath, options.playAfterGenerate);
            System.out.println("Podcast generated: " + options.outputPath);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
            printUsage();
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Failed to generate podcast: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(Config config, String outputPath, boolean playAfterGenerate) throws Exception {
        if (config.segments.isEmpty()) {
            throw new IllegalArgumentException("segments cannot be empty");
        }

        TtsClient ttsClient = new TtsClient(config.ttsSettings);
        List<double[]> renderedSegments = new ArrayList<>();

        for (Segment segment : config.segments) {
            double[] samples;
            if ("text".equals(segment.type)) {
                if (config.ttsSettings == null || !config.ttsSettings.isComplete()) {
                    throw new IllegalArgumentException("Text segment exists but xfyun credentials are missing");
                }
                byte[] pcm = ttsClient.synthesize(segment.text, segment.voice);
                samples = AudioOps.pcm16ToDouble(pcm);
            } else if ("audio".equals(segment.type)) {
                samples = AudioOps.readWavAsTarget(segment.path);
            } else {
                throw new IllegalArgumentException("Unsupported segment type: " + segment.type);
            }

            samples = AudioOps.trim(samples, segment.startSec, segment.endSec);
            if (segment.gain != null) {
                samples = AudioOps.gain(samples, segment.gain);
            }
            if (segment.fadeInSec != null || segment.fadeOutSec != null) {
                samples = AudioOps.applyFade(samples,
                        segment.fadeInSec == null ? 0.0 : segment.fadeInSec,
                        segment.fadeOutSec == null ? 0.0 : segment.fadeOutSec);
            }
            renderedSegments.add(samples);
        }

        double[] speech = AudioOps.concat(renderedSegments);

        if (config.echoEnabled) {
            speech = AudioOps.applyEcho(speech, config.echoDelaySec, config.echoDecay);
        }
        if (config.reverbEnabled) {
            speech = AudioOps.applyReverb(speech, config.reverbDelaySec, config.reverbDecay, config.reverbRepeats);
        }

        speech = AudioOps.normalizePeak(speech, config.normalizePeak);
        speech = AudioOps.applyFade(speech, config.fadeInSec, config.fadeOutSec);

        double[] finalMix = speech;
        if (config.bgmPath != null && !config.bgmPath.isBlank()) {
            double[] bgm = AudioOps.readWavAsTarget(config.bgmPath);
            bgm = AudioOps.trim(bgm, config.bgmStartSec, config.bgmEndSec);
            finalMix = AudioOps.mixWithLoopingBgm(speech, bgm, config.bgmVolume);
            finalMix = AudioOps.normalizePeak(finalMix, config.normalizePeak);
        }

        AudioOps.writeDoubleAsWav(finalMix, outputPath);

        if (playAfterGenerate) {
            AudioOps.playWavBlocking(outputPath);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java PodcastGenerator -c <config.json|yaml> [-o output.wav] [--play]");
        System.err.println("Only -c, -o and --play are supported.");
    }

    private static final class CliOptions {
        final String configPath;
        final String outputPath;
        final boolean playAfterGenerate;

        private CliOptions(String configPath, String outputPath, boolean playAfterGenerate) {
            this.configPath = configPath;
            this.outputPath = outputPath;
            this.playAfterGenerate = playAfterGenerate;
        }

        static CliOptions parse(String[] args) {
            String config = null;
            String output = "episode.wav";
            boolean play = false;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-c":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("-c requires a value");
                        }
                        config = args[++i];
                        break;
                    case "-o":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException("-o requires a value");
                        }
                        output = args[++i];
                        break;
                    case "--play":
                        play = true;
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported argument: " + arg);
                }
            }

            if (config == null || config.isBlank()) {
                throw new IllegalArgumentException("-c <config> is required");
            }
            return new CliOptions(config, output, play);
        }
    }

    private static final class Segment {
        String type;
        String text;
        String path;
        String voice;
        Double startSec;
        Double endSec;
        Double fadeInSec;
        Double fadeOutSec;
        Double gain;
    }

    private static final class TtsSettings {
        String appId;
        String apiKey;
        String apiSecret;
        String host = "tts-api.xfyun.cn";
        String path = "/v2/tts";
        String defaultVoice = "xiaoyan";

        boolean isComplete() {
            return notBlank(appId) && notBlank(apiKey) && notBlank(apiSecret);
        }
    }

    private static final class Config {
        List<Segment> segments = new ArrayList<>();
        TtsSettings ttsSettings;

        String bgmPath;
        double bgmVolume = 0.3;
        Double bgmStartSec;
        Double bgmEndSec;

        double normalizePeak = DEFAULT_PEAK;
        double fadeInSec = 0.0;
        double fadeOutSec = 0.0;

        boolean echoEnabled = false;
        double echoDelaySec = 0.2;
        double echoDecay = 0.25;

        boolean reverbEnabled = false;
        double reverbDelaySec = 0.04;
        double reverbDecay = 0.30;
        int reverbRepeats = 5;
    }

    private static final class ConfigLoader {
        static Config load(String configPath) throws IOException {
            String text = Files.readString(Path.of(configPath), StandardCharsets.UTF_8);
            Object rootObj = parseConfigText(text, configPath);
            if (!(rootObj instanceof Map)) {
                throw new IllegalArgumentException("Config root must be an object");
            }

            Map<String, Object> root = castMap(rootObj);
            Config cfg = new Config();

            cfg.ttsSettings = parseTtsSettings(root);

            Object segmentsObj = firstPresent(root, "segments", "paragraphs");
            if (!(segmentsObj instanceof List)) {
                throw new IllegalArgumentException("segments must be an array");
            }
            for (Object obj : castList(segmentsObj)) {
                Map<String, Object> item = castMap(obj);
                Segment s = new Segment();
                s.type = requiredString(item, "type").toLowerCase(Locale.ROOT);
                s.voice = stringOrNull(item.get("voice"));
                s.startSec = doubleOrNull(item.get("start"));
                s.endSec = doubleOrNull(item.get("end"));
                s.fadeInSec = doubleOrNull(firstPresent(item, "fade_in", "fadeIn"));
                s.fadeOutSec = doubleOrNull(firstPresent(item, "fade_out", "fadeOut"));
                s.gain = doubleOrNull(firstPresent(item, "gain", "volume", "volume_factor"));

                if ("text".equals(s.type)) {
                    s.text = requiredString(item, "text");
                } else if ("audio".equals(s.type)) {
                    s.path = requiredString(item, "path");
                } else {
                    throw new IllegalArgumentException("segment type must be text or audio");
                }
                cfg.segments.add(s);
            }

            cfg.bgmPath = stringOrNull(firstPresent(root, "bgm", "bgm_path"));
            Object bgmObj = root.get("bgm");
            if (bgmObj instanceof Map) {
                Map<String, Object> bgmMap = castMap(bgmObj);
                cfg.bgmPath = stringOrNull(firstPresent(bgmMap, "path", "file"));
                cfg.bgmVolume = doubleOrDefault(firstPresent(bgmMap, "volume", "bgm_volume"), cfg.bgmVolume);
                cfg.bgmStartSec = doubleOrNull(bgmMap.get("start"));
                cfg.bgmEndSec = doubleOrNull(bgmMap.get("end"));
            }
            cfg.bgmVolume = doubleOrDefault(root.get("bgm_volume"), cfg.bgmVolume);

            cfg.normalizePeak = clamp(doubleOrDefault(firstPresent(root, "normalize_peak", "peak", "target_peak"), DEFAULT_PEAK), 0.01, 1.0);
            cfg.fadeInSec = Math.max(0.0, doubleOrDefault(firstPresent(root, "fade_in", "fadeIn"), 0.0));
            cfg.fadeOutSec = Math.max(0.0, doubleOrDefault(firstPresent(root, "fade_out", "fadeOut"), 0.0));

            Object echoObj = root.get("echo");
            if (echoObj instanceof Map) {
                Map<String, Object> echoMap = castMap(echoObj);
                cfg.echoEnabled = boolOrDefault(echoMap.get("enabled"), true);
                cfg.echoDelaySec = Math.max(0.0, doubleOrDefault(firstPresent(echoMap, "delay", "delay_sec"), cfg.echoDelaySec));
                cfg.echoDecay = clamp(doubleOrDefault(echoMap.get("decay"), cfg.echoDecay), 0.0, 1.0);
            } else if (echoObj instanceof Boolean) {
                cfg.echoEnabled = (Boolean) echoObj;
            }

            Object reverbObj = root.get("reverb");
            if (reverbObj instanceof Map) {
                Map<String, Object> reverbMap = castMap(reverbObj);
                cfg.reverbEnabled = boolOrDefault(reverbMap.get("enabled"), true);
                cfg.reverbDelaySec = Math.max(0.0, doubleOrDefault(firstPresent(reverbMap, "delay", "delay_sec"), cfg.reverbDelaySec));
                cfg.reverbDecay = clamp(doubleOrDefault(reverbMap.get("decay"), cfg.reverbDecay), 0.0, 1.0);
                cfg.reverbRepeats = (int) Math.max(1, doubleOrDefault(firstPresent(reverbMap, "repeats", "k"), cfg.reverbRepeats));
            } else if (reverbObj instanceof Boolean) {
                cfg.reverbEnabled = (Boolean) reverbObj;
            }

            return cfg;
        }

        private static Object parseConfigText(String text, String configPath) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Config file is empty: " + configPath);
            }

            String lower = configPath.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".json") || trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return new MiniJsonParser(trimmed).parse();
            }
            if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                return new MiniYamlParser(trimmed).parse();
            }

            try {
                return new MiniJsonParser(trimmed).parse();
            } catch (RuntimeException e) {
                return new MiniYamlParser(trimmed).parse();
            }
        }

        private static TtsSettings parseTtsSettings(Map<String, Object> root) {
            TtsSettings settings = new TtsSettings();
            Object xfyunObj = firstPresent(root, "xfyun", "iflytek", "tts");
            Map<String, Object> ttsMap = xfyunObj instanceof Map ? castMap(xfyunObj) : null;

            settings.appId = stringOrNull(firstPresent(root, "app_id", "appid"));
            settings.apiKey = stringOrNull(firstPresent(root, "api_key", "apikey"));
            settings.apiSecret = stringOrNull(firstPresent(root, "api_secret", "apisecret"));

            if (ttsMap != null) {
                if (settings.appId == null) settings.appId = stringOrNull(firstPresent(ttsMap, "app_id", "appid"));
                if (settings.apiKey == null) settings.apiKey = stringOrNull(firstPresent(ttsMap, "api_key", "apikey"));
                if (settings.apiSecret == null) settings.apiSecret = stringOrNull(firstPresent(ttsMap, "api_secret", "apisecret"));
                String host = stringOrNull(ttsMap.get("host"));
                String path = stringOrNull(ttsMap.get("path"));
                String voice = stringOrNull(firstPresent(ttsMap, "voice", "vcn", "default_voice"));
                if (notBlank(host)) settings.host = host;
                if (notBlank(path)) settings.path = path;
                if (notBlank(voice)) settings.defaultVoice = voice;
            }

            if (!settings.isComplete()) {
                return settings;
            }
            return settings;
        }
    }

    private static final class TtsClient {
        private final TtsSettings settings;
        private final HttpClient client = HttpClient.newHttpClient();

        TtsClient(TtsSettings settings) {
            this.settings = settings;
        }

        byte[] synthesize(String text, String voice) throws Exception {
            if (settings == null || !settings.isComplete()) {
                throw new IllegalArgumentException("Missing xfyun TTS credentials");
            }
            if (!notBlank(text)) {
                return new byte[0];
            }

            String wsUrl = buildAuthorizedWebSocketUrl(settings.host, settings.path, settings.apiKey, settings.apiSecret);
            CompletableFuture<byte[]> audioFuture = new CompletableFuture<>();
            String payload = buildPayload(settings.appId, notBlank(voice) ? voice : settings.defaultVoice, text);

            TtsListener listener = new TtsListener(audioFuture, payload);
            WebSocket ws = client.newWebSocketBuilder().buildAsync(URI.create(wsUrl), listener).join();

            byte[] data = audioFuture.get(90, TimeUnit.SECONDS);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            return data;
        }

        private static String buildPayload(String appId, String voice, String text) {
            String textBase64 = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
            return "{" +
                    "\"common\":{\"app_id\":\"" + jsonEscape(appId) + "\"}," +
                    "\"business\":{" +
                    "\"aue\":\"raw\"," +
                    "\"auf\":\"audio/L16;rate=16000\"," +
                    "\"vcn\":\"" + jsonEscape(voice) + "\"," +
                    "\"tte\":\"UTF8\"}," +
                    "\"data\":{" +
                    "\"status\":2," +
                    "\"text\":\"" + textBase64 + "\"}" +
                    "}";
        }

        private static String buildAuthorizedWebSocketUrl(String host, String path, String apiKey, String apiSecret)
                throws GeneralSecurityException {
            String date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC));
            String signatureOrigin = "host: " + host + "\n" +
                    "date: " + date + "\n" +
                    "GET " + path + " HTTP/1.1";

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = Base64.getEncoder().encodeToString(mac.doFinal(signatureOrigin.getBytes(StandardCharsets.UTF_8)));

            String authorizationOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"" + signature + "\"";
            String authorization = Base64.getEncoder().encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

            return "wss://" + host + path +
                    "?authorization=" + urlEncode(authorization) +
                    "&date=" + urlEncode(date) +
                    "&host=" + urlEncode(host);
        }

        private static String urlEncode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        }

        private static final class TtsListener implements WebSocket.Listener {
            private final CompletableFuture<byte[]> future;
            private final String payload;
            private final StringBuilder buffer = new StringBuilder();
            private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

            private static final Pattern CODE_PATTERN = Pattern.compile("\\\"code\\\"\\s*:\\s*(\\d+)");
            private static final Pattern MESSAGE_PATTERN = Pattern.compile("\\\"message\\\"\\s*:\\s*\\\"(.*?)\\\"");
            private static final Pattern STATUS_PATTERN = Pattern.compile("\\\"status\\\"\\s*:\\s*(\\d+)");
            private static final Pattern AUDIO_PATTERN = Pattern.compile("\\\"audio\\\"\\s*:\\s*\\\"(.*?)\\\"");

            TtsListener(CompletableFuture<byte[]> future, String payload) {
                this.future = future;
                this.payload = payload;
            }

            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
                webSocket.sendText(payload, true);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String msg = buffer.toString();
                    buffer.setLength(0);
                    try {
                        int code = extractInt(CODE_PATTERN, msg, 0);
                        if (code != 0) {
                            String m = extractString(MESSAGE_PATTERN, msg, "unknown error");
                            future.completeExceptionally(new RuntimeException("iFlytek TTS error code=" + code + " message=" + m));
                            webSocket.abort();
                            return CompletableFuture.completedFuture(null);
                        }

                        String audioB64 = extractString(AUDIO_PATTERN, msg, null);
                        if (audioB64 != null && !audioB64.isEmpty()) {
                            byte[] chunk = Base64.getDecoder().decode(audioB64);
                            pcm.write(chunk);
                        }

                        int status = extractInt(STATUS_PATTERN, msg, 1);
                        if (status == 2) {
                            future.complete(pcm.toByteArray());
                        }
                    } catch (Exception ex) {
                        future.completeExceptionally(ex);
                    }
                }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                future.completeExceptionally(error);
            }

            private static int extractInt(Pattern pattern, String text, int defaultValue) {
                Matcher m = pattern.matcher(text);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
                return defaultValue;
            }

            private static String extractString(Pattern pattern, String text, String defaultValue) {
                Matcher m = pattern.matcher(text);
                if (m.find()) {
                    return jsonUnescape(m.group(1));
                }
                return defaultValue;
            }
        }
    }

    private static final class AudioOps {
        static double[] readWavAsTarget(String path) throws IOException, UnsupportedAudioFileException {
            File file = new File(path);
            try (AudioInputStream in = AudioSystem.getAudioInputStream(file);
                 AudioInputStream pcm = AudioSystem.getAudioInputStream(TARGET_FORMAT, in)) {
                byte[] bytes = pcm.readAllBytes();
                return pcm16ToDouble(bytes);
            }
        }

        static double[] pcm16ToDouble(byte[] pcm) {
            int n = pcm.length / 2;
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                int lo = pcm[i * 2] & 0xFF;
                int hi = pcm[i * 2 + 1];
                short sample = (short) ((hi << 8) | lo);
                out[i] = sample / 32768.0;
            }
            return out;
        }

        static void writeDoubleAsWav(double[] samples, String outputPath) throws IOException {
            byte[] bytes = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                double clamped = clip(samples[i]);
                int v = (int) Math.round(clamped * 32767.0);
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                bytes[i * 2] = (byte) (v & 0xFF);
                bytes[i * 2 + 1] = (byte) ((v >>> 8) & 0xFF);
            }

            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 AudioInputStream ais = new AudioInputStream(bais, TARGET_FORMAT, samples.length)) {
                AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(outputPath));
            }
        }

        static double[] trim(double[] samples, Double startSec, Double endSec) {
            int start = 0;
            int end = samples.length;
            if (startSec != null) {
                start = (int) Math.max(0, Math.round(startSec * TARGET_FORMAT.getSampleRate()));
            }
            if (endSec != null) {
                end = (int) Math.max(0, Math.round(endSec * TARGET_FORMAT.getSampleRate()));
            }
            start = Math.min(start, samples.length);
            end = Math.min(end, samples.length);
            if (end < start) {
                return new double[0];
            }
            double[] out = new double[end - start];
            System.arraycopy(samples, start, out, 0, out.length);
            return out;
        }

        static double[] concat(List<double[]> list) {
            int len = 0;
            for (double[] arr : list) {
                len += arr.length;
            }
            double[] out = new double[len];
            int pos = 0;
            for (double[] arr : list) {
                System.arraycopy(arr, 0, out, pos, arr.length);
                pos += arr.length;
            }
            return out;
        }

        static double[] gain(double[] samples, double k) {
            double[] out = new double[samples.length];
            for (int i = 0; i < samples.length; i++) {
                out[i] = clip(samples[i] * k);
            }
            return out;
        }

        static double[] normalizePeak(double[] samples, double targetPeak) {
            double peak = 0.0;
            for (double s : samples) {
                double a = Math.abs(s);
                if (a > peak) peak = a;
            }
            if (peak < 1e-12) {
                return samples.clone();
            }
            double scale = targetPeak / peak;
            double[] out = new double[samples.length];
            for (int i = 0; i < samples.length; i++) {
                out[i] = clip(samples[i] * scale);
            }
            return out;
        }

        static double[] applyFade(double[] samples, double fadeInSec, double fadeOutSec) {
            double[] out = samples.clone();
            int n = out.length;
            int fadeInSamples = (int) Math.max(0, Math.round(fadeInSec * TARGET_FORMAT.getSampleRate()));
            int fadeOutSamples = (int) Math.max(0, Math.round(fadeOutSec * TARGET_FORMAT.getSampleRate()));

            fadeInSamples = Math.min(fadeInSamples, n);
            fadeOutSamples = Math.min(fadeOutSamples, n);

            for (int i = 0; i < fadeInSamples; i++) {
                double g = fadeInSamples <= 1 ? 1.0 : (double) i / (fadeInSamples - 1);
                out[i] = clip(out[i] * g);
            }
            for (int i = 0; i < fadeOutSamples; i++) {
                int idx = n - 1 - i;
                if (idx < 0) break;
                double g = fadeOutSamples <= 1 ? 1.0 : (double) i / (fadeOutSamples - 1);
                out[idx] = clip(out[idx] * (1.0 - g));
            }
            return out;
        }

        static double[] mixWithLoopingBgm(double[] speech, double[] bgm, double bgmVolume) {
            if (bgm.length == 0) {
                return speech.clone();
            }
            double[] out = new double[speech.length];
            for (int i = 0; i < speech.length; i++) {
                double m = speech[i] + bgmVolume * bgm[i % bgm.length];
                out[i] = clip(m);
            }
            return out;
        }

        static double[] applyEcho(double[] samples, double delaySec, double decay) {
            int delay = (int) Math.max(1, Math.round(delaySec * TARGET_FORMAT.getSampleRate()));
            double[] out = samples.clone();
            for (int i = delay; i < out.length; i++) {
                out[i] = clip(out[i] + decay * samples[i - delay]);
            }
            return out;
        }

        static double[] applyReverb(double[] samples, double delaySec, double decay, int repeats) {
            int delay = (int) Math.max(1, Math.round(delaySec * TARGET_FORMAT.getSampleRate()));
            int k = Math.max(1, repeats);
            double[] out = samples.clone();
            for (int i = 0; i < out.length; i++) {
                double sum = samples[i];
                for (int j = 1; j <= k; j++) {
                    int idx = i - j * delay;
                    if (idx < 0) break;
                    sum += Math.pow(decay, j) * samples[idx];
                }
                out[i] = clip(sum);
            }
            return out;
        }

        static void playWavBlocking(String wavPath) throws Exception {
            File f = new File(wavPath);
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(f)) {
                Clip clip = AudioSystem.getClip();
                Object lock = new Object();
                LineListener listener = event -> {
                    if (event.getType() == LineEvent.Type.STOP) {
                        synchronized (lock) {
                            lock.notifyAll();
                        }
                    }
                };
                clip.addLineListener(listener);
                clip.open(ais);
                clip.start();
                synchronized (lock) {
                    while (clip.isRunning()) {
                        lock.wait(200L);
                    }
                }
                clip.close();
            }
        }

        private static double clip(double v) {
            if (v > 1.0) return 1.0;
            if (v < -1.0) return -1.0;
            return v;
        }
    }

    private static final class MiniJsonParser {
        private final String s;
        private int i;

        MiniJsonParser(String s) {
            this.s = s;
            this.i = 0;
        }

        Object parse() {
            skipWs();
            Object value = parseValue();
            skipWs();
            if (i != s.length()) {
                throw error("Unexpected trailing characters");
            }
            return value;
        }

        private Object parseValue() {
            skipWs();
            if (i >= s.length()) throw error("Unexpected end");
            char c = s.charAt(i);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw error("Unexpected character: " + c);
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWs();
            Map<String, Object> map = new LinkedHashMap<>();
            if (peek('}')) {
                expect('}');
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                if (peek('}')) {
                    expect('}');
                    break;
                }
                expect(',');
            }
            return map;
        }

        private List<Object> parseArray() {
            expect('[');
            skipWs();
            List<Object> list = new ArrayList<>();
            if (peek(']')) {
                expect(']');
                return list;
            }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (peek(']')) {
                    expect(']');
                    break;
                }
                expect(',');
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) throw error("Invalid escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw error("Invalid unicode escape");
                            String hex = s.substring(i, i + 4);
                            b.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        default:
                            throw error("Invalid escape char: " + e);
                    }
                } else {
                    b.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            throw error("Invalid boolean");
        }

        private Object parseNull() {
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            throw error("Invalid null");
        }

        private Number parseNumber() {
            int start = i;
            if (s.charAt(i) == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try {
                return Double.parseDouble(num);
            } catch (NumberFormatException e) {
                throw error("Invalid number: " + num);
            }
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw error("Expected '" + c + "'");
            }
            i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private RuntimeException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + i);
        }
    }

    private static final class MiniYamlParser {
        private final List<String> lines;
        private int index;

        MiniYamlParser(String text) {
            this.lines = preprocess(text);
            this.index = 0;
        }

        Object parse() {
            if (lines.isEmpty()) {
                return new LinkedHashMap<String, Object>();
            }
            return parseBlock(0);
        }

        private Object parseBlock(int indent) {
            if (index >= lines.size()) {
                return new LinkedHashMap<String, Object>();
            }
            String current = lines.get(index);
            int currentIndent = indentOf(current);
            if (currentIndent < indent) {
                return new LinkedHashMap<String, Object>();
            }
            if (trimmedContent(current).startsWith("- ")) {
                return parseList(indent);
            }
            return parseMap(indent);
        }

        private Map<String, Object> parseMap(int indent) {
            Map<String, Object> map = new LinkedHashMap<>();
            while (index < lines.size()) {
                String line = lines.get(index);
                int ind = indentOf(line);
                if (ind < indent) {
                    break;
                }
                if (ind > indent) {
                    throw new IllegalArgumentException("Invalid YAML indentation near: " + line);
                }

                String content = trimmedContent(line);
                if (content.startsWith("- ")) {
                    break;
                }

                int colon = content.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalArgumentException("Invalid YAML map entry: " + content);
                }

                String key = content.substring(0, colon).trim();
                String rest = content.substring(colon + 1).trim();
                index++;

                if (!rest.isEmpty()) {
                    map.put(key, parseScalar(rest));
                } else {
                    if (index < lines.size() && indentOf(lines.get(index)) > indent) {
                        map.put(key, parseBlock(indent + 2));
                    } else {
                        map.put(key, new LinkedHashMap<String, Object>());
                    }
                }
            }
            return map;
        }

        private List<Object> parseList(int indent) {
            List<Object> list = new ArrayList<>();
            while (index < lines.size()) {
                String line = lines.get(index);
                int ind = indentOf(line);
                if (ind < indent) {
                    break;
                }
                if (ind > indent) {
                    throw new IllegalArgumentException("Invalid YAML indentation near: " + line);
                }

                String content = trimmedContent(line);
                if (!content.startsWith("- ")) {
                    break;
                }

                String item = content.substring(2).trim();
                index++;

                if (item.isEmpty()) {
                    if (index < lines.size() && indentOf(lines.get(index)) > indent) {
                        list.add(parseBlock(indent + 2));
                    } else {
                        list.add(null);
                    }
                    continue;
                }

                int colon = item.indexOf(':');
                if (colon > 0) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String key = item.substring(0, colon).trim();
                    String rest = item.substring(colon + 1).trim();
                    if (rest.isEmpty()) {
                        if (index < lines.size() && indentOf(lines.get(index)) > indent) {
                            m.put(key, parseBlock(indent + 2));
                        } else {
                            m.put(key, new LinkedHashMap<String, Object>());
                        }
                    } else {
                        m.put(key, parseScalar(rest));
                    }

                    while (index < lines.size()) {
                        String next = lines.get(index);
                        int nextIndent = indentOf(next);
                        if (nextIndent < indent + 2) {
                            break;
                        }
                        if (nextIndent > indent + 2) {
                            throw new IllegalArgumentException("Invalid YAML indentation near: " + next);
                        }
                        String nextContent = trimmedContent(next);
                        if (nextContent.startsWith("- ")) {
                            break;
                        }
                        int c = nextContent.indexOf(':');
                        if (c <= 0) {
                            throw new IllegalArgumentException("Invalid YAML map entry in list: " + nextContent);
                        }
                        String nk = nextContent.substring(0, c).trim();
                        String nr = nextContent.substring(c + 1).trim();
                        index++;
                        if (!nr.isEmpty()) {
                            m.put(nk, parseScalar(nr));
                        } else if (index < lines.size() && indentOf(lines.get(index)) > indent + 2) {
                            m.put(nk, parseBlock(indent + 4));
                        } else {
                            m.put(nk, new LinkedHashMap<String, Object>());
                        }
                    }
                    list.add(m);
                } else {
                    list.add(parseScalar(item));
                }
            }
            return list;
        }

        private static List<String> preprocess(String text) {
            String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
            List<String> out = new ArrayList<>();
            for (String line : raw) {
                String noComment = stripComment(line);
                if (noComment.trim().isEmpty()) {
                    continue;
                }
                out.add(noComment);
            }
            return out;
        }

        private static String stripComment(String line) {
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && !inDouble) inSingle = !inSingle;
                if (c == '"' && !inSingle) inDouble = !inDouble;
                if (c == '#' && !inSingle && !inDouble) {
                    return line.substring(0, i);
                }
            }
            return line;
        }

        private static int indentOf(String line) {
            int n = 0;
            while (n < line.length() && line.charAt(n) == ' ') {
                n++;
            }
            return n;
        }

        private static String trimmedContent(String line) {
            return line.trim();
        }
    }

    private static Object parseScalar(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        if ("null".equalsIgnoreCase(v)) return null;
        if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
        try {
            if (v.contains(".") || v.contains("e") || v.contains("E")) {
                return Double.parseDouble(v);
            }
            return Double.parseDouble(v);
        } catch (NumberFormatException ignore) {
            return v;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object obj) {
        if (!(obj instanceof Map)) {
            throw new IllegalArgumentException("Expected object/map but got: " + typeName(obj));
        }
        return (Map<String, Object>) obj;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object obj) {
        if (!(obj instanceof List)) {
            throw new IllegalArgumentException("Expected array/list but got: " + typeName(obj));
        }
        return (List<Object>) obj;
    }

    private static String typeName(Object obj) {
        return obj == null ? "null" : obj.getClass().getSimpleName();
    }

    private static String requiredString(Map<String, Object> map, String key) {
        String s = stringOrNull(map.get(key));
        if (!notBlank(s)) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return s;
    }

    private static Object firstPresent(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k)) {
                return map.get(k);
            }
        }
        return null;
    }

    private static String stringOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        return String.valueOf(obj);
    }

    private static Double doubleOrNull(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).doubleValue();
        try {
            return Double.parseDouble(Objects.toString(obj));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected number but got: " + obj);
        }
    }

    private static double doubleOrDefault(Object obj, double def) {
        Double d = doubleOrNull(obj);
        return d == null ? def : d;
    }

    private static boolean boolOrDefault(Object obj, boolean def) {
        if (obj == null) return def;
        if (obj instanceof Boolean) return (Boolean) obj;
        String s = String.valueOf(obj).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s)) return true;
        if ("false".equals(s)) return false;
        return def;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String jsonUnescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            String hex = s.substring(i + 1, i + 5);
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                        break;
                    default:
                        out.append(n);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}

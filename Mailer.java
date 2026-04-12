import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class Mailer {

    private static final DateTimeFormatter TS_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static class Config {
        String recipientsFile;
        String templateFile;
        String outboxDir;
        String sentLog = "sent.log";
        int limitPerMinute = 0;
        String blacklistFile;
        boolean dryRun = false;
    }

    private static class Template {
        String subject;
        String body;
    }

    public static void main(String[] args) {
        Config config = parseArgs(args);
        if (config == null) {
            System.exit(1);
            return;
        }

        try {
            run(config);
        } catch (Exception e) {
            System.err.println("运行失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Config parseArgs(String[] args) {
        Config cfg = new Config();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-r":
                    if (i + 1 >= args.length) return argError("缺少 -r 参数值");
                    cfg.recipientsFile = args[++i];
                    break;
                case "-t":
                    if (i + 1 >= args.length) return argError("缺少 -t 参数值");
                    cfg.templateFile = args[++i];
                    break;
                case "-o":
                    if (i + 1 >= args.length) return argError("缺少 -o 参数值");
                    cfg.outboxDir = args[++i];
                    break;
                case "-s":
                    if (i + 1 >= args.length) return argError("缺少 -s 参数值");
                    cfg.sentLog = args[++i];
                    break;
                case "--limit":
                    if (i + 1 >= args.length) return argError("缺少 --limit 参数值");
                    try {
                        cfg.limitPerMinute = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        return argError("--limit 必须是正整数");
                    }
                    if (cfg.limitPerMinute <= 0) {
                        return argError("--limit 必须大于 0");
                    }
                    break;
                case "--blacklist":
                    if (i + 1 >= args.length) return argError("缺少 --blacklist 参数值");
                    cfg.blacklistFile = args[++i];
                    break;
                case "--dry-run":
                    cfg.dryRun = true;
                    break;
                default:
                    return argError("未知参数: " + arg);
            }
        }

        if (cfg.recipientsFile == null) return argError("必须指定 -r <recipients.txt>");
        if (cfg.templateFile == null) return argError("必须指定 -t <template.txt>");
        if (cfg.dryRun && cfg.outboxDir == null) return argError("--dry-run 模式必须指定 -o <outbox/>");

        return cfg;
    }

    private static Config argError(String msg) {
        System.err.println("参数错误: " + msg);
        printUsage();
        return null;
    }

    private static void printUsage() {
        System.err.println("用法:");
        System.err.println("  java Mailer -r <recipients.txt> -t <template.txt> [-o <outbox/>] [-s <sent.log>] ");
        System.err.println("              [--limit <n>] [--blacklist <black.txt>] [--dry-run]");
    }

    private static void run(Config cfg) throws Exception {
        Template template = loadTemplate(cfg.templateFile);
        List<String> recipients = loadRecipients(cfg.recipientsFile);
        Set<String> blacklist = loadSimpleEmailSet(cfg.blacklistFile);
        Set<String> alreadySent = loadAlreadySent(cfg.sentLog);

        if (cfg.dryRun) {
            Files.createDirectories(Paths.get(cfg.outboxDir));
        }

        SmtpConfig smtp = SmtpConfig.fromEnv();
        MailSender sender = smtp.isComplete() ? new ReflectiveJavaMailSender(smtp) : new SimulatedSender();

        int success = 0;
        int failure = 0;
        int skipped = 0;
        int processedInWindow = 0;
        long windowStart = System.currentTimeMillis();
        int previewIndex = 1;

        Path sentLogPath = Paths.get(cfg.sentLog);
        ensureParentDir(sentLogPath);

        for (String recipient : recipients) {
            if (blacklist.contains(recipient)) {
                skipped++;
                logResult(sentLogPath, recipient, template.subject, "SKIPPED_BLACKLIST");
                continue;
            }
            if (alreadySent.contains(recipient)) {
                skipped++;
                logResult(sentLogPath, recipient, template.subject, "SKIPPED_ALREADY_SENT");
                continue;
            }

            String body = personalize(template.body, recipient);
            String subject = personalize(template.subject, recipient);

            if (cfg.dryRun) {
                Path emlPath = Paths.get(cfg.outboxDir, previewIndex + "_" + safeFileName(recipient) + ".eml");
                writeEml(emlPath, smtp.fromOrDefault(), recipient, subject, body);
                logResult(sentLogPath, recipient, subject, "DRY_RUN_OK");
                success++;
                previewIndex++;
            } else {
                try {
                    String result = sender.send(recipient, subject, body);
                    if (result.startsWith("SUCCESS")) {
                        success++;
                        alreadySent.add(recipient);
                    } else {
                        failure++;
                    }
                    logResult(sentLogPath, recipient, subject, result);
                } catch (Exception e) {
                    failure++;
                    String reason = "FAIL:" + sanitizeLogField(e.getMessage());
                    logResult(sentLogPath, recipient, subject, reason);
                    System.err.println("发送失败 " + recipient + ": " + e.getMessage());
                }
            }

            if (cfg.limitPerMinute > 0) {
                processedInWindow++;
                if (processedInWindow >= cfg.limitPerMinute) {
                    long now = System.currentTimeMillis();
                    long elapsed = now - windowStart;
                    if (elapsed < 60_000L) {
                        long waitMs = 60_000L - elapsed;
                        try {
                            Thread.sleep(waitMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            System.err.println("限速等待被中断，继续执行。");
                        }
                    }
                    windowStart = System.currentTimeMillis();
                    processedInWindow = 0;
                }
            }
        }

        System.out.println("发送完成: 成功=" + success + " 失败=" + failure + " 跳过=" + skipped);
        if (!(sender instanceof ReflectiveJavaMailSender)) {
            System.out.println("当前为模拟发送模式（未检测到完整 SMTP 环境变量）。");
        }
    }

    private static Template loadTemplate(String templateFile) throws IOException {
        Template t = new Template();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(templateFile), StandardCharsets.UTF_8))) {
            String subject = reader.readLine();
            if (subject == null) {
                throw new IOException("模板文件为空: " + templateFile);
            }
            t.subject = stripBom(subject);

            StringBuilder body = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                line = stripBom(line);
                if (!first) body.append(System.lineSeparator());
                body.append(line);
                first = false;
            }
            t.body = body.toString();
        }
        return t;
    }

    private static List<String> loadRecipients(String recipientsFile) throws IOException {
        List<String> list = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(recipientsFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String email = stripBom(line).trim().toLowerCase();
                if (email.isEmpty()) continue;
                list.add(email);
            }
        }
        return list;
    }

    private static Set<String> loadSimpleEmailSet(String file) throws IOException {
        Set<String> set = new HashSet<>();
        if (file == null) return set;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String email = stripBom(line).trim().toLowerCase();
                if (!email.isEmpty()) {
                    set.add(email);
                }
            }
        }
        return set;
    }

    private static Set<String> loadAlreadySent(String sentLogFile) throws IOException {
        Set<String> sent = new LinkedHashSet<>();
        Path p = Paths.get(sentLogFile);
        if (!Files.exists(p)) {
            return sent;
        }

        try (BufferedReader reader = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripBom(line);
                String[] parts = line.split(",", 4);
                if (parts.length < 4) continue;
                String recipient = stripBom(parts[1]).trim().toLowerCase();
                String result = parts[3].trim();
                if (result.startsWith("SUCCESS")) {
                    sent.add(recipient);
                }
            }
        }
        return sent;
    }

    private static String stripBom(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    private static String personalize(String text, String recipient) {
        int at = recipient.indexOf('@');
        String name = at > 0 ? recipient.substring(0, at) : recipient;
        return text
                .replace("{email}", recipient)
                .replace("{name}", name)
                .replace("{city}", "your city");
    }

    private static void writeEml(Path emlPath, String from, String to, String subject, String body) throws IOException {
        ensureParentDir(emlPath);
        try (BufferedWriter writer = Files.newBufferedWriter(
                emlPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writer.write("From: " + from);
            writer.newLine();
            writer.write("To: " + to);
            writer.newLine();
            writer.write("Subject: " + subject);
            writer.newLine();
            writer.write("Content-Type: text/plain; charset=UTF-8");
            writer.newLine();
            writer.newLine();
            writer.write(body);
        }
    }

    private static void logResult(Path sentLogPath, String recipient, String subject, String result) {
        String ts = TS_FORMATTER.format(Instant.now());
        String line = sanitizeLogField(ts) + ","
                + sanitizeLogField(recipient) + ","
                + sanitizeLogField(subject) + ","
                + sanitizeLogField(result);

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                Files.newOutputStream(sentLogPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND),
                StandardCharsets.UTF_8))) {
            out.println(line);
        } catch (IOException e) {
            System.err.println("写发送日志失败: " + e.getMessage());
        }
    }

    private static String sanitizeLogField(String s) {
        if (s == null) return "";
        return s.replace("\r", " ").replace("\n", " ").replace(",", "，");
    }

    private static String safeFileName(String recipient) {
        return recipient.replaceAll("[^a-zA-Z0-9@._-]", "_");
    }

    private static void ensureParentDir(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private interface MailSender {
        String send(String to, String subject, String body) throws Exception;
    }

    private static class SimulatedSender implements MailSender {
        @Override
        public String send(String to, String subject, String body) {
            return "SUCCESS:SIMULATED";
        }
    }

    private static class SmtpConfig {
        String host;
        String port;
        String user;
        String pass;
        String from;

        static SmtpConfig fromEnv() {
            SmtpConfig c = new SmtpConfig();
            c.host = getenv("SMTP_HOST");
            c.port = getenv("SMTP_PORT");
            c.user = getenv("SMTP_USER");
            c.pass = getenv("SMTP_PASS");
            c.from = getenv("MAIL_FROM");
            return c;
        }

        boolean isComplete() {
            return notEmpty(host) && notEmpty(port) && notEmpty(user) && notEmpty(pass) && notEmpty(from);
        }

        String fromOrDefault() {
            return notEmpty(from) ? from : "no-reply@example.com";
        }

        private static String getenv(String name) {
            String v = System.getenv(name);
            return v == null ? "" : v.trim();
        }

        private static boolean notEmpty(String v) {
            return v != null && !v.isEmpty();
        }
    }

    private static class ReflectiveJavaMailSender implements MailSender {
        private final SmtpConfig cfg;

        ReflectiveJavaMailSender(SmtpConfig cfg) {
            this.cfg = cfg;
        }

        @Override
        public String send(String to, String subject, String body) throws Exception {
            try {
                return sendWithPackage("javax.mail", "javax.mail.internet", to, subject, body);
            } catch (ClassNotFoundException e) {
                return sendWithPackage("jakarta.mail", "jakarta.mail.internet", to, subject, body);
            }
        }

        private String sendWithPackage(String mailPkg, String internetPkg, String to, String subject, String body)
                throws Exception {
            Class<?> propsClass = Properties.class;
            Class<?> sessionClass = Class.forName(mailPkg + ".Session");
            Class<?> messageClass = Class.forName(mailPkg + ".Message");
            Class<?> recipientTypeClass = Class.forName(mailPkg + ".Message$RecipientType");
            Class<?> addressClass = Class.forName(mailPkg + ".Address");
            Class<?> transportClass = Class.forName(mailPkg + ".Transport");
            Class<?> internetAddressClass = Class.forName(internetPkg + ".InternetAddress");
            Class<?> mimeMessageClass = Class.forName(internetPkg + ".MimeMessage");

            Properties props = new Properties();
            props.put("mail.smtp.host", cfg.host);
            props.put("mail.smtp.port", cfg.port);
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

                Object session = sessionClass.getMethod("getInstance", propsClass).invoke(null, props);

            Object message = mimeMessageClass.getConstructor(sessionClass).newInstance(session);

                message.getClass().getMethod("setFrom", addressClass)
                    .invoke(message, internetAddressClass.getConstructor(String.class).newInstance(cfg.from));

                Object recipientType = recipientTypeClass.getField("TO").get(null);
            Object toAddress = internetAddressClass.getConstructor(String.class).newInstance(to);
            message.getClass()
                    .getMethod("setRecipient", recipientTypeClass, addressClass)
                    .invoke(message, recipientType, toAddress);

            message.getClass().getMethod("setSubject", String.class, String.class)
                    .invoke(message, subject, "UTF-8");
            message.getClass().getMethod("setText", String.class, String.class)
                    .invoke(message, body, "UTF-8");

                transportClass.getMethod("send", messageClass, String.class, String.class)
                    .invoke(null, message, cfg.user, cfg.pass);
            return "SUCCESS";
        }
    }
}

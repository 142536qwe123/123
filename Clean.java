import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Locale;
import java.util.regex.Pattern;


public class Clean {

    // 字段分隔符正则：逗号、分号、竖线
    private static final Pattern SEPARATOR = Pattern.compile("[;,|]");

    // 命令行参数
    private static String inputFile = null;
    private static String outputFile = null;
    private static String badFile = null;
    private static String logFile = null;
    private static String mode = "clean";      // clean 或 emails
    private static String format = "formal";   // formal 或 lower
    private static boolean dedup = false;      // 仅在 emails 模式下有效

    // 统计信息
    private static int totalLines = 0;
    private static int goodLines = 0;
    private static int badLines = 0;
    private static int uniqueEmails = 0;       // emails 模式去重后数量

    public static void main(String[] args) {
        // 1. 解析命令行参数
        if (!parseArguments(args)) {
            System.exit(1);
        }

        // 2. 处理文件
        try {
            process();
        } catch (IOException e) {
            System.err.println("I/O 错误: " + e.getMessage());
            System.exit(1);
        }

        // 3. 写入日志（如果指定了 --log）
        if (logFile != null) {
            writeLog();
        }
    }


    private static boolean parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-i":
                    if (i + 1 < args.length) inputFile = args[++i];
                    else return error("缺少 -i 的参数");
                    break;
                case "-o":
                    if (i + 1 < args.length) outputFile = args[++i];
                    else return error("缺少 -o 的参数");
                    break;
                case "--bad":
                    if (i + 1 < args.length) badFile = args[++i];
                    else return error("缺少 --bad 的参数");
                    break;
                case "--log":
                    if (i + 1 < args.length) logFile = args[++i];
                    else return error("缺少 --log 的参数");
                    break;
                case "--mode":
                    if (i + 1 < args.length) {
                        String m = args[++i];
                        if (m.equals("clean") || m.equals("emails")) mode = m;
                        else return error("--mode 只能是 clean 或 emails");
                    } else return error("缺少 --mode 的参数");
                    break;
                case "--format":
                    if (i + 1 < args.length) {
                        String f = args[++i];
                        if (f.equals("formal") || f.equals("lower")) format = f;
                        else return error("--format 只能是 formal 或 lower");
                    } else return error("缺少 --format 的参数");
                    break;
                case "--dedup":
                    dedup = true;
                    break;
                default:
                    return error("未知参数: " + args[i]);
            }
        }

        if (inputFile == null) return error("必须指定 -i 输入文件");
        return true;
    }

    private static boolean error(String msg) {
        System.err.println("参数错误: " + msg);
        System.err.println("用法:");
        System.err.println("  java Clean -i <输入文件> [-o <输出文件>] [--bad <坏行文件>] [--log <日志文件>]");
        System.err.println("        [--mode clean|emails] [--format formal|lower] [--dedup]");
        return false;
    }

    /** 核心处理逻辑 */
    private static void process() throws IOException {
        // 打开输入文件（UTF-8 编码）
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8));
             // 输出流：clean 模式需要按行输出，emails 模式需要收集后输出
        ) {
            // 根据模式选择输出方式
            PrintWriter outputWriter = null;
            PrintWriter badWriter = null;
            boolean useStdout = (outputFile == null);

            try {
                if (!useStdout) {
                    outputWriter = new PrintWriter(new OutputStreamWriter(
                            new FileOutputStream(outputFile), StandardCharsets.UTF_8));
                }
                if (badFile != null) {
                    badWriter = new PrintWriter(new OutputStreamWriter(
                            new FileOutputStream(badFile), StandardCharsets.UTF_8));
                }

                if (mode.equals("clean")) {
                    // clean 模式：逐行处理，直接输出
                    String line;
                    while ((line = reader.readLine()) != null) {
                        totalLines++;
                        String result = processCleanLine(line);
                        if (result != null) {
                            // 合法行
                            goodLines++;
                            if (outputWriter != null) {
                                outputWriter.println(result);
                            } else {
                                System.out.println(result);
                            }
                        } else {
                            // 坏行
                            badLines++;
                            if (badWriter != null) {
                                badWriter.println(line);
                            }
                        }
                    }
                } else { // emails 模式
                    // 先收集所有合法邮箱
                    Set<String> emailSet = dedup ? new LinkedHashSet<>() : null;
                    List<String> emailList = dedup ? null : new ArrayList<>();

                    String line;
                    while ((line = reader.readLine()) != null) {
                        totalLines++;
                        String email = extractEmail(line);
                        if (email != null) {
                            goodLines++;
                            if (dedup) {
                                emailSet.add(email);
                            } else {
                                emailList.add(email);
                            }
                        } else {
                            badLines++;
                            if (badWriter != null) {
                                badWriter.println(line);
                            }
                        }
                    }


                    if (dedup) {
                        uniqueEmails = emailSet.size();
                        PrintWriter out = outputWriter != null ? outputWriter : new PrintWriter(System.out, true);
                        for (String e : emailSet) {
                            out.println(e);
                        }
                        if (outputWriter == null) out.flush();
                    } else {
                        uniqueEmails = emailList.size();
                        PrintWriter out = outputWriter != null ? outputWriter : new PrintWriter(System.out, true);
                        for (String e : emailList) {
                            out.println(e);
                        }
                        if (outputWriter == null) out.flush();
                    }
                }
            } finally {
                if (outputWriter != null) outputWriter.close();
                if (badWriter != null) badWriter.close();
            }
        }
    }


    private static String processCleanLine(String rawLine) {
        String[] parts = SEPARATOR.split(rawLine);

        if (parts.length != 3) return null;
        String name = parts[0].trim();
        String email = parts[1].trim();
        String city = parts[2].trim();
        if (name.isEmpty() || email.isEmpty() || city.isEmpty()) return null;

        if (!isValidEmail(email)) return null;

        name = normalizeNameCity(name);
        city = normalizeNameCity(city);
        email = email.toLowerCase(Locale.ROOT); // 邮箱一律小写

        return name + "," + email + "," + city;
    }


    private static String extractEmail(String rawLine) {
        String[] parts = SEPARATOR.split(rawLine);
        if (parts.length != 3) return null;
        String name = parts[0].trim();
        String email = parts[1].trim();
        String city = parts[2].trim();
        if (name.isEmpty() || email.isEmpty() || city.isEmpty()) return null;
        if (!isValidEmail(email)) return null;
        return email.toLowerCase(Locale.ROOT);
    }

    private static boolean isValidEmail(String email) {

        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (!((c >= 'a' && c <= 'z') ||
                  (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') ||
                  c == '@' || c == '.')) {
                return false;
            }
        }

        int atIdx = -1;
        int atCnt = 0;
        for (int i = 0; i < email.length(); i++) {
            if (email.charAt(i) == '@') {
                atCnt++;
                atIdx = i;
            }
        }
        if (atCnt != 1) return false;
        if (atIdx == 0 || atIdx == email.length() - 1) return false;

        String prefix = email.substring(0, atIdx);
        if (prefix.contains(".")) return false;
        String suffix = email.substring(atIdx + 1);
        if (!suffix.contains(".")) return false;

        for (int i = 0; i < email.length(); i++) {
            if (email.charAt(i) == '.') {
                if (i == 0 || i == email.length() - 1) return false;
                char prev = email.charAt(i - 1);
                char next = email.charAt(i + 1);
                if (!isLetterOrDigit(prev) || !isLetterOrDigit(next)) return false;
            }
        }
        return true;
    }

    private static boolean isLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') ||
               (c >= '0' && c <= '9');
    }

    private static String normalizeNameCity(String s) {
        if (format.equals("lower")) {
            return s.toLowerCase(Locale.ROOT);
        } else { // formal

            StringBuilder sb = new StringBuilder();
            boolean nextUpper = true;
            for (char c : s.toCharArray()) {
                if (Character.isWhitespace(c)) {
                    sb.append(c);
                    nextUpper = true;
                } else {
                    if (nextUpper) {
                        sb.append(Character.toUpperCase(c));
                        nextUpper = false;
                    } else {
                        sb.append(Character.toLowerCase(c));
                    }
                }
            }
            return sb.toString();
        }
    }

    private static void writeLog() {
        try (PrintWriter logWriter = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8))) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            logWriter.println("程序运行日志");
            logWriter.println("时间: " + sdf.format(new Date()));
            logWriter.println("输入文件: " + inputFile);
            logWriter.println("输出文件: " + (outputFile == null ? "<标准输出>" : outputFile));
            logWriter.println("坏行文件: " + (badFile == null ? "<未指定>" : badFile));
            logWriter.println("运行模式: " + mode);
            logWriter.println("大小写格式: " + format);
            logWriter.println("邮箱去重: " + (dedup ? "是" : "否"));
            logWriter.println("总行数: " + totalLines);
            logWriter.println("合格行数: " + goodLines);
            logWriter.println("坏行数: " + badLines);
            if (mode.equals("emails")) {
                logWriter.println("输出邮箱数量: " + uniqueEmails);
            }
            logWriter.println("处理完成。");
        } catch (IOException e) {
            System.err.println("无法写入日志文件: " + e.getMessage());
        }
    }
}
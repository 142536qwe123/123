import java.util.Arrays;

public class EmailTool {
    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
            return;
        }

        String command = args[0];
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "clean":
                Clean.main(subArgs);
                break;
            case "mail":
                Mailer.main(subArgs);
                break;
            default:
                System.err.println("未知命令: " + command);
                printUsage();
                System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("用法:");
        System.err.println("  java EmailTool clean <Clean 参数...>");
        System.err.println("  java EmailTool mail <Mailer 参数...>");
    }
}
